package io.tokenguard.distill;

import io.tokenguard.store.Database;
import io.tokenguard.util.Log;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P3.3 (enhancement plan; context-mode-validated pattern, KC-6): rolling
 * journal of what happened this workspace-session — files read, edits applied,
 * errors — persisted so a fresh process (post-compaction, post-restart) can
 * hand the agent a dense state summary via restore_context.
 *
 * Local-only by design: paths stay on this machine (§6.6.4). Capped at
 * maxEvents per workspace with oldest-first eviction. Writes are
 * failure-swallowing (§6.7 — never break a tool call).
 */
public final class SessionJournal {

    private final Database db;
    private final String workspaceId;
    private final int maxEvents;

    public SessionJournal(Database db, String workspaceId, int maxEvents) {
        this.db = db;
        this.workspaceId = workspaceId;
        this.maxEvents = Math.max(10, maxEvents);
    }

    /** kinds: read | edit | error | call */
    public void record(String kind, String detail) {
        try {
            db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO session_journal(workspace_id, ts, kind, detail) "
                                + "VALUES (?,?,?,?)")) {
                    ps.setString(1, workspaceId);
                    ps.setLong(2, Instant.now().getEpochSecond());
                    ps.setString(3, kind);
                    ps.setString(4, detail);
                    ps.executeUpdate();
                }
                // oldest-first cap (config session.journal_max_events)
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM session_journal WHERE workspace_id = ? AND id NOT IN "
                                + "(SELECT id FROM session_journal WHERE workspace_id = ? "
                                + " ORDER BY id DESC LIMIT ?)")) {
                    ps.setString(1, workspaceId);
                    ps.setString(2, workspaceId);
                    ps.setInt(3, maxEvents);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            Log.debug("journal write failed: " + e.getMessage());
        }
    }

    public int eventCount() {
        try {
            return db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM session_journal WHERE workspace_id = ?")) {
                    ps.setString(1, workspaceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            });
        } catch (Exception e) {
            return 0;
        }
    }

    private record Event(String kind, String detail) {}

    /** Dense state summary — the restore_context payload. */
    public String render() {
        List<Event> events = new ArrayList<>();
        try {
            db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT kind, detail FROM session_journal WHERE workspace_id = ? "
                                + "ORDER BY id")) {
                    ps.setString(1, workspaceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) events.add(new Event(rs.getString(1), rs.getString(2)));
                    }
                }
                return null;
            });
        } catch (Exception e) {
            return "session journal unavailable: " + e.getMessage();
        }
        if (events.isEmpty())
            return "No prior session activity recorded for this workspace.";

        Set<String> reads = new LinkedHashSet<>();
        Set<String> edits = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        List<String> lastCalls = new ArrayList<>();
        for (Event e : events) {
            switch (e.kind()) {
                case "read" -> reads.add(e.detail());
                case "edit" -> edits.add(e.detail());
                case "error" -> errors.add(e.detail());
                default -> lastCalls.add(e.detail());
            }
        }
        StringBuilder sb = new StringBuilder("Restored session state (" + events.size()
                + " events, oldest first):\n");
        if (!edits.isEmpty())
            sb.append("edited: ").append(String.join(", ", cap(edits, 15))).append('\n');
        if (!reads.isEmpty())
            sb.append("read: ").append(String.join(", ", cap(reads, 20))).append('\n');
        if (!errors.isEmpty()) {
            sb.append("errors (" + errors.size() + "):\n");
            for (String err : errors.subList(Math.max(0, errors.size() - 5), errors.size()))
                sb.append("  - ").append(err).append('\n');
        }
        if (!lastCalls.isEmpty()) {
            List<String> tail = lastCalls.subList(Math.max(0, lastCalls.size() - 8),
                    lastCalls.size());
            sb.append("recent calls: ").append(String.join(", ", tail)).append('\n');
        }
        sb.append("(re-reads of the files above return diffs/'unchanged', not full content)");
        return sb.toString();
    }

    private static List<String> cap(Set<String> xs, int n) {
        List<String> list = new ArrayList<>(xs);
        if (list.size() <= n) return list;
        List<String> out = new ArrayList<>(list.subList(list.size() - n, list.size()));
        out.add("… +" + (list.size() - n) + " earlier");
        return out;
    }
}
