package io.tokenguard.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.util.Json;

import java.util.Set;

/**
 * P1.2 facade tool 3/3: invoke a downstream tool. Routed through the normal
 * layer chain, so downstream results are cached/metered/traced with zero new
 * plumbing.
 *
 * Cache policy decision (recorded per the autonomy mandate): downstream tools
 * may be MUTATING (create_issue, send_message) — caching those would silently
 * suppress re-execution, which is worse than any cache miss. We therefore
 * cache only tools whose name starts with a read-shaped verb (allow-list);
 * everything else is CachePolicy.NONE. TTL class for cached reads:
 * SEARCH_RESULT (24 h), invalidated by TTL alone (plan §6.1.4 — no file deps
 * for pure downstream results).
 */
public final class ExecuteToolTool implements Tool {

    private static final Set<String> READ_VERBS = Set.of(
            "get", "list", "read", "search", "query", "describe", "fetch",
            "find", "show", "status", "lookup", "count", "browse", "view");

    private final DownstreamRegistry registry;

    public ExecuteToolTool(DownstreamRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "execute_tool"; }

    @Override public String description() {
        return "Invoke a downstream MCP tool by its server/tool name (from search_tools). "
                + "Pass 'arguments' matching the schema from describe_tools. Read-style "
                + "results are cached; repeated identical calls cost nothing downstream.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode props = Json.obj();
        props.set("name", Json.prop("string", "server/tool, e.g. \"github/list_issues\""));
        ObjectNode arguments = Json.obj();
        arguments.put("type", "object");
        arguments.put("description", "Arguments for the downstream tool");
        props.set("arguments", arguments);
        return Json.schemaObject(props, "name");
    }

    @Override
    public CachePolicy cachePolicy(ObjectNode args) {
        String tool = toolPart(Json.str(args, "name", ""));
        int underscore = tool.indexOf('_');
        String verb = (underscore > 0 ? tool.substring(0, underscore) : tool).toLowerCase();
        return READ_VERBS.contains(verb) ? CachePolicy.SEARCH_RESULT : CachePolicy.NONE;
    }

    private static String toolPart(String qualified) {
        int slash = qualified.indexOf('/');
        return slash >= 0 ? qualified.substring(slash + 1) : qualified;
    }

    @Override
    public ToolResult call(ObjectNode args) {
        String qualified = Json.str(args, "name", "");
        int slash = qualified.indexOf('/');
        if (slash <= 0)
            return ToolResult.error("Pass 'name' as server/tool (e.g. \"github/list_issues\") "
                    + "— find names with search_tools.");
        String server = qualified.substring(0, slash);
        String tool = qualified.substring(slash + 1);
        JsonNode a = args.get("arguments");
        ObjectNode toolArgs = a != null && a.isObject() ? (ObjectNode) a : Json.obj();
        try {
            DownstreamServer.CallResult r = registry.call(server, tool, toolArgs);
            if (r.isError()) return ToolResult.error(r.text());
            return ToolResult.text(r.text());
        } catch (DownstreamServer.DownstreamException e) {
            // §6.7.5: structured tool error naming the dead server; never a protocol error
            return ToolResult.error(e.getMessage());
        }
    }
}
