package io.tokenguard.distill;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.cache.DependencyTracker;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.util.IgnoreRules;
import io.tokenguard.util.Json;
import io.tokenguard.util.PathJail;
import io.tokenguard.util.TokenEstimator;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Grouped, capped regex search (plan §5.1). The naive-baseline metric is what
 * an agent would otherwise do: read each matched file in full (A6 estimate).
 */
public final class SearchFilesTool implements Tool {

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int HARD_MAX_RESULTS = 200;

    private final PathJail jail;
    private final IgnoreRules ignore;
    private final Metrics metrics;
    private final DependencyTracker tracker;

    public SearchFilesTool(PathJail jail, IgnoreRules ignore, Metrics metrics,
                           DependencyTracker tracker) {
        this.jail = jail;
        this.ignore = ignore;
        this.metrics = metrics;
        this.tracker = tracker;
    }

    @Override public String name() { return "search_files"; }

    @Override public String description() {
        return "Search workspace file contents with a regex. Returns only the matching lines "
                + "grouped by file with line numbers, hard-capped to stay small — use this "
                + "instead of reading files to locate code. Repeated identical searches are "
                + "served from cache at no recomputation cost.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode props = Json.obj();
        props.set("pattern", Json.prop("string", "Regular expression (Java syntax)"));
        props.set("glob", Json.prop("string", "Filename glob filter, e.g. **/*.java (optional)"));
        props.set("context_lines", Json.prop("integer", "Lines of context around each match, 0-5 (default 0)"));
        props.set("max_results", Json.prop("integer", "Maximum matching lines (default 50, max 200)"));
        return Json.schemaObject(props, "pattern");
    }

    @Override public CachePolicy cachePolicy() { return CachePolicy.SEARCH_RESULT; }

    @Override
    public ToolResult call(ObjectNode args) throws Exception {
        String patternStr = Json.str(args, "pattern", null);
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("Invalid regex: " + e.getMessage());
        }
        String glob = Json.str(args, "glob", null);
        java.util.function.Predicate<java.nio.file.Path> globMatcher =
                glob == null ? null : io.tokenguard.util.Globs.matcher(glob);
        int context = Math.clamp(Json.intVal(args, "context_lines", 0), 0, 5);
        int maxResults = Math.clamp(Json.intVal(args, "max_results", DEFAULT_MAX_RESULTS),
                1, HARD_MAX_RESULTS);

        Map<String, List<String>> byFile = new LinkedHashMap<>();
        int[] found = {0};
        long[] naiveTokens = {0};
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(jail.root())) {
            s.filter(Files::isRegularFile)
             .filter(p -> !ignore.ignored(jail.root().relativize(p)))
             .filter(p -> globMatcher == null || globMatcher.test(jail.root().relativize(p)))
             .forEach(files::add);
        }
        boolean truncated = false;
        for (Path file : files) {
            if (found[0] >= maxResults) { truncated = true; break; }
            try {
                if (Files.size(file) > TextFiles.MAX_FILE_BYTES || TextFiles.looksBinary(file)) continue;
                byte[] bytes = Files.readAllBytes(file);
                List<String> lines = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                        .lines().toList();
                List<String> hits = null;
                for (int i = 0; i < lines.size() && found[0] < maxResults; i++) {
                    if (!pattern.matcher(lines.get(i)).find()) continue;
                    if (hits == null) {
                        hits = new ArrayList<>();
                        String rel = jail.relativize(file);
                        byFile.put(rel, hits);
                        // §6.1.4: matched files form this result's invalidation set
                        tracker.recordFile(rel, io.tokenguard.util.Hashes.sha256(bytes));
                        naiveTokens[0] += TokenEstimator.estimateBytes(bytes.length);
                    }
                    found[0]++;
                    int from = Math.max(0, i - context), to = Math.min(lines.size() - 1, i + context);
                    for (int j = from; j <= to; j++) {
                        String marker = j == i ? ":" : "-";
                        hits.add("  " + (j + 1) + marker + " " + lines.get(j));
                    }
                }
            } catch (IOException ignoreFile) {
                // unreadable file: skip, never fail the search (§6.7 invariant)
            }
        }

        if (byFile.isEmpty()) {
            String out = "No matches for /" + patternStr + "/"
                    + (glob != null ? " in " + glob : "") + ".";
            metrics.tokens(name(), TokenEstimator.estimate(out), TokenEstimator.estimate(out));
            return ToolResult.text(out);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(found[0]).append(" match(es) in ").append(byFile.size()).append(" file(s):\n");
        for (Map.Entry<String, List<String>> e : byFile.entrySet()) {
            sb.append(e.getKey()).append('\n');
            e.getValue().forEach(l -> sb.append(l).append('\n'));
        }
        if (truncated)
            sb.append("[truncated at ").append(maxResults)
              .append(" matches — narrow the pattern or add a glob]");
        String out = sb.toString();
        long raw = Math.max(naiveTokens[0], TokenEstimator.estimate(out));
        metrics.tokens(name(), raw, TokenEstimator.estimate(out));
        return ToolResult.text(out, raw);
    }
}
