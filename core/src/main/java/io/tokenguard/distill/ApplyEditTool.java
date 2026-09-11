package io.tokenguard.distill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.util.Json;
import io.tokenguard.util.PathJail;
import io.tokenguard.util.TokenEstimator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The ONLY mutation path in TokenGuard (§6.4: scripts cannot write; §2.4 of the
 * product context). Responds with the minimal unified diff, never the full
 * file, and every mutation is visible to the user as a diff.
 */
public final class ApplyEditTool implements Tool {

    private final PathJail jail;
    private final FileStateTracker tracker;
    private final DiffService diff;
    private final Metrics metrics;

    public ApplyEditTool(PathJail jail, FileStateTracker tracker, DiffService diff, Metrics metrics) {
        this.jail = jail;
        this.tracker = tracker;
        this.diff = diff;
        this.metrics = metrics;
    }

    @Override public String name() { return "apply_edit"; }

    @Override public String description() {
        return "Edit a workspace file by exact string replacement. Each old_string must occur "
                + "exactly once in the file (include surrounding context to disambiguate). "
                + "To create a new file, pass a single edit with an empty old_string. "
                + "Returns a unified diff of the change — you do not need to re-read the file.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode edit = Json.obj();
        ObjectNode editProps = Json.obj();
        editProps.set("old_string", Json.prop("string", "Exact text to replace (empty to create a new file)"));
        editProps.set("new_string", Json.prop("string", "Replacement text"));
        edit.put("type", "object");
        edit.set("properties", editProps);
        ObjectNode edits = Json.obj();
        edits.put("type", "array");
        edits.put("description", "Edits applied in order");
        edits.set("items", edit);
        ObjectNode props = Json.obj();
        props.set("path", Json.prop("string", "Workspace-relative file path"));
        props.set("edits", edits);
        return Json.schemaObject(props, "path", "edits");
    }

    @Override
    public ToolResult call(ObjectNode args) throws Exception {
        Path file = jail.resolve(Json.str(args, "path", null));
        String rel = jail.relativize(file);
        JsonNode edits = args.get("edits");
        if (edits == null || !edits.isArray() || edits.isEmpty())
            return ToolResult.error("'edits' must be a non-empty array");

        boolean exists = Files.isRegularFile(file);
        String before = exists ? TextFiles.read(file) : "";
        String after = before;

        for (JsonNode e : edits) {
            String oldStr = Json.str(e, "old_string", "");
            String newStr = Json.str(e, "new_string", null);
            if (newStr == null) return ToolResult.error("Edit missing new_string");
            if (oldStr.isEmpty()) {
                if (exists) return ToolResult.error(rel
                        + " already exists; empty old_string is only for file creation");
                after = newStr;
                continue;
            }
            int first = after.indexOf(oldStr);
            if (first < 0)
                return ToolResult.error("old_string not found in " + rel
                        + ". The file may have changed — re-read it (you will get a diff).");
            if (after.indexOf(oldStr, first + 1) >= 0)
                return ToolResult.error("old_string occurs more than once in " + rel
                        + ". Include more surrounding context to make it unique.");
            after = after.substring(0, first) + newStr + after.substring(first + oldStr.length());
        }

        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, after.getBytes(StandardCharsets.UTF_8));
        tracker.record(rel, after);

        String d = exists ? diff.unifiedDiff(rel, before, after)
                : "created " + rel + " (" + after.lines().count() + " lines)";
        String out = d.isEmpty() ? rel + ": no changes (edits were identity)" : "Applied. " + d;
        metrics.tokens(name(), TokenEstimator.estimate(after), TokenEstimator.estimate(out));
        return ToolResult.text(out);
    }
}
