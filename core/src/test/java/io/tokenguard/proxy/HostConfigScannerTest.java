package io.tokenguard.proxy;

import io.tokenguard.util.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1.3 DoD: on a test HOME with 3 host-config fixtures (one per format
 * variant, including VS Code's "servers" key), --dry-run lists all 3 with
 * correct commands; a real import produces TOML the registry parses; host
 * files are untouched unless rewriteHostFile is explicitly invoked; re-import
 * is idempotent.
 */
class HostConfigScannerTest {

    @TempDir Path home;
    @TempDir Path appData;
    @TempDir Path cwd;
    @TempDir Path dataDir;

    private List<HostConfigScanner.FoundServer> writeFixturesAndScan() throws Exception {
        // Variant 1: Claude Desktop (mcpServers, env block) — under APPDATA
        Path desktop = appData.resolve("Claude");
        Files.createDirectories(desktop);
        Files.writeString(desktop.resolve("claude_desktop_config.json"), """
                { "mcpServers": {
                    "github": { "command": "npx",
                                "args": ["-y", "@modelcontextprotocol/server-github"],
                                "env": {"GITHUB_TOKEN": "tok_1"} },
                    "token-guard": { "command": "token-guard", "args": ["--workspace", "."] }
                } }
                """);
        // Variant 2: Cursor user-level (mcpServers, no env)
        Path cursor = home.resolve(".cursor");
        Files.createDirectories(cursor);
        Files.writeString(cursor.resolve("mcp.json"), """
                { "mcpServers": { "sqlite": { "command": "uvx",
                                              "args": ["mcp-server-sqlite"] } } }
                """);
        // Variant 3: VS Code workspace — the "servers" key variant
        Path vscode = cwd.resolve(".vscode");
        Files.createDirectories(vscode);
        Files.writeString(vscode.resolve("mcp.json"), """
                { "servers": { "fetch": { "type": "stdio", "command": "uvx",
                                          "args": ["mcp-server-fetch"] } } }
                """);
        return HostConfigScanner.scan(HostConfigScanner.candidates(home, appData, cwd));
    }

    @Test
    void scanFindsAllThreeFormatsAndSkipsTokenGuardItself() throws Exception {
        List<HostConfigScanner.FoundServer> found = writeFixturesAndScan();
        assertEquals(3, found.size(), "3 fixtures, token-guard entry excluded: " + found);
        assertTrue(found.stream().anyMatch(f -> f.name().equals("github")
                && f.command().equals("npx") && f.env().get("GITHUB_TOKEN").equals("tok_1")));
        assertTrue(found.stream().anyMatch(f -> f.name().equals("sqlite")
                && f.command().equals("uvx")));
        assertTrue(found.stream().anyMatch(f -> f.name().equals("fetch")
                && f.host().startsWith("vscode")), "VS Code 'servers' variant must parse");
    }

    @Test
    void importProducesParseableTomlAndIsIdempotent() throws Exception {
        List<HostConfigScanner.FoundServer> found = writeFixturesAndScan();
        Path toml = dataDir.resolve("config.toml");

        List<String> first = HostConfigScanner.importInto(toml, found);
        assertEquals(3, first.size());
        // the produced TOML boots the registry
        DownstreamRegistry reg = DownstreamRegistry.fromConfig(Config.load(toml), 1000);
        assertEquals(3, reg.servers().size());
        assertTrue(reg.get("github").isPresent());
        reg.close();
        Config cfg = Config.load(toml);
        assertEquals("tok_1", cfg.getString("servers.github.env.GITHUB_TOKEN", ""));
        assertEquals("-y @modelcontextprotocol/server-github",
                cfg.getString("servers.github.args", ""));

        // idempotence: nothing new on re-import, file content unchanged
        String before = Files.readString(toml);
        assertTrue(HostConfigScanner.importInto(toml, found).isEmpty());
        assertEquals(before, Files.readString(toml));
    }

    @Test
    void hostFilesAreNeverTouchedByScanOrImport() throws Exception {
        List<HostConfigScanner.FoundServer> found = writeFixturesAndScan();
        Path hostFile = home.resolve(".cursor/mcp.json");
        String before = Files.readString(hostFile);
        HostConfigScanner.importInto(dataDir.resolve("config.toml"), found);
        assertEquals(before, Files.readString(hostFile), "import must not modify host configs");
        assertFalse(Files.exists(home.resolve(".cursor/mcp.json.tg-backup")));
    }

    @Test
    void explicitRewriteBacksUpAndReplacesWithTokenGuardOnly() throws Exception {
        writeFixturesAndScan();
        HostConfigScanner.Location loc = new HostConfigScanner.Location(
                "cursor (user)", home.resolve(".cursor/mcp.json"), "mcpServers");
        String original = Files.readString(loc.file());

        HostConfigScanner.rewriteHostFile(loc, List.of("token-guard", "--workspace", "."));

        Path backup = home.resolve(".cursor/mcp.json.tg-backup");
        assertTrue(Files.exists(backup));
        assertEquals(original, Files.readString(backup), "backup must be byte-identical");
        String rewritten = Files.readString(loc.file());
        assertTrue(rewritten.contains("token-guard"));
        assertFalse(rewritten.contains("mcp-server-sqlite"), "old servers replaced");
    }
}
