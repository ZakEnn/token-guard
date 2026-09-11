package io.tokenguard.telemetry;

import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.util.Hashes;
import io.tokenguard.util.Log;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Enterprise audit trail (§6.6.3): opt-in, hash-chained, hashes-only. Records
 * tool name + sha256(args) + prev_hash — argument CONTENT never enters the
 * chain, so the trail proves "this exact known call ran" without copying
 * proprietary code to the cloud. Runs as an outermost router layer.
 */
public final class AuditLog implements ToolRouter.Layer {

    private final AtomicReference<byte[]> prevHash = new AtomicReference<>(new byte[32]);
    private final AuditSink sink;

    public interface AuditSink {
        void append(String tool, byte[] argsHash, byte[] prevHash, byte[] chainHash, long ts);
    }

    public AuditLog(AuditSink sink) {
        this.sink = sink;
    }

    @Override
    public ToolResult invoke(Tool tool, ObjectNode args, ToolRouter.Invoker next) throws Exception {
        long ts = System.currentTimeMillis();
        byte[] argsHash = Hashes.sha256(args.toString());
        byte[] prev = prevHash.get();
        // chain = sha256(prev || tool || argsHash || ts): truncation/tampering is evident
        byte[] chain = Hashes.sha256(concat(prev, Hashes.sha256(tool.name()), argsHash,
                String.valueOf(ts).getBytes()));
        prevHash.set(chain);
        try {
            sink.append(tool.name(), argsHash, prev, chain, ts);
        } catch (Exception e) {
            Log.debug("audit append failed: " + e.getMessage());
        }
        return next.invoke(tool, args);
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int i = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }
}
