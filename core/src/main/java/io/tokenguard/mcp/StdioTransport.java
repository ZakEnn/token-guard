package io.tokenguard.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Json;
import io.tokenguard.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCP stdio framing: newline-delimited UTF-8 JSON-RPC 2.0. Single reader
 * thread; requests dispatch to a worker pool (A11 — hosts mostly serialize,
 * but we don't rely on it); writes are serialized on the writer lock.
 */
public final class StdioTransport {

    private final BufferedReader in;
    private final BufferedWriter out;
    private final McpServer server;
    private final ExecutorService pool;
    private final Object writeLock = new Object();

    public StdioTransport(InputStream in, OutputStream out, McpServer server, ExecutorService pool) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        this.server = server;
        this.pool = pool;
    }

    /** Blocks until stdin closes (host shutdown per §6.7.6). */
    public void run() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            // Windows shells (PowerShell pipes) prepend a UTF-8 BOM to the first
            // line; without stripping it, the initialize request is a parse error
            // and client attribution is silently lost (found in the P4 prod pass).
            if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1);
            if (line.isBlank()) continue;
            dispatchLine(line);
        }
        // Drain in-flight requests before exiting — workers are daemon threads,
        // so returning here without waiting would drop queued responses.
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS))
                Log.warn("worker pool did not drain within 30s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Log.info("stdin closed; shutting down");
    }

    private void dispatchLine(String line) {
        JsonNode msg;
        try {
            msg = Json.MAPPER.readTree(line);
        } catch (IOException e) {
            write(McpServer.error(null, -32700, "Parse error"));
            return;
        }
        if (!msg.isObject()) {
            // Batches were removed from MCP in 2025-06-18; reject uniformly.
            write(McpServer.error(null, -32600, "Invalid request"));
            return;
        }
        JsonNode methodNode = msg.get("method");
        JsonNode id = msg.get("id");
        if (methodNode == null || !methodNode.isTextual()) {
            // A response to a server-initiated request; none exist yet (multiplexer owns its own pipes).
            if (id == null) write(McpServer.error(null, -32600, "Invalid request"));
            return;
        }
        String method = methodNode.asText();
        JsonNode params = msg.get("params");
        if (id == null || id.isNull()) {
            server.handleNotification(method, params);
        } else if ("tools/call".equals(method)) {
            // Tool calls are serialized on the worker (A11 refined: file-state diff
            // semantics require in-order execution; hosts serialize anyway)
            pool.submit(() -> write(server.handleRequest(id, method, params)));
        } else {
            // Protocol methods are trivial — answer inline so ping/initialize stay
            // responsive even while a slow tool call is running
            write(server.handleRequest(id, method, params));
        }
    }

    private void write(ObjectNode response) {
        if (response == null) return;
        try {
            String json = Json.MAPPER.writeValueAsString(response);
            synchronized (writeLock) {
                out.write(json);
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            Log.error("Failed writing response", e);
        }
    }
}
