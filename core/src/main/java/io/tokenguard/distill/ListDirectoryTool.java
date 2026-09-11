package io.tokenguard.distill;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.util.IgnoreRules;
import io.tokenguard.util.Json;
import io.tokenguard.util.PathJail;
import io.tokenguard.util.TokenEstimator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Compact, gitignore-aware tree (plan §5.1), hard-capped and truncation-labeled. */
public final class ListDirectoryTool implements Tool {

    private static final int MAX_ENTRIES = 500;

    private final PathJail jail;
    private final IgnoreRules ignore;
    private final Metrics metrics;

    public ListDirectoryTool(PathJail jail, IgnoreRules ignore, Metrics metrics) {
        this.jail = jail;
        this.ignore = ignore;
        this.metrics = metrics;
    }

    @Override public String name() { return "list_directory"; }

    @Override public String description() {
        return "List workspace files as a compact indented tree — far smaller than raw "
                + "directory dumps (gitignore-aware, noise directories excluded, output "
                + "capped). Use depth to control recursion.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode props = Json.obj();
        props.set("path", Json.prop("string", "Directory to list (default: workspace root)"));
        props.set("depth", Json.prop("integer", "Recursion depth, 1-10 (default 2)"));
        return Json.schemaObject(props);
    }

    @Override
    public ToolResult call(ObjectNode args) throws Exception {
        Path dir = jail.resolve(Json.str(args, "path", "."));
        if (!Files.isDirectory(dir)) return ToolResult.error("Not a directory: " + jail.relativize(dir));
        int depth = Math.clamp(Json.intVal(args, "depth", 2), 1, 10);

        StringBuilder sb = new StringBuilder();
        String relRoot = jail.relativize(dir);
        sb.append(relRoot.isEmpty() ? "." : relRoot).append("/\n");
        int[] budget = {MAX_ENTRIES};
        walk(dir, depth, "  ", sb, budget);
        if (budget[0] <= 0)
            sb.append("[truncated at ").append(MAX_ENTRIES)
              .append(" entries — list a subdirectory for more]");
        String out = sb.toString();
        metrics.tokens(name(), TokenEstimator.estimate(out), TokenEstimator.estimate(out));
        return ToolResult.text(out);
    }

    private void walk(Path dir, int depth, String indent, StringBuilder sb, int[] budget)
            throws IOException {
        if (depth <= 0 || budget[0] <= 0) return;
        List<Path> children;
        try (Stream<Path> s = Files.list(dir)) {
            children = s.filter(p -> !ignore.ignored(jail.root().relativize(p)))
                    .sorted((a, b) -> {
                        boolean da = Files.isDirectory(a), db = Files.isDirectory(b);
                        return da == db ? a.getFileName().compareTo(b.getFileName()) : (da ? -1 : 1);
                    })
                    .toList();
        }
        for (Path child : children) {
            if (budget[0]-- <= 0) return;
            boolean isDir = Files.isDirectory(child);
            sb.append(indent).append(child.getFileName());
            if (isDir) {
                sb.append("/\n");
                walk(child, depth - 1, indent + "  ", sb, budget);
            } else {
                sb.append('\n');
            }
        }
    }
}
