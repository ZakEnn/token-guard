package io.tokenguard.proxy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.cache.Embedder;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.mcp.ToolRouter;
import io.tokenguard.util.Json;

/**
 * P1.2: how downstream capability is exposed upstream.
 *
 * - "facade" (default): 3 fixed tools (search_tools / describe_tools /
 *   execute_tool) — context footprint constant regardless of downstream size
 *   (PLATFORM_AGNOSTIC_PLAN §1.4 amendment; supersedes plan-§5.1 ds__
 *   namespacing as the default).
 * - "namespaced": compatibility mode — every downstream tool re-registered as
 *   ds__<server>__<tool>. Requires the registry to be STARTED first (tool
 *   names must be known at registration), so GuardWiring starts it
 *   synchronously in this mode only — a documented tradeoff of the opt-in.
 */
public final class ProxyWiring {

    private ProxyWiring() {}

    public static void registerFacade(ToolRouter router, DownstreamRegistry registry,
                                      Embedder embedder) {
        router.register(new SearchToolsTool(registry, embedder));
        router.register(new DescribeToolsTool(registry));
        router.register(new ExecuteToolTool(registry));
    }

    public static void registerNamespaced(ToolRouter router, DownstreamRegistry registry) {
        for (DownstreamRegistry.QualifiedTool qt : registry.allTools())
            router.register(namespaced(registry, qt));
    }

    static Tool namespaced(DownstreamRegistry registry, DownstreamRegistry.QualifiedTool qt) {
        String name = "ds__" + qt.server() + "__" + qt.tool().name();
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() {
                return "[" + qt.server() + "] " + qt.tool().description();
            }
            @Override public ObjectNode inputSchema() { return qt.tool().inputSchema(); }
            @Override public CachePolicy cachePolicy(ObjectNode args) {
                // Same read-verb rule as execute_tool (see ExecuteToolTool)
                ObjectNode probe = Json.obj();
                probe.put("name", qt.server() + "/" + qt.tool().name());
                return new ExecuteToolTool(registry).cachePolicy(probe);
            }
            @Override public ToolResult call(ObjectNode args) {
                try {
                    DownstreamServer.CallResult r = registry.call(qt.server(), qt.tool().name(), args);
                    return r.isError() ? ToolResult.error(r.text()) : ToolResult.text(r.text());
                } catch (DownstreamServer.DownstreamException e) {
                    return ToolResult.error(e.getMessage());
                }
            }
        };
    }
}
