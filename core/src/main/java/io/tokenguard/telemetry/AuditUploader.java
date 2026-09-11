package io.tokenguard.telemetry;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Json;
import io.tokenguard.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P4.5c (enhancement plan): batch shipper for the enterprise audit chain
 * (§6.6.3). Buffers hash-chain rows in memory and posts them to /v1/audit in
 * order — chain continuity (entry[i].prev_hash == entry[i-1].chain_hash) is
 * preserved across batches so the server can verify tamper-evidence.
 *
 * Uploads run on a daemon thread, never on the tool-call path; a failed batch
 * is re-queued at the head (§6.7.1 buffering posture). The transport is
 * pluggable: production wires SaasClient::postAudit; tests verify chain/batch
 * behavior without a live backend (the live round-trip is gated on P4.1-P4.3).
 */
public final class AuditUploader implements AuditLog.AuditSink, AutoCloseable {

    @FunctionalInterface
    public interface Transport {
        boolean send(ArrayNode entries);
    }

    private final Transport transport;
    private final int batchSize;
    private final List<ObjectNode> buffer = new ArrayList<>();
    private final ExecutorService flusher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tg-audit-flush");
        t.setDaemon(true);
        return t;
    });

    public AuditUploader(Transport transport, int batchSize) {
        this.transport = transport;
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public synchronized void append(String tool, byte[] argsHash, byte[] prevHash,
                                    byte[] chainHash, long ts) {
        ObjectNode entry = Json.obj();
        entry.put("tool", tool);
        entry.put("args_hash", io.tokenguard.util.Hashes.hex(argsHash));
        entry.put("prev_hash", io.tokenguard.util.Hashes.hex(prevHash));
        entry.put("chain_hash", io.tokenguard.util.Hashes.hex(chainHash));
        entry.put("ts", ts);
        buffer.add(entry);
        if (buffer.size() >= batchSize) {
            List<ObjectNode> batch = new ArrayList<>(buffer);
            buffer.clear();
            flusher.submit(() -> ship(batch));
        }
    }

    private void ship(List<ObjectNode> batch) {
        ArrayNode entries = Json.arr();
        batch.forEach(entries::add);
        if (!transport.send(entries)) {
            // re-queue at the head to preserve chain order for the next attempt
            synchronized (this) {
                buffer.addAll(0, batch);
            }
            Log.debug("audit batch upload failed; re-queued " + batch.size() + " entries");
        }
    }

    /** Shutdown flush — ships whatever is buffered, best-effort. */
    public synchronized void flushOnShutdown() {
        if (buffer.isEmpty()) return;
        List<ObjectNode> batch = new ArrayList<>(buffer);
        buffer.clear();
        ship(batch);
    }

    synchronized int buffered() { return buffer.size(); }

    @Override
    public void close() {
        flushOnShutdown();
        flusher.shutdown();
    }
}
