package io.tokenguard.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.5c DoD (transport-faked; the live /v1/audit round-trip is gated on the
 * blocked backend items P4.1-P4.3): batches ship at the threshold in order,
 * the hash chain is continuous ACROSS batches, failed batches are re-queued
 * head-first, and shutdown flushes the remainder.
 */
class AuditUploaderTest {

    private static Tool noop(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "t"; }
            @Override public ObjectNode inputSchema() { return Json.schemaObject(Json.obj()); }
            @Override public ToolResult call(ObjectNode args) { return ToolResult.text("ok"); }
        };
    }

    @Test
    void chainIsContinuousAcrossBatches() throws Exception {
        List<ArrayNode> shipped = new ArrayList<>();
        try (AuditUploader uploader = new AuditUploader(batch -> {
            synchronized (shipped) { shipped.add(batch); }
            return true;
        }, 3)) {
            ToolRouter router = new ToolRouter();
            router.register(noop("read_file"));
            router.addLayer(new AuditLog(uploader));
            for (int i = 0; i < 7; i++) {
                ObjectNode args = Json.obj();
                args.put("i", i);
                router.call("read_file", args);
            }
            // 7 appends, batch=3 -> two shipped batches (6), one buffered
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                synchronized (shipped) { if (shipped.size() == 2) break; }
                Thread.sleep(20);
            }
            synchronized (shipped) { assertEquals(2, shipped.size()); }
            assertEquals(1, uploader.buffered());

            List<JsonNode> all = new ArrayList<>();
            synchronized (shipped) {
                for (ArrayNode b : shipped) b.forEach(all::add);
            }
            assertEquals(6, all.size());
            // genesis prev = 32 zero bytes
            assertEquals("0".repeat(64), all.get(0).get("prev_hash").asText());
            for (int i = 1; i < all.size(); i++)
                assertEquals(all.get(i - 1).get("chain_hash").asText(),
                        all.get(i).get("prev_hash").asText(),
                        "chain must be continuous across batch boundary at entry " + i);
        }
    }

    @Test
    void failedBatchIsRequeuedHeadFirstThenRetriedInOrder() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<ArrayNode> delivered = new ArrayList<>();
        try (AuditUploader uploader = new AuditUploader(batch -> {
            if (attempts.incrementAndGet() == 1) return false;   // first ship fails
            synchronized (delivered) { delivered.add(batch); }
            return true;
        }, 2)) {
            uploader.append("a", new byte[32], new byte[32], new byte[]{1}, 1);
            uploader.append("b", new byte[32], new byte[]{1}, new byte[]{2}, 2);
            long deadline = System.currentTimeMillis() + 5000;
            while (attempts.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            // the failed pair is back in the buffer, order preserved
            deadline = System.currentTimeMillis() + 5000;
            while (uploader.buffered() < 2 && System.currentTimeMillis() < deadline)
                Thread.sleep(20);
            assertEquals(2, uploader.buffered());

            uploader.flushOnShutdown();   // retry succeeds
            synchronized (delivered) {
                assertEquals(1, delivered.size());
                assertEquals("a", delivered.get(0).get(0).get("tool").asText());
                assertEquals("b", delivered.get(0).get(1).get("tool").asText());
            }
        }
    }
}
