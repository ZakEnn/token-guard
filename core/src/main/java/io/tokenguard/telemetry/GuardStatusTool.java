package io.tokenguard.telemetry;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * guard_status (plan §5.1): cache hit rate, tokens saved, index freshness,
 * degradation state. Sections register themselves as engines come online, so
 * this tool grows with the build without being rewritten.
 */
public final class GuardStatusTool implements Tool {

    private final String version;
    private final Map<String, Supplier<String>> sections = new LinkedHashMap<>();

    public GuardStatusTool(String version) {
        this.version = version;
    }

    public synchronized void addSection(String title, Supplier<String> render) {
        sections.put(title, render);
    }

    @Override public String name() { return "guard_status"; }

    @Override public String description() {
        return "TokenGuard health and savings report: cache hit rate, estimated tokens saved, "
                + "codebase index freshness, and any degradation state.";
    }

    @Override public ObjectNode inputSchema() {
        return Json.schemaObject(Json.obj());
    }

    @Override
    public synchronized ToolResult call(ObjectNode args) {
        StringBuilder sb = new StringBuilder();
        sb.append("TokenGuard v").append(version).append('\n');
        for (Map.Entry<String, Supplier<String>> e : sections.entrySet()) {
            sb.append("\n## ").append(e.getKey()).append('\n');
            try {
                sb.append(e.getValue().get()).append('\n');
            } catch (Exception ex) {
                sb.append("(unavailable: ").append(ex.getMessage()).append(")\n");
            }
        }
        return ToolResult.text(sb.toString());
    }
}
