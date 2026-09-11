package io.tokenguard.codemode;

import io.tokenguard.util.Log;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * §6.4 sandbox: fresh Context per run (nothing shared to corrupt), all access
 * flags off, no IO capability — the HostApi proxies are the entire surface.
 * Resource limits (P0.4): statement-count ceiling + wall-clock watchdog always;
 * plus Oracle-GraalVM sandbox.MaxCPUTime/MaxHeapMemory when the runtime
 * supports them (probed once at startup, watchdog-only fallback on CE — the
 * watchdog also covers time blocked inside host calls, which CPU limits
 * would not).
 */
public final class ScriptSandbox implements AutoCloseable {

    public static final int RETURN_CAP_BYTES = 64 * 1024;

    /** engineFault distinguishes sandbox breakage (circuit breaker, §6.7.4) from script bugs. */
    public record Outcome(String value, String error, boolean engineFault) {
        public boolean ok() { return error == null; }
    }

    private final Engine engine;
    private final HostApi hostApi;
    private final long defaultTimeoutMs;
    private final long maxTimeoutMs;
    private final long statementLimit;
    private final String maxHeap;
    private final boolean sandboxOptions;   // P0.4: Oracle-GraalVM resource options active?
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tg-sandbox-watchdog");
                t.setDaemon(true);
                return t;
            });

    public static ScriptSandbox create(io.tokenguard.util.PathJail jail,
                                       io.tokenguard.util.IgnoreRules ignore,
                                       io.tokenguard.astindex.WorkspaceIndexer indexer,
                                       io.tokenguard.astindex.QueryEngine queryEngine,
                                       io.tokenguard.cache.DependencyTracker tracker,
                                       long defaultTimeoutMs, long maxTimeoutMs,
                                       long statementLimit, String maxHeap) {
        return new ScriptSandbox(new HostApi(jail, ignore, indexer, queryEngine, tracker),
                defaultTimeoutMs, maxTimeoutMs, statementLimit, maxHeap);
    }

    ScriptSandbox(HostApi hostApi, long defaultTimeoutMs, long maxTimeoutMs, long statementLimit) {
        this(hostApi, defaultTimeoutMs, maxTimeoutMs, statementLimit, "256MB");
    }

    ScriptSandbox(HostApi hostApi, long defaultTimeoutMs, long maxTimeoutMs, long statementLimit,
                  String maxHeap) {
        this.hostApi = hostApi;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.maxTimeoutMs = maxTimeoutMs;
        this.statementLimit = statementLimit;
        this.maxHeap = maxHeap;
        this.engine = Engine.newBuilder()
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        this.sandboxOptions = probeSandboxOptions();
        Log.info("sandbox resource limits: " + limitsMode());
    }

    /**
     * P0.4 (enhancement plan, reconciling discrepancy D6): try the Oracle
     * GraalVM sandbox options once; on a community stack the probe throws and
     * every run keeps the watchdog-only enforcement (plan §6.4 fallback). The
     * watchdog stays active in BOTH modes — it also covers time blocked inside
     * host calls, which CPU limits do not.
     */
    private boolean probeSandboxOptions() {
        try (Context probe = contextBuilder(true).build()) {
            probe.eval("js", "1");
            return true;
        } catch (RuntimeException | Error e) {
            Log.debug("Oracle sandbox options unavailable (" + e.getMessage()
                    + "); watchdog-only enforcement");
            return false;
        }
    }

    /** P1.4: wire the downstream dispatcher (execute_tool path) into scripts. */
    public void setMcpDispatch(java.util.function.Function<
            com.fasterxml.jackson.databind.node.ObjectNode,
            io.tokenguard.mcp.ToolResult> dispatch) {
        hostApi.setMcpDispatch(dispatch);
    }

    /** "sandbox-options + watchdog" on Oracle GraalVM, "watchdog-only" on CE. */
    public String limitsMode() {
        return sandboxOptions ? "sandbox-options (MaxCPUTime/MaxHeapMemory " + maxHeap
                + ") + watchdog" : "watchdog-only";
    }

    private Context.Builder contextBuilder(boolean withSandboxOptions) {
        Context.Builder b = Context.newBuilder("js")
                .engine(engine)
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(cls -> false)
                .allowCreateProcess(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowIO(IOAccess.NONE);
        if (withSandboxOptions) {
            b.option("sandbox.MaxCPUTime", maxTimeoutMs + "ms")
             .option("sandbox.MaxHeapMemory", maxHeap);
        }
        return b;
    }

    public Outcome run(String script, long requestedTimeoutMs) {
        // Per-call timeout may only lower the ceiling (§6.4)
        long timeout = requestedTimeoutMs > 0
                ? Math.min(requestedTimeoutMs, maxTimeoutMs) : defaultTimeoutMs;
        ResourceLimits limits = ResourceLimits.newBuilder()
                .statementLimit(statementLimit, null)
                .build();
        Context ctx = null;
        try {
            ctx = contextBuilder(sandboxOptions)
                    .resourceLimits(limits)
                    .build();
            Value bindings = ctx.getBindings("js");
            bindings.putMember("fs", hostApi.fs());
            bindings.putMember("index", hostApi.index());
            bindings.putMember("mcp", hostApi.mcp());

            final Context toKill = ctx;
            ScheduledFuture<?> killer = watchdog.schedule(
                    () -> toKill.close(true), timeout, TimeUnit.MILLISECONDS);
            Value result;
            try {
                result = ctx.eval("js", script);
            } finally {
                killer.cancel(false);
            }
            return new Outcome(cap(stringify(ctx, result)), null, false);
        } catch (PolyglotException e) {
            if (e.isResourceExhausted())
                return new Outcome(null, "Script exceeded the " + statementLimit
                        + "-statement limit. Narrow the work (filter earlier, fewer files) "
                        + "and retry.", false);
            if (e.isCancelled() || e.isInterrupted())
                return new Outcome(null, "Script exceeded the " + timeout + " ms time limit. "
                        + "Narrow the query or pass a smaller scope; timeout_ms can only be "
                        + "lowered, not raised.", false);
            if (e.isHostException()) {
                // Host API denial (HostError) or a host-side argument error —
                // a script-level failure, never a circuit-breaker fault
                return new Outcome(null, "Script error: " + e.getMessage()
                        + ". Fix the script and retry.", false);
            }
            if (e.isGuestException()) {
                String where = e.getSourceLocation() != null
                        ? " (line " + e.getSourceLocation().getStartLine() + ")" : "";
                return new Outcome(null, "Script error" + where + ": " + e.getMessage()
                        + ". Fix the script and retry.", false);
            }
            Log.error("sandbox engine fault", e);
            return new Outcome(null, "Sandbox internal error: " + e.getMessage(), true);
        } catch (IllegalStateException e) {
            // Context force-closed by the watchdog racing eval completion
            return new Outcome(null, "Script exceeded the " + timeout + " ms time limit.", false);
        } finally {
            if (ctx != null) {
                try {
                    ctx.close(true);
                } catch (RuntimeException ignore) {
                }
            }
        }
    }

    private static String stringify(Context ctx, Value v) {
        if (v == null || v.isNull()) return "null";
        if (v.isString()) return v.asString();
        try {
            Value json = ctx.eval("js", "JSON.stringify");
            Value s = json.execute(v);
            if (s.isString()) return s.asString();
        } catch (RuntimeException fallThrough) {
        }
        return v.toString();
    }

    private static String cap(String s) {
        if (s.length() <= RETURN_CAP_BYTES) return s;
        // Truncation is always labeled, never silent (§6.4)
        return s.substring(0, RETURN_CAP_BYTES)
                + "\n[truncated: result exceeded 64 KB — return a narrower slice]";
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        engine.close();
    }
}
