package io.tokenguard.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Json;
import io.tokenguard.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P1.1 (enhancement plan): client side of one downstream MCP server —
 * TokenGuard spawns the configured process, performs the MCP handshake
 * client-side (2025-06-18), and exposes tools/list + tools/call.
 *
 * Lifecycle per plan §6.7.5: on failure, restart ONCE with backoff; if the
 * restarted process fails too, mark UNAVAILABLE and return structured errors
 * naming the dead server. TokenGuard's own tools are never affected.
 */
public final class DownstreamServer implements AutoCloseable {

    public record DownstreamTool(String name, String description, ObjectNode inputSchema) {}
    public record CallResult(String text, boolean isError) {}

    /** Structured downstream failure — becomes a tool-result error upstream. */
    public static final class DownstreamException extends Exception {
        public DownstreamException(String message) { super(message); }
        public DownstreamException(String message, Throwable cause) { super(message, cause); }
    }

    public enum State { NEW, READY, UNAVAILABLE }

    private static final long RESTART_BACKOFF_MS = 200;

    private final String name;
    private final List<String> command;
    private final Map<String, String> env;
    private final long requestTimeoutMs;

    private final Object lifecycle = new Object();
    private Process process;
    private BufferedWriter writer;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private volatile State state = State.NEW;
    private volatile List<DownstreamTool> tools = List.of();
    private boolean restartUsed;   // §6.7.5: one restart per process lifetime, then UNAVAILABLE

    public DownstreamServer(String name, List<String> command, Map<String, String> env,
                            long requestTimeoutMs) {
        this.name = name;
        this.command = List.copyOf(command);
        this.env = Map.copyOf(env);
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public String name() { return name; }
    public State state() { return state; }
    public List<DownstreamTool> tools() { return tools; }

    /** Spawn + client-side MCP handshake + tool discovery. */
    public void start() throws DownstreamException {
        synchronized (lifecycle) {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.environment().putAll(env);
                process = pb.start();
                writer = new BufferedWriter(new OutputStreamWriter(
                        process.getOutputStream(), StandardCharsets.UTF_8));
                startReader(process);
                drainStderr(process);

                ObjectNode initParams = Json.obj();
                initParams.put("protocolVersion", "2025-06-18");
                initParams.set("capabilities", Json.obj());
                ObjectNode ci = Json.obj();
                ci.put("name", "token-guard");
                ci.put("version", io.tokenguard.Main.VERSION);
                initParams.set("clientInfo", ci);
                request("initialize", initParams);
                notify("notifications/initialized");

                JsonNode listed = request("tools/list", Json.obj());
                List<DownstreamTool> found = new ArrayList<>();
                JsonNode arr = listed.get("tools");
                if (arr != null && arr.isArray()) {
                    for (JsonNode t : arr) {
                        JsonNode schema = t.get("inputSchema");
                        found.add(new DownstreamTool(
                                Json.str(t, "name", "?"),
                                Json.str(t, "description", ""),
                                schema != null && schema.isObject()
                                        ? (ObjectNode) schema : Json.schemaObject(Json.obj())));
                    }
                }
                tools = List.copyOf(found);
                state = State.READY;
                Log.info("downstream '" + name + "' ready (" + tools.size() + " tools)");
            } catch (DownstreamException e) {
                closeProcessQuietly();
                throw e;
            } catch (Exception e) {
                closeProcessQuietly();
                throw new DownstreamException("downstream '" + name + "' failed to start: "
                        + e.getMessage(), e);
            }
        }
    }

    /**
     * tools/call with §6.7.5 recovery: one restart-with-backoff, then
     * UNAVAILABLE. The failed call is retried once on the restarted process.
     */
    public CallResult call(String tool, ObjectNode args) throws DownstreamException {
        if (state == State.UNAVAILABLE)
            throw new DownstreamException("Downstream MCP server '" + name
                    + "' is unavailable (crashed and did not survive a restart). "
                    + "Other tools keep working; restart TokenGuard to retry it.");
        try {
            return doCall(tool, args);
        } catch (DownstreamException first) {
            synchronized (lifecycle) {
                if (restartUsed) {
                    state = State.UNAVAILABLE;
                    throw unavailable(first);
                }
                restartUsed = true;
            }
            Log.warn("downstream '" + name + "' failed (" + first.getMessage()
                    + "); restarting once per §6.7.5");
            closeProcessQuietly();
            try {
                Thread.sleep(RESTART_BACKOFF_MS);
                start();
                return doCall(tool, args);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                state = State.UNAVAILABLE;
                throw unavailable(first);
            } catch (DownstreamException second) {
                state = State.UNAVAILABLE;
                throw unavailable(second);
            }
        }
    }

    private DownstreamException unavailable(DownstreamException cause) {
        return new DownstreamException("Downstream MCP server '" + name
                + "' is unavailable (crashed and did not survive a restart): "
                + cause.getMessage() + ". Other tools keep working.");
    }

    private CallResult doCall(String tool, ObjectNode args) throws DownstreamException {
        ObjectNode params = Json.obj();
        params.put("name", tool);
        params.set("arguments", args);
        JsonNode result = request("tools/call", params);
        StringBuilder text = new StringBuilder();
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode c : content)
                if ("text".equals(Json.str(c, "type", ""))) {
                    if (text.length() > 0) text.append('\n');
                    text.append(Json.str(c, "text", ""));
                }
        }
        return new CallResult(text.toString(), Json.boolVal(result, "isError", false));
    }

    private JsonNode request(String method, ObjectNode params) throws DownstreamException {
        Process p = process;
        if (p == null || !p.isAlive())
            throw new DownstreamException("downstream '" + name + "' process is not running");
        long id = ids.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            ObjectNode env = Json.obj();
            env.put("jsonrpc", "2.0");
            env.put("id", id);
            env.put("method", method);
            env.set("params", params);
            writeLine(env);
            JsonNode response = future.get(requestTimeoutMs, TimeUnit.MILLISECONDS);
            JsonNode error = response.get("error");
            if (error != null && !error.isNull())
                throw new DownstreamException("downstream '" + name + "' error on " + method
                        + ": " + Json.str(error, "message", error.toString()));
            JsonNode result = response.get("result");
            return result != null ? result : Json.obj();
        } catch (TimeoutException e) {
            throw new DownstreamException("downstream '" + name + "' timed out after "
                    + requestTimeoutMs + " ms on " + method);
        } catch (DownstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new DownstreamException("downstream '" + name + "' request failed: "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        } finally {
            pending.remove(id);
        }
    }

    private void notify(String method) throws IOException {
        ObjectNode env = Json.obj();
        env.put("jsonrpc", "2.0");
        env.put("method", method);
        writeLine(env);
    }

    private synchronized void writeLine(ObjectNode msg) throws IOException {
        writer.write(Json.MAPPER.writeValueAsString(msg));
        writer.write('\n');
        writer.flush();
    }

    private void startReader(Process p) {
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode msg = Json.MAPPER.readTree(line);
                        JsonNode id = msg.get("id");
                        if (id != null && id.canConvertToLong()) {
                            CompletableFuture<JsonNode> f = pending.get(id.asLong());
                            if (f != null) f.complete(msg);
                        } // server-initiated requests/notifications: none supported yet
                    } catch (IOException parse) {
                        Log.debug("downstream '" + name + "' emitted non-JSON: " + line);
                    }
                }
            } catch (IOException closed) {
                // process death — pending requests fail below
            }
            pending.values().forEach(f -> f.completeExceptionally(
                    new IOException("downstream '" + name + "' pipe closed")));
        }, "tg-ds-" + name + "-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void drainStderr(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(
                    p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null)
                    Log.debug("[" + name + "] " + line);
            } catch (IOException ignored) {
            }
        }, "tg-ds-" + name + "-stderr");
        t.setDaemon(true);
        t.start();
    }

    private void closeProcessQuietly() {
        Process p = process;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        process = null;
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            closeProcessQuietly();
            state = State.UNAVAILABLE;
        }
    }
}
