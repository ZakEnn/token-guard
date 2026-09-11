package io.tokenguard.proxy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Config;
import io.tokenguard.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * P1.1 (enhancement plan): lifecycle owner for all configured downstream MCP
 * servers ([servers.<name>] blocks in ~/.token-guard/config.toml). Startup is
 * per-server fault-isolated: one broken config/binary never blocks the rest,
 * and never blocks TokenGuard's own tools.
 *
 * Config shape (minimal-TOML constraints — no arrays, so args is one
 * whitespace-split string; quoted arguments are not supported, documented):
 *   [servers.github]
 *   command = "npx"
 *   args = "-y @modelcontextprotocol/server-github"
 *   env.GITHUB_TOKEN = "..."
 */
public final class DownstreamRegistry implements AutoCloseable {

    public record QualifiedTool(String server, DownstreamServer.DownstreamTool tool) {}

    private final Map<String, DownstreamServer> servers = new LinkedHashMap<>();

    public static DownstreamRegistry fromConfig(Config cfg, long requestTimeoutMs) {
        DownstreamRegistry reg = new DownstreamRegistry();
        // Collect names from "servers.<name>.command" keys, sorted for stable order
        Map<String, String> commands = new TreeMap<>();
        for (String key : cfg.keys()) {
            if (key.startsWith("servers.") && key.endsWith(".command")) {
                String name = key.substring("servers.".length(),
                        key.length() - ".command".length());
                if (!name.isBlank() && !name.contains("."))
                    commands.put(name, cfg.getString(key, ""));
            }
        }
        for (Map.Entry<String, String> e : commands.entrySet()) {
            String name = e.getKey();
            List<String> cmd = new ArrayList<>();
            cmd.add(e.getValue());
            String args = cfg.getString("servers." + name + ".args", "");
            for (String a : args.trim().split("\\s+"))
                if (!a.isBlank()) cmd.add(a);
            Map<String, String> env = new LinkedHashMap<>();
            String envPrefix = "servers." + name + ".env.";
            for (String key : cfg.keys())
                if (key.startsWith(envPrefix))
                    env.put(key.substring(envPrefix.length()), cfg.getString(key, ""));
            reg.add(new DownstreamServer(name, cmd, env, requestTimeoutMs));
        }
        return reg;
    }

    public void add(DownstreamServer server) {
        servers.put(server.name(), server);
    }

    /** Start every server; failures are logged and isolated (§6.7.5). */
    public void startAll() {
        for (DownstreamServer s : servers.values()) {
            try {
                s.start();
            } catch (DownstreamServer.DownstreamException e) {
                Log.warn(e.getMessage());
            }
        }
    }

    public boolean isEmpty() { return servers.isEmpty(); }
    public Collection<DownstreamServer> servers() { return servers.values(); }
    public Optional<DownstreamServer> get(String name) {
        return Optional.ofNullable(servers.get(name));
    }

    /** Every known downstream tool, server-qualified, READY servers only. */
    public List<QualifiedTool> allTools() {
        List<QualifiedTool> out = new ArrayList<>();
        for (DownstreamServer s : servers.values())
            if (s.state() == DownstreamServer.State.READY)
                for (DownstreamServer.DownstreamTool t : s.tools())
                    out.add(new QualifiedTool(s.name(), t));
        return out;
    }

    public DownstreamServer.CallResult call(String server, String tool, ObjectNode args)
            throws DownstreamServer.DownstreamException {
        DownstreamServer s = servers.get(server);
        if (s == null)
            throw new DownstreamServer.DownstreamException("No downstream MCP server named '"
                    + server + "' is configured. Configured: " + servers.keySet());
        return s.call(tool, args);
    }

    public String renderStatus() {
        if (servers.isEmpty()) return "no downstream servers configured";
        StringBuilder sb = new StringBuilder();
        for (DownstreamServer s : servers.values()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s.name()).append(": ").append(s.state().name().toLowerCase())
              .append(s.state() == DownstreamServer.State.READY
                      ? " (" + s.tools().size() + " tools)" : "");
        }
        return sb.toString();
    }

    @Override
    public void close() {
        for (DownstreamServer s : servers.values()) s.close();
    }
}
