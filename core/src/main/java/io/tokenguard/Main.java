package io.tokenguard;

import io.tokenguard.mcp.McpServer;
import io.tokenguard.mcp.StdioTransport;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.store.Database;
import io.tokenguard.telemetry.GuardStatusTool;
import io.tokenguard.util.Log;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Command(name = "token-guard", mixinStandardHelpOptions = true,
        version = Main.VERSION,
        description = "TokenGuard — token optimization MCP server (stdio)",
        subcommands = {ImportServersCommand.class, HooksCommands.Install.class,
                HooksCommands.Uninstall.class, HooksCommands.HookGate.class})
public final class Main implements Callable<Integer> {

    public static final String VERSION = "0.1.0";

    @Option(names = "--license-key", description = "License key (or set TOKEN_GUARD_LICENSE_KEY)")
    String licenseKey;

    @Option(names = "--workspace", description = "Workspace root (default: current directory)")
    Path workspace;

    @Option(names = "--cache-dir", description = "Data directory (default: TOKEN_GUARD_CACHE_DIR or ~/.token-guard)")
    Path cacheDir;

    @Option(names = "--profile", description = "Tool profile: standard | code-mode (default: standard)")
    String profile = "standard";

    @Option(names = "--doctor", description = "Run self-checks and exit")
    boolean doctor;

    @Option(names = "--debug", description = "Verbose logging to stderr")
    boolean debug;

    @Option(names = "--no-telemetry",
            description = "Disable anonymous usage telemetry (equivalent to telemetry.enabled=false)")
    boolean noTelemetry;   // P4.4: opt-out without editing a file

    @Option(names = "--log-json", description = "Structured JSON logs on stderr")
    boolean logJson;

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        if (exit != 0) System.exit(exit);
    }

    @Override
    public Integer call() throws Exception {
        Log.setDebug(debug);
        Log.setJson(logJson);
        Path dataDir = resolveDataDir();
        Path workspaceRoot = (workspace != null ? workspace : Paths.get(""))
                .toAbsolutePath().normalize();

        // P4.4: fail fast with an actionable one-liner instead of a stack trace
        if (!java.nio.file.Files.isDirectory(workspaceRoot)) {
            Log.warn("workspace path does not exist or is not a directory: " + workspaceRoot
                    + " — pass --workspace /path/to/your/project (see the Troubleshooting "
                    + "table in README.md)");
            return 1;
        }

        if (doctor) return Doctor.run(dataDir, workspaceRoot);

        Database db = Database.open(dataDir.resolve("guard.db"));
        Runtime.getRuntime().addShutdownHook(new Thread(db::close, "tg-shutdown"));

        ToolRouter router = new ToolRouter();
        GuardStatusTool status = new GuardStatusTool(VERSION);
        status.addSection("Storage", () -> "database: " + db.path() + " (ok)");
        router.register(status);

        String key = licenseKey != null ? licenseKey
                : System.getenv("TOKEN_GUARD_LICENSE_KEY");
        io.tokenguard.trace.TraceLayer trace = GuardWiring.wire(router, status, db,
                workspaceRoot, dataDir, profile,
                key == null || key.isBlank() ? null : key, noTelemetry);

        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tg-worker");
            t.setDaemon(true);
            return t;
        });

        Log.info("TokenGuard v" + VERSION + " starting; workspace=" + workspaceRoot
                + " profile=" + profile);
        McpServer server = new McpServer(router, VERSION);
        server.setClientInfoListener(trace::setClient);   // P0.2: clientInfo -> trace rows
        new StdioTransport(System.in, System.out, server, pool).run();
        return 0;
    }

    Path resolveDataDir() {   // package-visible for ImportServersCommand (P1.3)
        if (cacheDir != null) return cacheDir.toAbsolutePath().normalize();
        String env = System.getenv("TOKEN_GUARD_CACHE_DIR");
        if (env != null && !env.isBlank()) {
            String expanded = env.startsWith("~") ?
                    System.getProperty("user.home") + env.substring(1) : env;
            return Paths.get(expanded).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".token-guard");
    }
}
