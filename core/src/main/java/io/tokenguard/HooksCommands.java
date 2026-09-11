package io.tokenguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.util.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * P3.4 (enhancement plan): optional per-host hook pack — enforcement instead
 * of hoping the LLM picks TokenGuard's tools (token-optimizer/context-mode
 * precedent). v1 supports Claude Code (.claude/settings.json).
 *
 * The installed PreToolUse hook runs `token-guard hook-gate`, which prints the
 * deny decision as JSON — routing through a subcommand sidesteps shell-quoting
 * differences across platforms entirely.
 *
 * PARTIAL by design: PreCompact state capture is deferred until the P2.3
 * per-client campaign validates hook behavior on real host versions (the hook
 * response schema is host-version-sensitive).
 */
public final class HooksCommands {

    private HooksCommands() {}

    static final String MARKER = "token-guard hook-gate";
    static final String BACKUP_SUFFIX = ".tg-backup";

    static Path defaultSettings(Path workspace) {
        return workspace.resolve(".claude/settings.json");
    }

    @Command(name = "install-hooks",
            description = "Install TokenGuard enforcement hooks into a host config "
                    + "(v1: --host claude-code). Idempotent; writes a backup first.")
    public static final class Install implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Main parent;

        @Option(names = "--host", description = "Target host (supported: claude-code)")
        String host = "claude-code";

        @Option(names = "--settings-file",
                description = "Override the settings file location (mainly for testing)")
        Path settingsFile;

        @Override
        public Integer call() throws Exception {
            if (!"claude-code".equals(host)) {
                System.err.println("Unsupported --host '" + host + "'. Supported: claude-code");
                return 2;
            }
            Path file = settingsFile != null ? settingsFile
                    : defaultSettings(parent.workspace != null
                        ? parent.workspace.toAbsolutePath().normalize()
                        : Paths.get("").toAbsolutePath().normalize());

            ObjectNode root;
            if (Files.isRegularFile(file)) {
                String original = Files.readString(file);
                if (original.contains(MARKER)) {
                    System.out.println("TokenGuard hooks already installed in " + file);
                    return 0;   // idempotent
                }
                Files.writeString(file.resolveSibling(file.getFileName() + BACKUP_SUFFIX),
                        original, StandardCharsets.UTF_8);
                root = (ObjectNode) Json.MAPPER.readTree(original);
            } else {
                Files.createDirectories(file.getParent());
                root = Json.obj();
            }

            ObjectNode hooks = root.has("hooks") && root.get("hooks").isObject()
                    ? (ObjectNode) root.get("hooks") : Json.obj();
            ArrayNode pre = hooks.has("PreToolUse") && hooks.get("PreToolUse").isArray()
                    ? (ArrayNode) hooks.get("PreToolUse") : Json.arr();

            ObjectNode entry = Json.obj();
            entry.put("matcher", "Read|Grep|Glob");
            ArrayNode inner = Json.arr();
            ObjectNode cmd = Json.obj();
            cmd.put("type", "command");
            cmd.put("command", MARKER);
            inner.add(cmd);
            entry.set("hooks", inner);
            pre.add(entry);

            hooks.set("PreToolUse", pre);
            root.set("hooks", hooks);
            Files.writeString(file,
                    Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            System.out.println("Installed TokenGuard hooks into " + file
                    + " (uninstall-hooks restores the previous state). Requires token-guard "
                    + "on PATH for the hook command.");
            return 0;
        }
    }

    @Command(name = "uninstall-hooks",
            description = "Remove TokenGuard hooks; restores the pre-install backup if present.")
    public static final class Uninstall implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Main parent;

        @Option(names = "--host", description = "Target host (supported: claude-code)")
        String host = "claude-code";

        @Option(names = "--settings-file", description = "Override the settings file location")
        Path settingsFile;

        @Override
        public Integer call() throws Exception {
            Path file = settingsFile != null ? settingsFile
                    : defaultSettings(parent.workspace != null
                        ? parent.workspace.toAbsolutePath().normalize()
                        : Paths.get("").toAbsolutePath().normalize());
            Path backup = file.resolveSibling(file.getFileName() + BACKUP_SUFFIX);
            if (Files.isRegularFile(backup)) {
                Files.writeString(file, Files.readString(backup), StandardCharsets.UTF_8);
                Files.delete(backup);
                System.out.println("Restored " + file + " from backup (byte-identical).");
                return 0;
            }
            if (!Files.isRegularFile(file)) {
                System.out.println("Nothing to uninstall at " + file);
                return 0;
            }
            // no backup: we created the file — strip our entries only
            ObjectNode root = (ObjectNode) Json.MAPPER.readTree(Files.readString(file));
            JsonNode hooks = root.get("hooks");
            if (hooks instanceof ObjectNode h && h.get("PreToolUse") instanceof ArrayNode pre) {
                ArrayNode kept = Json.arr();
                for (JsonNode e : pre) if (!e.toString().contains(MARKER)) kept.add(e);
                if (kept.isEmpty()) h.remove("PreToolUse"); else h.set("PreToolUse", kept);
                if (h.isEmpty()) root.remove("hooks");
            }
            Files.writeString(file,
                    Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            System.out.println("Removed TokenGuard hook entries from " + file);
            return 0;
        }
    }

    /**
     * The hook command itself: Claude Code pipes tool-call JSON to stdin; we
     * emit the deny decision steering the model to TokenGuard's equivalents.
     * Response schema per Claude Code hooks docs; P2.3 must validate against
     * live host versions before this pack is documented for end users.
     */
    @Command(name = "hook-gate", hidden = true,
            description = "PreToolUse gate — prints a deny decision (internal)")
    public static final class HookGate implements Callable<Integer> {
        @Override
        public Integer call() {
            ObjectNode out = Json.obj();
            ObjectNode spec = Json.obj();
            spec.put("hookEventName", "PreToolUse");
            spec.put("permissionDecision", "deny");
            spec.put("permissionDecisionReason",
                    "TokenGuard handles this operation more token-efficiently: use "
                            + "mcp__token-guard__read_file (diffs on re-read), "
                            + "mcp__token-guard__search_files, or "
                            + "mcp__token-guard__list_directory instead.");
            out.set("hookSpecificOutput", spec);
            System.out.println(out.toString());
            return 0;
        }
    }
}
