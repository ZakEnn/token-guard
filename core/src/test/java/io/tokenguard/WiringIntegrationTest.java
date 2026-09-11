package io.tokenguard;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.McpServer;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.store.Database;
import io.tokenguard.telemetry.GuardStatusTool;
import io.tokenguard.trace.TraceLayer;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Production-wiring regression: the exact Main flow (wire + listener) must
 * attribute the client in guard_status's Session detail. */
class WiringIntegrationTest {

    @TempDir Path workspace;
    @TempDir Path dataDir;

    @Test
    void sessionDetailCarriesClientAfterRealWire() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        try (Database db = Database.open(dataDir.resolve("guard.db"))) {
            ToolRouter router = new ToolRouter();
            GuardStatusTool status = new GuardStatusTool("test");
            status.addSection("Storage", () -> "database: ok");
            router.register(status);
            TraceLayer trace = GuardWiring.wire(router, status, db, workspace, dataDir,
                    "standard", null, true);
            McpServer server = new McpServer(router, "test");
            server.setClientInfoListener(trace::setClient);

            ObjectNode init = Json.obj();
            init.put("protocolVersion", "2025-06-18");
            ObjectNode ci = Json.obj();
            ci.put("name", "wire-test-client");
            ci.put("version", "9");
            init.set("clientInfo", ci);
            assertFalse(server.handleRequest(Json.obj().numberNode(0), "initialize", init)
                    .has("error"));

            ObjectNode readArgs = Json.obj();
            ObjectNode params = Json.obj();
            params.put("name", "read_file");
            readArgs.put("path", "pom.xml");
            params.set("arguments", readArgs);
            server.handleRequest(Json.obj().numberNode(1), "tools/call", params);

            ObjectNode statusParams = Json.obj();
            statusParams.put("name", "guard_status");
            statusParams.set("arguments", Json.obj());
            String text = server.handleRequest(Json.obj().numberNode(2), "tools/call", statusParams)
                    .get("result").get("content").get(0).get("text").asText();
            assertTrue(text.contains("client: wire-test-client 9"),
                    "Session detail must attribute the client:\n" + text);
        }
    }
}
