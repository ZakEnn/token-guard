package io.tokenguard.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {

    private McpServer server;

    @BeforeEach
    void setUp() {
        ToolRouter router = new ToolRouter();
        router.register(new EchoTool());
        server = new McpServer(router, "test");
    }

    private ObjectNode request(String method, ObjectNode params) {
        return server.handleRequest(IntNode.valueOf(1), method, params);
    }

    @Test
    void initializeEchoesSupportedProtocolVersion() {
        ObjectNode params = Json.obj();
        params.put("protocolVersion", "2025-03-26");
        JsonNode resp = request("initialize", params);
        assertEquals("2025-03-26", resp.get("result").get("protocolVersion").asText());
        assertEquals("token-guard", resp.get("result").get("serverInfo").get("name").asText());
        assertTrue(resp.get("result").get("capabilities").has("tools"));
    }

    @Test
    void initializeFallsBackToLatestOnUnknownVersion() {
        ObjectNode params = Json.obj();
        params.put("protocolVersion", "1999-01-01");
        JsonNode resp = request("initialize", params);
        assertEquals(McpServer.LATEST_PROTOCOL, resp.get("result").get("protocolVersion").asText());
    }

    @Test
    void toolsListContainsRegisteredTool() {
        JsonNode resp = request("tools/list", null);
        JsonNode tools = resp.get("result").get("tools");
        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).get("name").asText());
        assertEquals("object", tools.get(0).get("inputSchema").get("type").asText());
    }

    @Test
    void toolsCallReturnsTextContent() {
        ObjectNode params = Json.obj();
        params.put("name", "echo");
        ObjectNode args = Json.obj();
        args.put("text", "hello");
        params.set("arguments", args);
        JsonNode resp = request("tools/call", params);
        JsonNode result = resp.get("result");
        assertFalse(result.get("isError").asBoolean());
        assertEquals("echo: hello", result.get("content").get(0).get("text").asText());
    }

    @Test
    void unknownMethodReturnsMethodNotFound() {
        JsonNode resp = request("bogus/method", null);
        assertEquals(-32601, resp.get("error").get("code").asInt());
    }

    @Test
    void unknownToolReturnsInvalidParams() {
        ObjectNode params = Json.obj();
        params.put("name", "no-such-tool");
        JsonNode resp = request("tools/call", params);
        assertEquals(-32602, resp.get("error").get("code").asInt());
    }

    @Test
    void toolExceptionBecomesToolResultErrorNotProtocolError() {
        ToolRouter router = new ToolRouter();
        router.register(new Tool() {
            @Override public String name() { return "boom"; }
            @Override public String description() { return "always fails"; }
            @Override public ObjectNode inputSchema() { return Json.schemaObject(Json.obj()); }
            @Override public ToolResult call(ObjectNode args) { throw new IllegalStateException("kaput"); }
        });
        McpServer s = new McpServer(router, "test");
        ObjectNode params = Json.obj();
        params.put("name", "boom");
        JsonNode resp = s.handleRequest(IntNode.valueOf(7), "tools/call", params);
        assertNull(resp.get("error"), "engine failure must not be a protocol error (§6.7.4)");
        assertTrue(resp.get("result").get("isError").asBoolean());
        assertTrue(resp.get("result").get("content").get(0).get("text").asText().contains("kaput"));
    }

    @Test
    void initializedNotificationSetsFlag() {
        assertFalse(server.isInitialized());
        server.handleNotification("notifications/initialized", null);
        assertTrue(server.isInitialized());
    }
}
