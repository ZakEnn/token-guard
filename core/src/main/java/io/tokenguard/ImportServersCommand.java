package io.tokenguard;

import io.tokenguard.proxy.HostConfigScanner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * P1.3 (enhancement plan): `token-guard import-servers` — absorb the MCP
 * servers from the user's existing IDE configs into TokenGuard's own
 * [servers.*] config, printing the host-side replacement snippet. Host files
 * are NEVER modified unless --write-host-config is passed explicitly.
 */
@Command(name = "import-servers",
        description = "Scan IDE mcp.json files and import their servers into TokenGuard's config")
public final class ImportServersCommand implements java.util.concurrent.Callable<Integer> {

    @ParentCommand Main parent;

    @Option(names = "--dry-run", description = "List what would be imported; write nothing")
    boolean dryRun;

    @Option(names = "--write-host-config",
            description = "ALSO rewrite the scanned host files to contain only token-guard "
                    + "(a .tg-backup is written first)")
    boolean writeHostConfig;

    @Override
    public Integer call() throws Exception {
        Path home = Paths.get(System.getProperty("user.home"));
        String appDataEnv = System.getenv("APPDATA");
        Path appData = appDataEnv == null ? null : Paths.get(appDataEnv);
        Path cwd = parent.workspace != null
                ? parent.workspace.toAbsolutePath().normalize()
                : Paths.get("").toAbsolutePath().normalize();

        List<HostConfigScanner.Location> locations =
                HostConfigScanner.candidates(home, appData, cwd);
        List<HostConfigScanner.FoundServer> found = HostConfigScanner.scan(locations);
        if (found.isEmpty()) {
            System.out.println("No MCP servers found in known host configs "
                    + "(scanned " + locations.size() + " locations under " + cwd + ", " + home + ").");
            return 0;
        }
        for (HostConfigScanner.FoundServer s : found)
            System.out.println("  " + s.name() + "  <- " + s.host() + " (" + s.file() + ")");

        if (dryRun) {
            System.out.println("Dry run: nothing written. Re-run without --dry-run to import.");
            return 0;
        }

        Path configToml = parent.resolveDataDir().resolve("config.toml");
        List<String> imported = HostConfigScanner.importInto(configToml, found);
        System.out.println(imported.isEmpty()
                ? "Nothing new to import (all servers already configured)."
                : "Imported " + imported + " into " + configToml);
        System.out.println("""

                Now make token-guard the only server in your IDE config:
                  { "mcpServers": { "token-guard": { "command": "token-guard",
                      "args": ["--workspace", "."] } } }
                (VS Code uses "servers" instead of "mcpServers".)""");

        if (writeHostConfig) {
            for (HostConfigScanner.Location loc : locations) {
                if (found.stream().noneMatch(f -> f.file().equals(loc.file()))) continue;
                HostConfigScanner.rewriteHostFile(loc, List.of("token-guard", "--workspace", "."));
                System.out.println("Rewrote " + loc.file() + " (backup: "
                        + loc.file().getFileName() + ".tg-backup)");
            }
        }
        return 0;
    }
}
