package io.tokenguard.proxy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.util.Config;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1.1 DoD: spawn a real (fixture) downstream MCP server over stdio; list and
 * round-trip tools; kill it mid-session -> exactly one restart attempt ->
 * structured error naming the dead server, while TokenGuard-side tools keep
 * answering in the same session.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class DownstreamServerTest {

    private static List<String> fakeCommand(String variant) {
        String sep = java.io.File.separator;
        String javaBin = System.getProperty("java.home") + sep + "bin" + sep + "java";
        return List.of(javaBin, "-cp", System.getProperty("java.class.path"),
                FakeMcpServerMain.class.getName(), variant);
    }

    static DownstreamRegistry registry;

    @BeforeAll
    void setUp() {
        registry = new DownstreamRegistry();
        registry.add(new DownstreamServer("alpha", fakeCommand("alpha"), Map.of(), 5000));
        registry.add(new DownstreamServer("mortal", fakeCommand("mortal"), Map.of(), 5000));
        registry.startAll();
    }

    @AfterAll
    void tearDown() {
        registry.close();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void listsDownstreamToolsAfterHandshake() {
        assertEquals(DownstreamServer.State.READY, registry.get("alpha").orElseThrow().state());
        List<DownstreamRegistry.QualifiedTool> tools = registry.allTools();
        assertTrue(tools.stream().anyMatch(q ->
                        q.server().equals("alpha") && q.tool().name().equals("echo")),
                "alpha/echo must be discovered, got " + tools);
        assertTrue(tools.stream().anyMatch(q ->
                q.server().equals("mortal") && q.tool().name().equals("die")));
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void roundTripsAToolCall() throws Exception {
        ObjectNode args = Json.obj();
        args.put("msg", "bonjour");
        DownstreamServer.CallResult r = registry.call("alpha", "echo", args);
        assertFalse(r.isError());
        assertEquals("echo:bonjour", r.text());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void unknownServerIsAStructuredError() {
        DownstreamServer.DownstreamException e = assertThrows(
                DownstreamServer.DownstreamException.class,
                () -> registry.call("ghost", "echo", Json.obj()));
        assertTrue(e.getMessage().contains("ghost"));
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void crashRestartsOnceThenUnavailable_whileOwnToolsKeepAnswering() throws Exception {
        // TokenGuard-side tool registered alongside, same session
        ToolRouter router = new ToolRouter();
        router.register(new Tool() {
            @Override public String name() { return "guard_status"; }
            @Override public String description() { return "t"; }
            @Override public ObjectNode inputSchema() { return Json.schemaObject(Json.obj()); }
            @Override public ToolResult call(ObjectNode args) { return ToolResult.text("ok"); }
        });

        DownstreamServer mortal = registry.get("mortal").orElseThrow();
        assertEquals(DownstreamServer.State.READY, mortal.state());

        // 'die' kills the process before replying; the §6.7.5 recovery restarts
        // once and retries — the retried 'die' kills the restarted process too,
        // so the server must land in UNAVAILABLE after exactly its one restart.
        DownstreamServer.DownstreamException e = assertThrows(
                DownstreamServer.DownstreamException.class,
                () -> mortal.call("die", Json.obj()));
        assertTrue(e.getMessage().contains("mortal"), e.getMessage());
        assertTrue(e.getMessage().contains("unavailable"), e.getMessage());
        assertEquals(DownstreamServer.State.UNAVAILABLE, mortal.state());

        // Subsequent calls: structured error naming the dead server, no hang
        DownstreamServer.DownstreamException e2 = assertThrows(
                DownstreamServer.DownstreamException.class,
                () -> mortal.call("echo", Json.obj()));
        assertTrue(e2.getMessage().contains("mortal"));

        // Own tools and the OTHER downstream keep answering in the same session
        assertEquals("ok", router.call("guard_status", Json.obj()).text());
        ObjectNode args = Json.obj();
        args.put("msg", "still-alive");
        assertEquals("echo:still-alive", registry.call("alpha", "echo", args).text());
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void fromConfigParsesServerBlocksEnvAndArgs(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("config.toml");
        Files.writeString(toml, """
                [servers.github]
                command = "npx"
                args = "-y @modelcontextprotocol/server-github"
                env.GITHUB_TOKEN = "tok_x"

                [servers.files]
                command = "uvx"
                args = "mcp-server-filesystem"
                """);
        DownstreamRegistry reg = DownstreamRegistry.fromConfig(Config.load(toml), 1000);
        assertEquals(2, reg.servers().size());
        assertTrue(reg.get("github").isPresent());
        assertTrue(reg.get("files").isPresent());
        assertEquals(DownstreamServer.State.NEW, reg.get("github").orElseThrow().state());
        // not started — parsing only; commands would fail on machines without npx
        reg.close();
    }
}
