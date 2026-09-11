package io.tokenguard.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Config;
import io.tokenguard.util.Json;
import io.tokenguard.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1.3 (enhancement plan; grounded in the elusznik auto-discovery precedent,
 * research_proposition.md §2.3): find MCP servers in the user's existing host
 * configs and import them as TokenGuard [servers.*] blocks, so TokenGuard can
 * become the only server in the IDE's mcp.json.
 *
 * Read-only by default; host files are rewritten ONLY via rewriteHostFile
 * (explicit --write-host-config), and always with a .tg-backup first.
 */
public final class HostConfigScanner {

    /** One host-config location. VS Code uses "servers"; everyone else "mcpServers". */
    public record Location(String host, Path file, String rootKey) {}

    public record FoundServer(String host, Path file, String name, String command,
                              List<String> args, Map<String, String> env) {}

    private HostConfigScanner() {}

    /** Candidate locations per PLATFORM_AGNOSTIC_PLAN §2. Injectable roots for tests. */
    public static List<Location> candidates(Path home, Path appData, Path cwd) {
        List<Location> out = new ArrayList<>();
        out.add(new Location("claude-code (project)", cwd.resolve(".mcp.json"), "mcpServers"));
        out.add(new Location("claude-code (user)", home.resolve(".claude.json"), "mcpServers"));
        out.add(new Location("cursor (project)", cwd.resolve(".cursor/mcp.json"), "mcpServers"));
        out.add(new Location("cursor (user)", home.resolve(".cursor/mcp.json"), "mcpServers"));
        out.add(new Location("vscode (workspace)", cwd.resolve(".vscode/mcp.json"), "servers"));
        out.add(new Location("windsurf", home.resolve(".codeium/windsurf/mcp_config.json"),
                "mcpServers"));
        if (appData != null) {
            out.add(new Location("claude-desktop",
                    appData.resolve("Claude/claude_desktop_config.json"), "mcpServers"));
        }
        out.add(new Location("claude-desktop (mac)",
                home.resolve("Library/Application Support/Claude/claude_desktop_config.json"),
                "mcpServers"));
        // Cline stores config in VS Code extension globalStorage (version-dependent
        // path) — managed via its UI; deliberately not scanned (documented).
        return out;
    }

    public static List<FoundServer> scan(List<Location> locations) {
        List<FoundServer> out = new ArrayList<>();
        for (Location loc : locations) {
            if (!Files.isRegularFile(loc.file())) continue;
            try {
                JsonNode root = Json.MAPPER.readTree(Files.readString(loc.file()));
                JsonNode servers = root.get(loc.rootKey());
                if (servers == null || !servers.isObject()) continue;
                for (Iterator<String> it = servers.fieldNames(); it.hasNext(); ) {
                    String name = it.next();
                    if ("token-guard".equals(name)) continue;   // never proxy ourselves
                    JsonNode s = servers.get(name);
                    String command = Json.str(s, "command", null);
                    if (command == null) continue;              // http/sse servers unsupported (v1)
                    List<String> args = new ArrayList<>();
                    JsonNode a = s.get("args");
                    if (a != null && a.isArray()) for (JsonNode x : a) args.add(x.asText());
                    Map<String, String> env = new LinkedHashMap<>();
                    JsonNode e = s.get("env");
                    if (e != null && e.isObject())
                        for (Iterator<String> ek = e.fieldNames(); ek.hasNext(); ) {
                            String k = ek.next();
                            env.put(k, e.get(k).asText());
                        }
                    out.add(new FoundServer(loc.host(), loc.file(), name, command, args, env));
                }
            } catch (IOException | RuntimeException ex) {
                Log.warn("skipping unreadable host config " + loc.file() + ": " + ex.getMessage());
            }
        }
        return out;
    }

    /**
     * Append [servers.*] blocks to TokenGuard's config.toml. Idempotent: names
     * already configured are skipped. Returns the imported server names.
     */
    public static List<String> importInto(Path configToml, List<FoundServer> found)
            throws IOException {
        Config existing = Config.load(configToml);
        List<String> imported = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> takenNames = new java.util.HashSet<>();
        for (String k : existing.keys())
            if (k.startsWith("servers.") && k.endsWith(".command"))
                takenNames.add(k.substring("servers.".length(), k.length() - ".command".length()));

        for (FoundServer s : found) {
            String name = sanitize(s.name());
            if (takenNames.contains(name)) continue;   // idempotence
            takenNames.add(name);
            String joinedArgs = String.join(" ", s.args());
            if (s.args().stream().anyMatch(a -> a.contains(" ")))
                Log.warn("server '" + name + "': an argument contains spaces — the minimal "
                        + "TOML parser cannot represent it exactly; review the imported block");
            sb.append("\n[servers.").append(name).append("]\n");
            sb.append("# imported from ").append(s.host()).append(" (").append(s.file()).append(")\n");
            sb.append("command = \"").append(s.command()).append("\"\n");
            if (!joinedArgs.isBlank()) sb.append("args = \"").append(joinedArgs).append("\"\n");
            for (Map.Entry<String, String> e : s.env().entrySet())
                sb.append("env.").append(e.getKey()).append(" = \"").append(e.getValue()).append("\"\n");
            imported.add(name);
        }
        if (!imported.isEmpty()) {
            Files.createDirectories(configToml.getParent());
            Files.writeString(configToml,
                    (Files.exists(configToml) ? Files.readString(configToml) : "") + sb,
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return imported;
    }

    /**
     * EXPLICIT-OPT-IN host rewrite (--write-host-config): back the file up to
     * <file>.tg-backup, then replace its server map with a single token-guard
     * entry pointing at this binary. Never called on the default path.
     */
    public static void rewriteHostFile(Location loc, List<String> tokenGuardCommand)
            throws IOException {
        String original = Files.readString(loc.file());
        Files.writeString(loc.file().resolveSibling(loc.file().getFileName() + ".tg-backup"),
                original, java.nio.charset.StandardCharsets.UTF_8);
        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(original);
        ObjectNode servers = Json.obj();
        ObjectNode tg = Json.obj();
        tg.put("command", tokenGuardCommand.get(0));
        var args = Json.arr();
        for (int i = 1; i < tokenGuardCommand.size(); i++) args.add(tokenGuardCommand.get(i));
        tg.set("args", args);
        servers.set("token-guard", tg);
        root.set(loc.rootKey(), servers);
        Files.writeString(loc.file(),
                Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** TOML-section-safe server name (the minimal parser splits on dots). */
    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
