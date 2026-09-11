package io.tokenguard.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Json;
import io.tokenguard.util.Log;

import java.util.Set;

/**
 * MCP protocol logic, transport-agnostic (plan §1.3 MCP Frontend). Handles
 * initialize / ping / tools/list / tools/call; unknown methods get -32601,
 * unknown tools -32602. Tool execution failures are tool results, not
 * protocol errors (§6.7.4).
 */
public final class McpServer {

    public static final String LATEST_PROTOCOL = "2025-06-18";
    private static final Set<String> SUPPORTED_PROTOCOLS =
            Set.of("2024-11-05", "2025-03-26", LATEST_PROTOCOL);

    /** Receives clientInfo (name, version) from the initialize handshake (P0.2). */
    @FunctionalInterface
    public interface ClientInfoListener {
        void onClientInfo(String name, String version);
    }

    private final ToolRouter router;
    private final String serverVersion;
    private volatile boolean initialized;
    private volatile ClientInfoListener clientInfoListener;

    public McpServer(ToolRouter router, String serverVersion) {
        this.router = router;
        this.serverVersion = serverVersion;
    }

    public void setClientInfoListener(ClientInfoListener listener) {
        this.clientInfoListener = listener;
    }

    public boolean isInitialized() { return initialized; }

    /** @return the JSON-RPC response envelope (never null for requests). */
    public ObjectNode handleRequest(JsonNode id, String method, JsonNode params) {
        try {
            return switch (method) {
                case "initialize" -> response(id, initialize(params));
                case "ping" -> response(id, Json.obj());
                case "tools/list" -> response(id, toolsList());
                case "tools/call" -> toolsCall(id, params);
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            Log.error("Request handling failed for method " + method, e);
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    public void handleNotification(String method, JsonNode params) {
        switch (method) {
            case "notifications/initialized" -> initialized = true;
            case "notifications/cancelled" -> Log.debug("Cancellation received (ignored)");
            default -> Log.debug("Unhandled notification: " + method);
        }
    }

    private ObjectNode initialize(JsonNode params) {
        ClientInfoListener listener = clientInfoListener;
        if (listener != null && params != null && params.has("clientInfo")) {
            JsonNode ci = params.get("clientInfo");
            listener.onClientInfo(Json.str(ci, "name", null), Json.str(ci, "version", null));
        }
        String requested = Json.str(params, "protocolVersion", LATEST_PROTOCOL);
        ObjectNode result = Json.obj();
        result.put("protocolVersion",
                SUPPORTED_PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL);
        ObjectNode caps = Json.obj();
        ObjectNode toolsCap = Json.obj();
        toolsCap.put("listChanged", false);
        caps.set("tools", toolsCap);
        result.set("capabilities", caps);
        ObjectNode info = Json.obj();
        info.put("name", "token-guard");
        info.put("version", serverVersion);
        result.set("serverInfo", info);
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = Json.obj();
        ArrayNode tools = Json.arr();
        for (Tool t : router.list()) {
            ObjectNode entry = Json.obj();
            entry.put("name", t.name());
            entry.put("description", t.description());
            entry.set("inputSchema", t.inputSchema());
            tools.add(entry);
        }
        result.set("tools", tools);
        return result;
    }

    private ObjectNode toolsCall(JsonNode id, JsonNode params) {
        String name = Json.str(params, "name", null);
        if (name == null) return error(id, -32602, "Missing tool name");
        if (!router.has(name)) return error(id, -32602, "Unknown tool: " + name);
        JsonNode rawArgs = params.get("arguments");
        ObjectNode args = rawArgs != null && rawArgs.isObject()
                ? (ObjectNode) rawArgs : Json.obj();

        ToolResult r = router.call(name, args);

        ObjectNode result = Json.obj();
        ArrayNode content = Json.arr();
        ObjectNode text = Json.obj();
        text.put("type", "text");
        text.put("text", r.text());
        content.add(text);
        result.set("content", content);
        result.put("isError", r.isError());
        return response(id, result);
    }

    private static ObjectNode response(JsonNode id, ObjectNode result) {
        ObjectNode env = Json.obj();
        env.put("jsonrpc", "2.0");
        env.set("id", id);
        env.set("result", result);
        return env;
    }

    public static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode env = Json.obj();
        env.put("jsonrpc", "2.0");
        env.set("id", id);
        ObjectNode err = Json.obj();
        err.put("code", code);
        err.put("message", message);
        env.set("error", err);
        return env;
    }
}
