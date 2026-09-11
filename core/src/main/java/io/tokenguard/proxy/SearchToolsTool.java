package io.tokenguard.proxy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.cache.Embedder;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.util.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * P1.2 facade tool 1/3 (PLATFORM_AGNOSTIC_PLAN §1.4 amendment): semantic +
 * keyword search over downstream tool summaries. The context footprint of the
 * facade is FIXED — no downstream schema ever appears here, only ranked
 * one-line summaries; full schemas are promoted on demand by describe_tools.
 */
public final class SearchToolsTool implements Tool {

    private static final int DEFAULT_MAX = 8;
    private static final int SUMMARY_CHARS = 100;

    private final DownstreamRegistry registry;
    private final Embedder embedder;

    public SearchToolsTool(DownstreamRegistry registry, Embedder embedder) {
        this.registry = registry;
        this.embedder = embedder;
    }

    @Override public String name() { return "search_tools"; }

    @Override public String description() {
        return "Find tools from the connected downstream MCP servers by describing what you "
                + "need (e.g. 'create a github issue'). Returns ranked one-line summaries as "
                + "server/tool names. Then call describe_tools for full schemas and "
                + "execute_tool to invoke — this keeps unused tool schemas out of context.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode props = Json.obj();
        props.set("query", Json.prop("string", "What you want a tool to do (free text)"));
        props.set("max_results", Json.prop("integer", "Maximum results (default 8)"));
        return Json.schemaObject(props, "query");
    }

    @Override
    public ToolResult call(ObjectNode args) {
        String query = Json.str(args, "query", "").trim();
        if (query.isEmpty()) return ToolResult.error("Missing 'query'. Describe what you need.");
        int max = Json.intVal(args, "max_results", DEFAULT_MAX);

        List<DownstreamRegistry.QualifiedTool> tools = registry.allTools();
        if (tools.isEmpty())
            return ToolResult.text("No downstream tools available"
                    + (registry.isEmpty() ? " — no downstream servers are configured."
                    : " yet — downstream servers may still be starting; retry shortly."));

        record Scored(DownstreamRegistry.QualifiedTool qt, double score) {}
        float[] q = embedder.embed(query);
        String lowered = query.toLowerCase();
        List<Scored> scored = new ArrayList<>();
        for (DownstreamRegistry.QualifiedTool qt : tools) {
            String text = qt.tool().name() + " " + qt.tool().description();
            double score = Embedder.cosine(q, embedder.embed(text));
            // exact-substring boost: a named tool should always be findable by name
            if (text.toLowerCase().contains(lowered)) score += 1.0;
            scored.add(new Scored(qt, score));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));

        StringBuilder sb = new StringBuilder();
        int n = Math.min(max, scored.size());
        for (int i = 0; i < n; i++) {
            DownstreamRegistry.QualifiedTool qt = scored.get(i).qt();
            String desc = qt.tool().description();
            if (desc.length() > SUMMARY_CHARS) desc = desc.substring(0, SUMMARY_CHARS) + "…";
            sb.append(qt.server()).append('/').append(qt.tool().name())
              .append(" — ").append(desc).append('\n');
        }
        sb.append("(").append(tools.size()).append(" tools total; use describe_tools "
                + "with the server/tool names above for full parameter schemas)");
        long raw = io.tokenguard.util.TokenEstimator.estimate(renderAllSchemas(tools));
        return new ToolResult(sb.toString(), false, raw);
    }

    /** What the naive alternative (all schemas in context) would have cost. */
    private static String renderAllSchemas(List<DownstreamRegistry.QualifiedTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (DownstreamRegistry.QualifiedTool qt : tools)
            sb.append(qt.tool().name()).append(qt.tool().description())
              .append(qt.tool().inputSchema().toString());
        return sb.toString();
    }
}
