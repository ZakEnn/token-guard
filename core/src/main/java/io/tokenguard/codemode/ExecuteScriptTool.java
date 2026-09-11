package io.tokenguard.codemode;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.util.Json;
import io.tokenguard.util.Log;
import io.tokenguard.util.TokenEstimator;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * execute_script (Engine 3, plan §5.1): the LLM writes a small JS program that
 * runs against the §5.2 host API and returns only the exact slice of data
 * needed. Scripts are identity for caching (never fuzzy-matched, ADR-0003);
 * deps = the files the script actually read via the host API.
 *
 * Circuit breaker (§6.7.4): >=3 sandbox ENGINE faults (not script bugs) in
 * 5 minutes disables code-mode for the session; standard tools keep working.
 */
public final class ExecuteScriptTool implements Tool {

    private static final int BREAKER_FAULTS = 3;
    private static final long BREAKER_WINDOW_MS = 5 * 60 * 1000;

    private final ScriptSandbox sandbox;
    private final io.tokenguard.astindex.WorkspaceIndexer indexer;
    private final Metrics metrics;
    private final Deque<Long> faults = new ArrayDeque<>();
    private volatile boolean disabled;

    public ExecuteScriptTool(ScriptSandbox sandbox,
                             io.tokenguard.astindex.WorkspaceIndexer indexer, Metrics metrics) {
        this.sandbox = sandbox;
        this.indexer = indexer;
        this.metrics = metrics;
    }

    public boolean isDisabled() { return disabled; }

    @Override public String name() { return "execute_script"; }

    @Override public String description() {
        return "Run a small JavaScript program locally against the workspace and return only its "
                + "result — far cheaper than chains of individual tool calls. The value of the "
                + "last expression is returned (objects are JSON). Available APIs (read-only): "
                + "fs.read(path, {startLine?, endLine?}), fs.glob(pattern), "
                + "fs.grep(pattern, {glob?, maxResults?}), fs.stat(path), index.symbol(name), "
                + "index.callers(name), index.callees(name), index.outline(path), "
                + "index.search(query). No network, no writes (use apply_edit), 5 s limit.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode props = Json.obj();
        props.set("script", Json.prop("string", "JavaScript source; last expression is the result"));
        props.set("timeout_ms", Json.prop("integer", "Time limit override — may only LOWER the default 5000"));
        return Json.schemaObject(props, "script");
    }

    @Override public CachePolicy cachePolicy() { return CachePolicy.SCRIPT_RESULT; }

    @Override
    public ToolResult call(ObjectNode args) {
        if (disabled)
            return ToolResult.error("Code-mode is disabled for this session after repeated "
                    + "sandbox faults. Use the standard tools (read_file, search_files, "
                    + "query_codebase_structure).");
        String script = Json.str(args, "script", null);
        if (script == null || script.isBlank()) return ToolResult.error("'script' is required");

        // Pre-warm the shared index OUTSIDE the script budget: the watchdog bounds
        // script work, not the one-time cold index build (§6.4 / §6.7.4)
        indexer.refreshIfStale();
        ScriptSandbox.Outcome outcome = sandbox.run(script, Json.intVal(args, "timeout_ms", 0));
        if (outcome.ok()) {
            String out = outcome.value();
            metrics.tokens(name(), TokenEstimator.estimate(out), TokenEstimator.estimate(out));
            return ToolResult.text(out);
        }
        if (outcome.engineFault()) tripBreaker();
        return ToolResult.error(outcome.error());
    }

    private synchronized void tripBreaker() {
        long now = System.currentTimeMillis();
        faults.addLast(now);
        while (!faults.isEmpty() && faults.peekFirst() < now - BREAKER_WINDOW_MS)
            faults.removeFirst();
        if (faults.size() >= BREAKER_FAULTS) {
            disabled = true;
            Log.warn("code-mode circuit breaker tripped: " + faults.size()
                    + " engine faults in 5 min");
        }
    }
}
