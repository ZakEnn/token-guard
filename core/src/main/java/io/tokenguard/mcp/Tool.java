package io.tokenguard.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface Tool {

    String name();

    String description();

    /** JSON Schema for the tool's arguments (MCP `inputSchema`). */
    ObjectNode inputSchema();

    ToolResult call(ObjectNode args) throws Exception;

    /**
     * Cache TTL class per §6.1.3; NONE means the router never caches this tool
     * (mutating tools, status tools). Wired in M2.
     */
    default CachePolicy cachePolicy() { return CachePolicy.NONE; }

    /**
     * Args-aware refinement (P1.2): tools whose cacheability depends on the
     * call — execute_tool proxies arbitrary downstream tools, only some of
     * which are read-only — override this. Default: the per-tool policy.
     */
    default CachePolicy cachePolicy(ObjectNode args) { return cachePolicy(); }

    /**
     * Tier-2 eligibility (§6.1.1): the free-text query for THIS call, or null
     * when semantic matching must not apply (scripts, paths, regexes, symbol
     * identities are identity-bearing, never fuzzy-matched).
     */
    default String semanticText(ObjectNode args) { return null; }

    /** Arg fields excluded from the identity key because semanticText covers them. */
    default java.util.List<String> semanticFields() { return java.util.List.of(); }

    enum CachePolicy {
        NONE(0),
        STRUCTURE_QUERY(7 * 24 * 3600),
        SEARCH_RESULT(24 * 3600),
        SCRIPT_RESULT(3600);

        public final int ttlSeconds;
        CachePolicy(int ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }
}
