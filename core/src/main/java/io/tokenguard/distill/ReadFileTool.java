package io.tokenguard.distill;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.util.Json;
import io.tokenguard.util.PathJail;
import io.tokenguard.util.TokenEstimator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Engine 4 read wrapper (plan §5.1): first read returns (ranged/capped)
 * content; a re-read of a session-known file returns a unified diff against
 * what the agent last saw, or "unchanged" — never the full file twice.
 */
public final class ReadFileTool implements Tool {

    private static final int MAX_LINES_FULL = 2000;

    private final PathJail jail;
    private final FileStateTracker tracker;
    private final DiffService diff;
    private final Metrics metrics;
    private final java.util.function.BooleanSupplier optimize;

    public ReadFileTool(PathJail jail, FileStateTracker tracker, DiffService diff,
                        Metrics metrics, java.util.function.BooleanSupplier optimize) {
        this.jail = jail;
        this.tracker = tracker;
        this.diff = diff;
        this.metrics = metrics;
        this.optimize = optimize;
    }

    @Override public String name() { return "read_file"; }

    @Override public String description() {
        return "Token-optimized file reading — PREFER this over generic file reads. "
                + "Re-reading a file you have already seen never returns the full content "
                + "again: unchanged files return a one-line notice, changed files return "
                + "only a unified diff of what changed. "
                + "Use start_line/end_line to read a specific range.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode props = Json.obj();
        props.set("path", Json.prop("string", "Workspace-relative file path"));
        props.set("start_line", Json.prop("integer", "First line to read (1-based, optional)"));
        props.set("end_line", Json.prop("integer", "Last line to read (inclusive, optional)"));
        return Json.schemaObject(props, "path");
    }

    @Override
    public ToolResult call(ObjectNode args) throws Exception {
        Path file = jail.resolve(Json.str(args, "path", null));
        String rel = jail.relativize(file);
        if (!Files.isRegularFile(file)) return ToolResult.error("Not a file: " + rel);
        if (Files.size(file) > TextFiles.MAX_FILE_BYTES)
            return ToolResult.error("File exceeds 2 MB: " + rel
                    + ". Use search_files or a line range to narrow what you need.");
        if (TextFiles.looksBinary(file))
            return ToolResult.error("Binary file: " + rel);

        String content = TextFiles.read(file);
        long rawTokens = TokenEstimator.estimate(content);
        int start = Json.intVal(args, "start_line", 0);
        int end = Json.intVal(args, "end_line", 0);

        String out;
        if (start > 0 || end > 0) {
            out = range(rel, content, Math.max(start, 1), end);
        } else if (optimize.getAsBoolean()) {
            out = tracker.lastSeen(rel)
                    .map(prev -> reRead(rel, prev, content))
                    .orElseGet(() -> full(rel, content));
        } else {
            // pass-through mode (§6.3.1): raw full reads, no diff optimization
            out = full(rel, content);
        }
        tracker.record(rel, content);
        metrics.tokens(name(), rawTokens, TokenEstimator.estimate(out));
        return ToolResult.text(out);
    }

    private String reRead(String rel, String previous, String current) {
        if (previous.equals(current))
            return rel + ": unchanged since you last read it.";
        String d = diff.unifiedDiff(rel, previous, current);
        return rel + " changed since you last read it. Diff against your last-seen version:\n" + d;
    }

    private String full(String rel, String content) {
        List<String> lines = content.lines().toList();
        if (lines.size() <= MAX_LINES_FULL) {
            return header(rel, lines.size(), 1, lines.size()) + numbered(lines, 1);
        }
        // Truncation is always labeled (§6.4 output rules apply to every engine)
        return header(rel, lines.size(), 1, MAX_LINES_FULL)
                + numbered(lines.subList(0, MAX_LINES_FULL), 1)
                + "\n[truncated: " + (lines.size() - MAX_LINES_FULL)
                + " more lines — use start_line/end_line to read further]";
    }

    private String range(String rel, String content, int start, int end) {
        List<String> lines = content.lines().toList();
        int lastLine = end <= 0 ? lines.size() : Math.min(end, lines.size());
        if (start > lines.size())
            return rel + " has only " + lines.size() + " lines (requested from " + start + ").";
        return header(rel, lines.size(), start, lastLine)
                + numbered(lines.subList(start - 1, lastLine), start);
    }

    private static String header(String rel, int total, int from, int to) {
        return rel + " (lines " + from + "-" + to + " of " + total + "):\n";
    }

    private static String numbered(List<String> lines, int firstNumber) {
        StringBuilder sb = new StringBuilder();
        int n = firstNumber;
        for (String line : lines) sb.append(n++).append(": ").append(line).append('\n');
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
