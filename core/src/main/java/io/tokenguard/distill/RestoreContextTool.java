package io.tokenguard.distill;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.util.Json;

/**
 * P3.3: one call rebuilds working state after host context compaction or a
 * restart — which files were read/edited, what failed, what ran last. The
 * summary is deliberately dense (< ~500 tokens by construction of the
 * journal's caps).
 */
public final class RestoreContextTool implements Tool {

    private final SessionJournal journal;

    public RestoreContextTool(SessionJournal journal) {
        this.journal = journal;
    }

    @Override public String name() { return "restore_context"; }

    @Override public String description() {
        return "Rebuild working state after context compaction or a restart: a dense summary "
                + "of the files read and edited in this workspace's recent sessions, recent "
                + "errors, and the last tool calls. Call this FIRST when you have lost track "
                + "of prior work instead of re-reading files.";
    }

    @Override public ObjectNode inputSchema() {
        return Json.schemaObject(Json.obj());
    }

    @Override
    public ToolResult call(ObjectNode args) {
        return ToolResult.text(journal.render());
    }
}
