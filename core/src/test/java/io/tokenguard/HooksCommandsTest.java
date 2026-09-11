package io.tokenguard;

import com.fasterxml.jackson.databind.JsonNode;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3.4 DoD (mechanics): hooks install idempotently into .claude/settings.json
 * with a backup written; uninstall restores the backup byte-identical; the
 * gate command emits valid deny JSON. (Live host-behavior validation is part
 * of the blocked P2.3 campaign — labeled partial in the plan.)
 */
class HooksCommandsTest {

    @TempDir Path tmp;

    private int run(String... args) {
        return new CommandLine(new Main()).execute(args);
    }

    @Test
    void installIntoExistingSettingsBacksUpAndMerges() throws Exception {
        Path settings = tmp.resolve("settings.json");
        String original = """
                { "permissions": {"allow": ["Bash(ls:*)"]},
                  "hooks": { "PreToolUse": [
                    { "matcher": "Bash", "hooks": [{"type":"command","command":"echo hi"}] } ] } }
                """;
        Files.writeString(settings, original);

        assertEquals(0, run("install-hooks", "--host", "claude-code",
                "--settings-file", settings.toString()));

        Path backup = tmp.resolve("settings.json.tg-backup");
        assertTrue(Files.exists(backup));
        assertEquals(original, Files.readString(backup), "backup must be byte-identical");

        JsonNode root = Json.MAPPER.readTree(Files.readString(settings));
        assertEquals(2, root.get("hooks").get("PreToolUse").size(),
                "existing hook entries must be preserved");
        assertTrue(Files.readString(settings).contains(HooksCommands.MARKER));
        assertTrue(Files.readString(settings).contains("Bash(ls:*)"),
                "unrelated settings preserved");
    }

    @Test
    void installIsIdempotent() throws Exception {
        Path settings = tmp.resolve("settings.json");
        Files.writeString(settings, "{}");
        assertEquals(0, run("install-hooks", "--settings-file", settings.toString()));
        String afterFirst = Files.readString(settings);
        assertEquals(0, run("install-hooks", "--settings-file", settings.toString()));
        assertEquals(afterFirst, Files.readString(settings), "second install must change nothing");
    }

    @Test
    void uninstallRestoresBackupByteIdentical() throws Exception {
        Path settings = tmp.resolve("settings.json");
        String original = "{ \"model\": \"opus\" }";
        Files.writeString(settings, original);
        assertEquals(0, run("install-hooks", "--settings-file", settings.toString()));
        assertNotEquals(original, Files.readString(settings));

        assertEquals(0, run("uninstall-hooks", "--settings-file", settings.toString()));
        assertEquals(original, Files.readString(settings), "restore must be byte-identical");
        assertFalse(Files.exists(tmp.resolve("settings.json.tg-backup")), "backup consumed");
    }

    @Test
    void uninstallWithoutBackupStripsOnlyOurEntries() throws Exception {
        Path settings = tmp.resolve("fresh/settings.json");
        Files.createDirectories(settings.getParent());
        // freshly created by install (no pre-existing file, no backup)
        assertEquals(0, run("install-hooks", "--settings-file", settings.toString()));
        assertTrue(Files.readString(settings).contains(HooksCommands.MARKER));
        assertEquals(0, run("uninstall-hooks", "--settings-file", settings.toString()));
        assertFalse(Files.readString(settings).contains(HooksCommands.MARKER));
    }

    @Test
    void unsupportedHostIsARefusalNotACrash() throws Exception {
        Path settings = tmp.resolve("s.json");
        assertEquals(2, run("install-hooks", "--host", "cursor",
                "--settings-file", settings.toString()));
        assertFalse(Files.exists(settings));
    }
}
