package io.tokenguard.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StdioTransportTest {

    private List<JsonNode> roundTrip(String... lines) throws Exception {
        ToolRouter router = new ToolRouter();
        router.register(new EchoTool());
        McpServer server = new McpServer(router, "test");
        String input = String.join("\n", lines) + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        new StdioTransport(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                out, server, pool).run();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        List<JsonNode> responses = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) responses.add(Json.MAPPER.readTree(line));
        }
        return responses;
    }

    @Test
    void fullHandshakeAndToolCall() throws Exception {
        List<JsonNode> responses = roundTrip(
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}"
        );
        assertEquals(3, responses.size(), "notification must not produce a response");
        for (JsonNode r : responses) {
            assertEquals("2.0", r.get("jsonrpc").asText());
            assertNull(r.get("error"));
        }
        JsonNode byId2 = responses.stream().filter(r -> r.get("id").asInt() == 2).findFirst().orElseThrow();
        assertEquals("echo: hi", byId2.get("result").get("content").get(0).get("text").asText());
    }

    @Test
    void malformedJsonYieldsParseError() throws Exception {
        List<JsonNode> responses = roundTrip("{this is not json");
        assertEquals(1, responses.size());
        assertEquals(-32700, responses.get(0).get("error").get("code").asInt());
    }

    @Test
    void batchArrayRejectedAsInvalidRequest() throws Exception {
        List<JsonNode> responses = roundTrip("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");
        assertEquals(1, responses.size());
        assertEquals(-32600, responses.get(0).get("error").get("code").asInt());
    }

    @Test
    void blankLinesIgnored() throws Exception {
        List<JsonNode> responses = roundTrip("", "  ", "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}");
        assertEquals(1, responses.size());
        assertEquals(9, responses.get(0).get("id").asInt());
    }
}
