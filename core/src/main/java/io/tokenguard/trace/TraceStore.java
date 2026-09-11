package io.tokenguard.trace;

import io.tokenguard.store.Database;
import io.tokenguard.util.Log;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO for interaction_trace (enhancement plan P0.2, schema v2). Append-heavy;
 * every write is failure-swallowing — tracing must never break a tool call
 * (§6.7 invariant, same discipline as Metrics.event).
 */
public final class TraceStore {

    /** One recorded interaction; also the query-side row shape. */
    public record Row(String sessionId, long ts, String clientName, String clientVersion,
                      String tool, String engine, long rawTokens, long returnedTokens,
                      boolean wasError, boolean wasPassthrough, String optimization) {}

    private final Database db;

    public TraceStore(Database db) {
        this.db = db;
    }

    public void record(Row row) {
        try {
            db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO interaction_trace(session_id, ts, client_name, client_version, "
                                + "tool, engine, raw_tokens, returned_tokens, was_error, "
                                + "was_passthrough, optimization) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, row.sessionId());
                    ps.setLong(2, row.ts() > 0 ? row.ts() : Instant.now().getEpochSecond());
                    ps.setString(3, row.clientName());
                    ps.setString(4, row.clientVersion());
                    ps.setString(5, row.tool());
                    ps.setString(6, row.engine());
                    ps.setLong(7, row.rawTokens());
                    ps.setLong(8, row.returnedTokens());
                    ps.setInt(9, row.wasError() ? 1 : 0);
                    ps.setInt(10, row.wasPassthrough() ? 1 : 0);
                    ps.setString(11, row.optimization());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            Log.debug("trace write failed: " + e.getMessage());
        }
    }

    /** Rows for one session, oldest first — verification + guard_status (P2.2). */
    public List<Row> session(String sessionId) {
        try {
            return db.with(c -> {
                List<Row> out = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT session_id, ts, client_name, client_version, tool, engine, "
                                + "raw_tokens, returned_tokens, was_error, was_passthrough, optimization "
                                + "FROM interaction_trace WHERE session_id = ? ORDER BY ts, id")) {
                    ps.setString(1, sessionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new Row(rs.getString(1), rs.getLong(2), rs.getString(3),
                                    rs.getString(4), rs.getString(5), rs.getString(6),
                                    rs.getLong(7), rs.getLong(8), rs.getInt(9) != 0,
                                    rs.getInt(10) != 0, rs.getString(11)));
                        }
                    }
                }
                return out;
            });
        } catch (Exception e) {
            Log.debug("trace read failed: " + e.getMessage());
            return List.of();
        }
    }
}
