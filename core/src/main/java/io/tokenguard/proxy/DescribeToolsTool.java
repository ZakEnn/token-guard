package io.tokenguard.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.util.Json;

/**
 * P1.2 facade tool 2/3: on-demand schema promotion — the Tool Attention /
 * Speakeasy "lazy schema loading" step. Full JSON schemas enter context only
 * for tools the model actually intends to use.
 */
public final class DescribeToolsTool implements Tool {

    private final DownstreamRegistry registry;

    public DescribeToolsTool(DownstreamRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "describe_tools"; }

    @Override public String description() {
        return "Get the full parameter schemas for downstream tools found via search_tools. "
                + "Pass the server/tool names; then call execute_tool with matching arguments.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode props = Json.obj();
        ObjectNode names = Json.obj();
        names.put("type", "array");
        names.set("items", Json.prop("string", "server/tool name from search_tools"));
        names.put("description", "Tools to describe, e.g. [\"github/create_issue\"]");
        props.set("names", names);
        return Json.schemaObject(props, "names");
    }

    @Override
    public ToolResult call(ObjectNode args) {
        JsonNode names = args.get("names");
        if (names == null || !names.isArray() || names.isEmpty())
            return ToolResult.error("Pass 'names' as a non-empty array of server/tool strings "
                    + "(from search_tools).");
        ArrayNode out = Json.arr();
        StringBuilder missing = new StringBuilder();
        for (JsonNode n : names) {
            String qualified = n.asText();
            int slash = qualified.indexOf('/');
            String server = slash > 0 ? qualified.substring(0, slash) : null;
            String tool = slash > 0 ? qualified.substring(slash + 1) : qualified;
            DownstreamRegistry.QualifiedTool found = null;
            for (DownstreamRegistry.QualifiedTool qt : registry.allTools()) {
                if (qt.tool().name().equals(tool)
                        && (server == null || qt.server().equals(server))) {
                    found = qt;
                    break;
                }
            }
            if (found == null) {
                if (missing.length() > 0) missing.append(", ");
                missing.append(qualified);
                continue;
            }
            ObjectNode entry = Json.obj();
            entry.put("name", found.server() + "/" + found.tool().name());
            entry.put("description", found.tool().description());
            entry.set("inputSchema", found.tool().inputSchema());
            out.add(entry);
        }
        if (out.isEmpty())
            return ToolResult.error("No matching downstream tools: " + missing
                    + ". Use search_tools to find valid names.");
        String text = out.toPrettyString()
                + (missing.length() > 0 ? "\n(not found: " + missing + ")" : "");
        return ToolResult.text(text);
    }
}
