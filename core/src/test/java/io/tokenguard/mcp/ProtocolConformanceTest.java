package io.tokenguard.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.7 (enhancement plan): protocol conformance beyond McpServerTest /
 * StdioTransportTest — the cases ADR-0002 committed to keep covered, plus an
 * end-to-end stdio session per supported protocol version.
 */
class ProtocolConformanceTest {

    private McpServer newServer() {
        ToolRouter router = new ToolRouter();
        router.register(new EchoTool());
        return new McpServer(router, "test");
    }

    @Test
    void pingAnswersEmptyResult() {
        JsonNode resp = newServer().handleRequest(IntNode.valueOf(1), "ping", null);
        assertNull(resp.get("error"));
        assertTrue(resp.get("result").isObject());
        assertTrue(resp.get("result").isEmpty());
    }

    @Test
    void missingToolNameIsInvalidParams() {
        JsonNode resp = newServer().handleRequest(IntNode.valueOf(1), "tools/call", Json.obj());
        assertEquals(-32602, resp.get("error").get("code").asInt());
    }

    @Test
    void nonObjectArgumentsAreToleratedAsEmpty() {
        ObjectNode params = Json.obj();
        params.put("name", "echo");
        params.put("arguments", "not-an-object");
        JsonNode resp = newServer().handleRequest(IntNode.valueOf(1), "tools/call", params);
        assertNull(resp.get("error"), "malformed arguments degrade to {}, not a protocol error");
    }

    @Test
    void initializeWithNullParamsUsesLatestProtocol() {
        JsonNode resp = newServer().handleRequest(IntNode.valueOf(0), "initialize", null);
        assertEquals(McpServer.LATEST_PROTOCOL,
                resp.get("result").get("protocolVersion").asText());
    }

    @Test
    void utf8BomOnFirstLineIsTolerated() throws Exception {
        // Windows shells prepend a BOM when piping; the first frame must still parse.
        String session = "﻿{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StdioTransport(
                new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)),
                out, newServer(), Executors.newSingleThreadExecutor()).run();
        JsonNode resp = Json.MAPPER.readTree(out.toString(StandardCharsets.UTF_8).trim());
        assertNull(resp.get("error"), "BOM-prefixed initialize must not be a parse error");
        assertEquals("2025-06-18", resp.get("result").get("protocolVersion").asText());
    }

    @Test
    void unknownNotificationIsIgnoredNotFatal() {
        McpServer server = newServer();
        assertDoesNotThrow(() ->
                server.handleNotification("notifications/whatever_new_thing", Json.obj()));
    }

    @Test
    void endToEndStdioSessionPerSupportedVersion() throws Exception {
        for (String version : new String[]{"2024-11-05", "2025-03-26", "2025-06-18"}) {
            String session = String.join("\n",
                    "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\","
                            + "\"params\":{\"protocolVersion\":\"" + version + "\","
                            + "\"clientInfo\":{\"name\":\"conformance\",\"version\":\"1\"}}}",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"v\"}}}",
                    "not json at all",
                    "[{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}]",
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}") + "\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new StdioTransport(
                    new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)),
                    out, newServer(), Executors.newSingleThreadExecutor()).run();
            String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");
            assertEquals(5, lines.length, version + ": init, call, parse-error, batch-reject, ping");
            // tools/call answers via the worker pool while protocol methods answer
            // inline — output order is not guaranteed; index responses by id.
            JsonNode init = null, call = null, ping = null;
            int parseErrors = 0, invalidRequests = 0;
            for (String line : lines) {
                JsonNode msg = Json.MAPPER.readTree(line);
                JsonNode id = msg.get("id");
                if (id == null || id.isNull()) {
                    int code = msg.get("error").get("code").asInt();
                    if (code == -32700) parseErrors++;
                    if (code == -32600) invalidRequests++;
                } else switch (id.asInt()) {
                    case 0 -> init = msg;
                    case 1 -> call = msg;
                    case 2 -> ping = msg;
                    default -> fail("unexpected response id " + id);
                }
            }
            assertNotNull(init);
            assertEquals(version, init.get("result").get("protocolVersion").asText());
            assertNotNull(call);
            assertEquals("echo: v", call.get("result").get("content").get(0).get("text").asText());
            assertEquals(1, parseErrors, "non-JSON line -> -32700");
            assertEquals(1, invalidRequests, "batches rejected -> -32600");
            assertNotNull(ping);
            assertNull(ping.get("error"));
        }
    }
}
