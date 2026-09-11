package io.tokenguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** P4.4 DoD slices that are testable headlessly: flags exist and map; bad
 * workspace fails fast with exit 1 instead of a stack trace / hung server. */
class MainCliTest {

    @TempDir Path tmp;

    @Test
    void badWorkspaceFailsFastWithExitOne() {
        int exit = new CommandLine(new Main()).execute(
                "--workspace", tmp.resolve("does-not-exist").toString(),
                "--cache-dir", tmp.resolve("data").toString());
        assertEquals(1, exit, "nonexistent workspace must fail fast, not start the server");
    }

    @Test
    void newFlagsParse() {
        Main main = new Main();
        new CommandLine(main).parseArgs("--no-telemetry", "--log-json",
                "--workspace", tmp.toString());
        assertTrue(main.noTelemetry);
        assertTrue(main.logJson);
    }

    @Test
    void subcommandsAreRegistered() {
        CommandLine cl = new CommandLine(new Main());
        assertTrue(cl.getSubcommands().containsKey("import-servers"));
        assertTrue(cl.getSubcommands().containsKey("install-hooks"));
        assertTrue(cl.getSubcommands().containsKey("uninstall-hooks"));
        assertTrue(cl.getSubcommands().containsKey("hook-gate"));
    }
}
