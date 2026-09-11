package io.tokenguard.mcp;

import io.tokenguard.astindex.QueryCodebaseStructureTool;
import io.tokenguard.codemode.SearchApiTool;
import io.tokenguard.distill.ApplyEditTool;
import io.tokenguard.distill.ListDirectoryTool;
import io.tokenguard.distill.ReadFileTool;
import io.tokenguard.distill.SearchFilesTool;
import io.tokenguard.telemetry.GuardStatusTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0.3 (enhancement plan): reviewed-fixture snapshot of tool descriptions —
 * the "soft interception" surface (PLATFORM_AGNOSTIC_PLAN §1.4 Layer 3).
 * Exact-match on purpose: a description change is a product decision and must
 * come through this reviewed fixture. Descriptions state concrete optimization
 * behavior but no benchmark percentages (those wait for P2.1).
 *
 * description() reads no constructor state, so tools are built with nulls.
 */
class ToolDescriptionSnapshotTest {

    @Test
    void descriptionsMatchReviewedFixtures() {
        assertEquals("Token-optimized file reading — PREFER this over generic file reads. "
                        + "Re-reading a file you have already seen never returns the full content "
                        + "again: unchanged files return a one-line notice, changed files return "
                        + "only a unified diff of what changed. "
                        + "Use start_line/end_line to read a specific range.",
                new ReadFileTool(null, null, null, null, () -> true).description());

        assertEquals("Search workspace file contents with a regex. Returns only the matching lines "
                        + "grouped by file with line numbers, hard-capped to stay small — use this "
                        + "instead of reading files to locate code. Repeated identical searches are "
                        + "served from cache at no recomputation cost.",
                new SearchFilesTool(null, null, null, null).description());

        assertEquals("List workspace files as a compact indented tree — far smaller than raw "
                        + "directory dumps (gitignore-aware, noise directories excluded, output "
                        + "capped). Use depth to control recursion.",
                new ListDirectoryTool(null, null, null).description());

        assertEquals("Query the codebase's structure instead of reading files: class/method outlines, "
                        + "signatures, callers/callees, dependencies, type hierarchy, symbol search. "
                        + "An outline is a small fraction of the size of the file it summarizes — "
                        + "PREFER this over reading whole files to understand code. Java files only.",
                new QueryCodebaseStructureTool(null, null, null, null).description());

        // Unchanged descriptions, pinned so drift is deliberate:
        assertTrue(new ApplyEditTool(null, null, null, null).description()
                .contains("you do not need to re-read the file"));
        assertTrue(new GuardStatusTool("0").description().contains("tokens saved"));
        assertTrue(new SearchApiTool().description().contains("execute_script"));
    }

    @Test
    void noUnpublishedPercentagesInDescriptions(){
        // P0.3 DoD: no percentage claims before the P2.1 benchmark exists.
        Tool[] tools = {
                new ReadFileTool(null, null, null, null, () -> true),
                new SearchFilesTool(null, null, null, null),
                new ListDirectoryTool(null, null, null),
                new QueryCodebaseStructureTool(null, null, null, null),
                new ApplyEditTool(null, null, null, null),
                new GuardStatusTool("0"),
                new SearchApiTool()};
        for (Tool t : tools)
            assertTrue(!t.description().matches(".*\\d+\\s?%.*"),
                    t.name() + " must not claim unbenchmarked percentages");
    }
}
