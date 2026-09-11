package io.tokenguard;

import io.tokenguard.astindex.QueryCodebaseStructureTool;
import io.tokenguard.astindex.QueryEngine;
import io.tokenguard.astindex.WorkspaceIndexer;
import io.tokenguard.cache.CacheLayer;
import io.tokenguard.cache.CacheStore;
import io.tokenguard.cache.DependencyTracker;
import io.tokenguard.codemode.ExecuteScriptTool;
import io.tokenguard.codemode.ScriptSandbox;
import io.tokenguard.codemode.SearchApiTool;
import io.tokenguard.entitlement.EntitlementManager;
import io.tokenguard.saas.SaasClient;
import io.tokenguard.distill.ApplyEditTool;
import io.tokenguard.distill.DiffService;
import io.tokenguard.distill.FileStateTracker;
import io.tokenguard.distill.ListDirectoryTool;
import io.tokenguard.distill.ReadFileTool;
import io.tokenguard.distill.SearchFilesTool;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.store.Database;
import io.tokenguard.telemetry.GuardStatusTool;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.telemetry.TelemetrySync;
import io.tokenguard.util.Config;
import io.tokenguard.util.Hashes;
import io.tokenguard.util.IgnoreRules;
import io.tokenguard.util.PathJail;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Central engine registration. Each build-sequence milestone (plan §7) adds
 * its tools and router layers here, so Main never changes shape.
 */
final class GuardWiring {

    private GuardWiring() {}

    /**
     * Team cache + audit setup (§6.2.4/§6.6.3). Runs off the stdio path: fetches
     * team keys, and only if cloud is entitled does it enable the shared cache
     * and (enterprise) the audit chain. A backend that never answers simply
     * leaves these features off — local tiers keep working.
     */
    private static void setUpCloudFeatures(EntitlementManager entitlements, SaasClient saas,
            CacheLayer cacheLayer, ToolRouter router, Database db, Config config,
            String licenseKey) {
        try {
            if (!entitlements.cloudOn() || licenseKey == null) return;
            var keys = saas.teamKeys(licenseKey,
                    io.tokenguard.util.DeviceId.fingerprint(entitlements.installId()));
            if (keys.isEmpty()) return;
            cacheLayer.setTeamCache(new io.tokenguard.teamcache.TeamCache(
                    saas, keys.get().hmacSecret(), keys.get().aesKey()));
            io.tokenguard.util.Log.info("team cache enabled");

            var ent = entitlements.entitlement();
            if (ent != null && ent.audit()) {
                // P4.5c: real batch shipper (chain order preserved; §6.6.3)
                io.tokenguard.telemetry.AuditUploader uploader =
                        new io.tokenguard.telemetry.AuditUploader(saas::postAudit, 20);
                Runtime.getRuntime().addShutdownHook(
                        new Thread(uploader::flushOnShutdown, "tg-audit-flush-shutdown"));
                router.addLayer(new io.tokenguard.telemetry.AuditLog(uploader));
                io.tokenguard.util.Log.info("enterprise audit trail enabled (batched upload)");
            }
        } catch (Exception e) {
            // P4.4: visible (not silently-debug) — a misconfigured backend should
            // be diagnosable from default logs; local engines are unaffected.
            io.tokenguard.util.Log.warn("cloud features disabled (team cache/audit): "
                    + e.getMessage() + " — local optimization is unaffected");
        }
    }

    /** P2.2: per-client / own-vs-downstream session summary from the trace table. */
    private static String renderSessionDetail(io.tokenguard.trace.TraceStore traceStore,
                                              io.tokenguard.trace.TraceLayer traceLayer) {
        var rows = traceStore.session(traceLayer.sessionId());
        if (rows.isEmpty()) return "no tool calls traced yet";
        String client = rows.stream().map(io.tokenguard.trace.TraceStore.Row::clientName)
                .filter(java.util.Objects::nonNull).findFirst().orElse("(unidentified client)");
        String version = rows.stream().map(io.tokenguard.trace.TraceStore.Row::clientVersion)
                .filter(java.util.Objects::nonNull).findFirst().orElse("");
        long own = rows.stream().filter(r -> !r.engine().equals("proxy")).count();
        long downstream = rows.size() - own;
        long raw = rows.stream().mapToLong(io.tokenguard.trace.TraceStore.Row::rawTokens).sum();
        long ret = rows.stream().mapToLong(io.tokenguard.trace.TraceStore.Row::returnedTokens).sum();
        long errors = rows.stream().filter(io.tokenguard.trace.TraceStore.Row::wasError).count();
        return "client: " + client + (version.isEmpty() ? "" : " " + version) + "\n"
                + "calls: " + rows.size() + " (own " + own + ", downstream " + downstream
                + ", errors " + errors + ")\n"
                + "trace tokens: raw " + raw + " -> returned " + ret;
    }

    /** Embedded root of trust for entitlement tokens (§4.3); null tolerated in dev builds. */
    private static java.security.PublicKey loadEntitlementKey() {
        try (var in = GuardWiring.class.getResourceAsStream("/keys/entitlement_ed25519.pub")) {
            if (in == null) {
                io.tokenguard.util.Log.warn("entitlement public key resource missing; "
                        + "licensed features unavailable");
                return null;
            }
            byte[] b64 = in.readAllBytes();
            return io.tokenguard.util.Crypto.ed25519PublicKey(
                    java.util.Base64.getMimeDecoder().decode(b64));
        } catch (Exception e) {
            io.tokenguard.util.Log.warn("entitlement public key unreadable: " + e.getMessage());
            return null;
        }
    }

    /** Backward-compatible overload (tests); telemetry opt-out defaults off. */
    static io.tokenguard.trace.TraceLayer wire(ToolRouter router, GuardStatusTool status, Database db,
                     Path workspaceRoot, Path dataDir, String profile, String licenseKey)
            throws IOException {
        return wire(router, status, db, workspaceRoot, dataDir, profile, licenseKey, false);
    }

    /** @return the trace layer, for Main to connect to McpServer's clientInfo (P0.2). */
    static io.tokenguard.trace.TraceLayer wire(ToolRouter router, GuardStatusTool status, Database db,
                     Path workspaceRoot, Path dataDir, String profile, String licenseKey,
                     boolean noTelemetry)
            throws IOException {
        Config config = Config.load(dataDir.resolve("config.toml"));
        Metrics metrics = new Metrics(db);
        PathJail jail = new PathJail(workspaceRoot);
        IgnoreRules ignore = new IgnoreRules(workspaceRoot);
        String workspaceId = Hashes.hex(Hashes.sha256(jail.root().toString()));

        // M6 — licensing (§6.3.1): cached signed token now, async refresh later
        SaasClient saas = new SaasClient(
                config.getString("backend.url", "https://api.tokenguard.io"));
        EntitlementManager entitlements = new EntitlementManager(db, dataDir,
                loadEntitlementKey(), licenseKey, saas,
                () -> java.time.Instant.now().getEpochSecond());
        entitlements.startBackgroundRefresh();
        status.addSection("License", entitlements::renderStatus);

        // M2 — Engine 1 Tier 1: exact cache. Registered before the metrics layer
        // so metrics stays outermost (counts hits and misses alike).
        DependencyTracker tracker = new DependencyTracker();
        long budgetBytes = entitlements.cacheMb(config.getLong("cache.size_mb", 512))
                * 1024 * 1024;
        // P4.5b — opt-in cache-at-rest encryption (§6.2.1): AES-256-GCM over
        // result_blob under a 0600 key file (keychain deferred; see LocalKeys).
        byte[] cacheKey = null;
        if (config.getBool("cache.encrypt", false)) {
            try {
                cacheKey = io.tokenguard.util.LocalKeys.loadOrCreate(dataDir.resolve("cache.key"));
                io.tokenguard.util.Log.info("cache-at-rest encryption enabled");
            } catch (IOException e) {
                io.tokenguard.util.Log.warn("cache.encrypt requested but key unavailable ("
                        + e.getMessage() + "); continuing UNENCRYPTED");
            }
        }
        CacheStore cacheStore = new CacheStore(db, jail.root(), budgetBytes, cacheKey);
        // M4 — Tier 2: fallback embedder per ADR-0003; threshold per §6.1.1
        CacheLayer cacheLayer = new CacheLayer(cacheStore, tracker, metrics, workspaceId,
                new io.tokenguard.cache.Embedder(),
                config.getDouble("cache.semantic_threshold", 0.94),
                entitlements::optimizationsOn);
        router.addLayer(cacheLayer);
        router.addLayer((tool, args, next) -> {
            metrics.toolCall(tool.name());
            return next.invoke(tool, args);
        });

        // M7 — Telemetry sync (§6.6.2): opt-out; daily rollups, no content.
        // P4.4: --no-telemetry CLI flag overrides the config file.
        boolean telemetryOn = !noTelemetry && config.getBool("telemetry.enabled", true);
        TelemetrySync telemetry = new TelemetrySync(db, saas, telemetryOn,
                entitlements.entitlement() != null ? entitlements.entitlement().licenseId() : null,
                entitlements.installId());
        telemetry.start();
        Runtime.getRuntime().addShutdownHook(new Thread(telemetry::flushOnShutdown, "tg-telemetry-flush"));

        // M7 — Team cache (§6.2.4) + enterprise audit (§6.6.3): both gated on
        // cloud entitlement + team keys, resolved off the stdio path.
        Thread cloudSetup = new Thread(() ->
                setUpCloudFeatures(entitlements, saas, cacheLayer, router, db, config, licenseKey),
                "tg-cloud-setup");
        cloudSetup.setDaemon(true);
        cloudSetup.start();
        // Outermost: pass-through warning, once per session, never on errors (§6.3.1/A13)
        java.util.concurrent.atomic.AtomicBoolean warned = new java.util.concurrent.atomic.AtomicBoolean();
        router.addLayer((tool, args, next) -> {
            io.tokenguard.mcp.ToolResult r = next.invoke(tool, args);
            if (!entitlements.optimizationsOn() && !r.isError()
                    && warned.compareAndSet(false, true)) {
                return new io.tokenguard.mcp.ToolResult(r.text()
                        + "\n\n[TokenGuard] License expired - token optimization is disabled "
                        + "and requests pass through raw. Renew at https://tokenguard.io/account.",
                        false, r.rawTokens());
            }
            return r;
        });

        // P0.2 — interaction trace: added LAST so it is the outermost layer
        // (addLayer wraps; last added = outermost). Unconditional, failure-isolated.
        io.tokenguard.trace.TraceStore traceStore = new io.tokenguard.trace.TraceStore(db);
        io.tokenguard.trace.TraceLayer traceLayer =
                new io.tokenguard.trace.TraceLayer(traceStore, entitlements::optimizationsOn);
        router.addLayer(traceLayer);

        // P3.3 — session journal + restore_context (compaction recovery)
        io.tokenguard.distill.SessionJournal journal = new io.tokenguard.distill.SessionJournal(
                db, workspaceId, (int) config.getLong("session.journal_max_events", 200));
        traceLayer.setJournal(journal);
        router.register(new io.tokenguard.distill.RestoreContextTool(journal));

        // P2.2 — guard_status v2: trace-derived session detail + optional $ estimate
        status.addSection("Session detail", () -> renderSessionDetail(traceStore, traceLayer));
        double pricePerMtok = config.getDouble("status.price_per_mtok", 0);
        if (pricePerMtok > 0) {
            status.addSection("Estimated cost saved", () -> String.format(java.util.Locale.ROOT,
                    "$%.4f (saved tokens x $%.2f/MTok input)",
                    metrics.savedTokens() * pricePerMtok / 1_000_000.0, pricePerMtok));
        }

        boolean codeMode = "code-mode".equals(profile);

        // P1.1/P1.2 — MCP multiplexer + dynamic toolset facade. Only active when
        // [servers.*] blocks are configured. Facade (default) registers its 3
        // fixed tools immediately and starts downstream servers ASYNC (the MCP
        // handshake never waits, §1.4 startup); namespaced mode must know tool
        // names at registration, so it starts synchronously (opt-in tradeoff).
        io.tokenguard.proxy.DownstreamRegistry downstream =
                io.tokenguard.proxy.DownstreamRegistry.fromConfig(config,
                        config.getLong("proxy.request_timeout_ms", 30_000));
        if (!downstream.isEmpty()) {
            Runtime.getRuntime().addShutdownHook(new Thread(downstream::close, "tg-ds-shutdown"));
            String expose = config.getString("proxy.expose", "facade");
            if ("namespaced".equals(expose)) {
                downstream.startAll();
                io.tokenguard.proxy.ProxyWiring.registerNamespaced(router, downstream);
            } else {
                io.tokenguard.proxy.ProxyWiring.registerFacade(router, downstream,
                        new io.tokenguard.cache.Embedder());
                Thread dsStart = new Thread(downstream::startAll, "tg-ds-start");
                dsStart.setDaemon(true);
                dsStart.start();
            }
            status.addSection("Downstream servers", downstream::renderStatus);
        }

        // M1 — Engine 4: Diff/Distill + baseline tools. apply_edit is the ONLY
        // write path and exists in BOTH profiles (§5.2 — scripts cannot write).
        FileStateTracker fileState = new FileStateTracker(db);
        DiffService diff = new DiffService();
        router.register(new ApplyEditTool(jail, fileState, diff, metrics));
        if (!codeMode) {
            router.register(new ReadFileTool(jail, fileState, diff, metrics,
                    entitlements::optimizationsOn));
            router.register(new ListDirectoryTool(jail, ignore, metrics));
            router.register(new SearchFilesTool(jail, ignore, metrics, tracker));
        }
        status.addSection("Savings (this session)", metrics::renderSession);
        status.addSection("Cache", () -> "entries: " + cacheStore.entryCount()
                + ", budget: " + (budgetBytes / (1024 * 1024)) + " MB");

        // M3 — Engine 2: AST index. Startup refresh is async — the MCP handshake
        // must never wait on indexing (§1.4 startup lifecycle).
        WorkspaceIndexer indexer = new WorkspaceIndexer(db, jail, ignore, workspaceId);
        QueryEngine queryEngine = new QueryEngine(db, workspaceId);
        if (!codeMode)
            router.register(new QueryCodebaseStructureTool(indexer, queryEngine, tracker, metrics));
        status.addSection("Codebase index", () -> {
            WorkspaceIndexer.Stats s = indexer.stats();
            return "files: " + s.ok() + " ok, " + s.partial() + " partial, " + s.failed()
                    + " failed; symbols: " + s.symbols()
                    + "; last refresh: " + s.lastRefreshMs() + " ms";
        });
        Thread indexThread = new Thread(() -> {
            try {
                indexer.refresh();
            } catch (Exception e) {
                io.tokenguard.util.Log.warn("initial index refresh failed: " + e.getMessage());
            }
        }, "tg-indexer");
        indexThread.setDaemon(true);
        indexThread.start();

        // M5 — Engine 3: Code Mode. execute_script in both profiles (§5.1);
        // search_api only in code-mode, where it replaces the tool schemas.
        ScriptSandbox sandbox = ScriptSandbox.create(jail, ignore, indexer, queryEngine, tracker,
                config.getLong("sandbox.timeout_ms", 5000),
                config.getLong("sandbox.max_timeout_ms", 5000),
                config.getLong("sandbox.statement_limit", 5_000_000),
                config.getString("sandbox.max_heap", "256MB"));
        ExecuteScriptTool executeScript = new ExecuteScriptTool(sandbox, indexer, metrics);
        router.register(executeScript);
        if (codeMode) router.register(new SearchApiTool());

        // P1.4 — mcp.call inside scripts. Facade mode dispatches through the
        // REGISTERED execute_tool (cache/metrics/trace apply); namespaced mode
        // has no execute_tool registered, so it dispatches directly (uncached —
        // documented tradeoff of the compatibility mode).
        if (!downstream.isEmpty()) {
            io.tokenguard.proxy.ExecuteToolTool directDispatch =
                    new io.tokenguard.proxy.ExecuteToolTool(downstream);
            sandbox.setMcpDispatch(args -> router.has("execute_tool")
                    ? router.call("execute_tool", args)
                    : directDispatch.call(args));
        }
        status.addSection("Code mode", () -> executeScript.isDisabled()
                ? "DISABLED (circuit breaker tripped — sandbox faults; restart to re-enable)"
                : "sandbox ok (5 s / " + config.getLong("sandbox.statement_limit", 5_000_000)
                        + " statements per script; limits: " + sandbox.limitsMode() + ")");

        return traceLayer;
    }
}
