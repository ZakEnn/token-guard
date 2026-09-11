package io.tokenguard.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Log;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool registry + dispatch. Every call goes through the invoker chain so
 * cross-cutting layers (cache in M2, metrics in M1) wrap execution without the
 * tools knowing (plan §1.3 Tool Router).
 */
public final class ToolRouter {

    @FunctionalInterface
    public interface Invoker {
        ToolResult invoke(Tool tool, ObjectNode args) throws Exception;
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private volatile Invoker invoker = Tool::call;

    public synchronized void register(Tool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null)
            throw new IllegalStateException("Duplicate tool: " + tool.name());
    }

    /** Wrap the current invoker with a new layer (innermost registered first). */
    public synchronized void addLayer(Layer layer) {
        Invoker inner = this.invoker;
        this.invoker = (tool, args) -> layer.invoke(tool, args, inner);
    }

    @FunctionalInterface
    public interface Layer {
        ToolResult invoke(Tool tool, ObjectNode args, Invoker next) throws Exception;
    }

    public boolean has(String name) { return tools.containsKey(name); }

    public Collection<Tool> list() { return tools.values(); }

    public ToolResult call(String name, ObjectNode args) {
        Tool tool = tools.get(name);
        if (tool == null) return ToolResult.error("Unknown tool: " + name);
        try {
            return invoker.invoke(tool, args);
        } catch (Exception e) {
            // §6.7.4: engine failures are tool-result errors, written for the LLM to act on
            Log.error("Tool '" + name + "' failed", e);
            return ToolResult.error("Tool '" + name + "' failed: " + e.getMessage()
                    + ". Adjust the arguments and retry, or use a different tool.");
        }
    }
}
