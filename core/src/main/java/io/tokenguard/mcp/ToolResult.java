package io.tokenguard.mcp;

import io.tokenguard.util.TokenEstimator;

/**
 * Result of a tool invocation. Engine failures surface here as isError=true
 * tool results, never as JSON-RPC protocol errors (§6.7.4 — protocol errors
 * make some hosts drop the server).
 *
 * rawTokens = estimated cost of the naive/unoptimized equivalent output (A6);
 * the cache layer persists the difference as an entry's value (§6.1.3).
 */
public record ToolResult(String text, boolean isError, long rawTokens) {

    public static ToolResult text(String text) {
        return new ToolResult(text, false, TokenEstimator.estimate(text));
    }

    public static ToolResult text(String text, long rawTokens) {
        return new ToolResult(text, false, rawTokens);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true, 0);
    }
}
