package io.tokenguard.trace;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.mcp.ToolRouter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Outermost router layer (enhancement plan P0.2): records every tool call —
 * hits, misses, errors, passthrough — into interaction_trace. Tracing is
 * unconditional (PLATFORM_AGNOSTIC_PLAN §1.5); the optimization layer is
 * best-effort, the tracing layer is not optional.
 *
 * Client identity arrives from the MCP initialize handshake (clientInfo) via
 * setClient; rows written before initialize carry nulls, which is honest.
 */
public final class TraceLayer implements ToolRouter.Layer {

    /** Static tool -> engine attribution (P0.2 decision: outermost layer cannot
     * observe engine internals, and tool names map 1:1 to owning engines). */
    private static final Map<String, String> ENGINES = Map.ofEntries(
            Map.entry("read_file", "diff_distill"),
            Map.entry("apply_edit", "diff_distill"),
            Map.entry("list_directory", "diff_distill"),
            Map.entry("search_files", "diff_distill"),
            Map.entry("query_codebase_structure", "ast_index"),
            Map.entry("execute_script", "code_mode"),
            Map.entry("search_api", "code_mode"),
            Map.entry("guard_status", "telemetry"),
            Map.entry("restore_context", "session"),
            Map.entry("search_tools", "proxy"),
            Map.entry("describe_tools", "proxy"),
            Map.entry("execute_tool", "proxy"));

    private final TraceStore store;
    private final BooleanSupplier optimizationsOn;
    private final String sessionId = UUID.randomUUID().toString();
    private volatile String clientName;
    private volatile String clientVersion;
    /** P3.3: optional session journal fed from the same interception point.
     * Local-only (receives paths; the trace/metrics stores never do). */
    private volatile io.tokenguard.distill.SessionJournal journal;

    public TraceLayer(TraceStore store, BooleanSupplier optimizationsOn) {
        this.store = store;
        this.optimizationsOn = optimizationsOn;
    }

    public void setJournal(io.tokenguard.distill.SessionJournal journal) {
        this.journal = journal;
    }

    private void journal(Tool tool, ObjectNode args, boolean wasError) {
        io.tokenguard.distill.SessionJournal j = journal;
        if (j == null || "restore_context".equals(tool.name())) return;
        String path = args != null && args.hasNonNull("path") ? args.get("path").asText() : null;
        if (wasError) j.record("error", tool.name() + (path != null ? " " + path : "") + " failed");
        else if ("read_file".equals(tool.name()) && path != null) j.record("read", path);
        else if ("apply_edit".equals(tool.name()) && path != null) j.record("edit", path);
        else j.record("call", tool.name());
    }

    /** Wired to McpServer's initialize handshake (clientInfo). */
    public void setClient(String name, String version) {
        this.clientName = name;
        this.clientVersion = version;
    }

    public String sessionId() { return sessionId; }

    private static String engineFor(Tool tool) {
        String byName = ENGINES.get(tool.name());
        if (byName != null) return byName;
        return tool.name().startsWith("ds__") ? "proxy" : "other";
    }

    /** Pragmatic optimization labeling from result shape — the outermost layer
     * has no channel to engine internals; read_file's two optimized shapes are
     * stable, documented output contracts (evals.md tests 2-3). */
    private static String optimizationOf(Tool tool, ToolResult r) {
        if (r == null || r.isError()) return null;
        String text = r.text();
        if ("read_file".equals(tool.name())) {
            if (text.endsWith("unchanged since you last read it.")) return "unchanged_reread";
            if (text.startsWith("--- a/")) return "diff_reread";
        }
        return null;
    }

    @Override
    public ToolResult invoke(Tool tool, ObjectNode args, ToolRouter.Invoker next) throws Exception {
        long ts = Instant.now().getEpochSecond();
        boolean passthrough = !optimizationsOn.getAsBoolean();
        try {
            ToolResult r = next.invoke(tool, args);
            store.record(new TraceStore.Row(sessionId, ts, clientName, clientVersion,
                    tool.name(), engineFor(tool), r.rawTokens(),
                    io.tokenguard.util.TokenEstimator.estimate(r.text()),
                    r.isError(), passthrough, optimizationOf(tool, r)));
            journal(tool, args, r.isError());
            return r;
        } catch (Exception e) {
            store.record(new TraceStore.Row(sessionId, ts, clientName, clientVersion,
                    tool.name(), engineFor(tool), 0, 0, true, passthrough, null));
            journal(tool, args, true);
            throw e;
        }
    }
}
