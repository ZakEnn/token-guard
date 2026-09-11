package io.tokenguard.distill;

import io.tokenguard.mcp.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.tokenguard.distill.DistillFixture.args;
import static org.junit.jupiter.api.Assertions.*;

class ListAndSearchToolTest {

    @TempDir Path workspace;
    @TempDir Path dataDir;
    DistillFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(workspace.resolve("src/app"));
        Files.createDirectories(workspace.resolve("node_modules/lib"));
        Files.createDirectories(workspace.resolve(".git/objects"));
        Files.writeString(workspace.resolve("src/app/Main.java"), "public class Main {}\n");
        Files.writeString(workspace.resolve("src/app/Util.java"), "public class Util {}\n");
        Files.writeString(workspace.resolve("node_modules/lib/x.js"), "junk\n");
        Files.writeString(workspace.resolve("secret.log"), "public class Hidden {}\n");
        Files.writeString(workspace.resolve(".gitignore"), "*.log\n");
        fx = new DistillFixture(workspace, dataDir);
    }

    @AfterEach
    void tearDown() { fx.close(); }

    @Test
    void listDirectoryHonorsIgnores() throws Exception {
        ToolResult r = fx.listDirectory.call(args("depth", 3));
        assertFalse(r.isError());
        assertTrue(r.text().contains("Main.java"));
        assertFalse(r.text().contains("node_modules"), "builtin ignore");
        assertFalse(r.text().contains(".git/"), "builtin ignore (dirs render with slash)");
        assertFalse(r.text().contains("secret.log"), ".gitignore pattern");
    }

    @Test
    void searchGroupsMatchesByFile() throws Exception {
        ToolResult r = fx.searchFiles.call(args("pattern", "public class"));
        assertFalse(r.isError());
        assertTrue(r.text().contains("src/app/Main.java"));
        assertTrue(r.text().contains("src/app/Util.java"));
        assertFalse(r.text().contains("secret.log"), "ignored files not searched");
        assertTrue(r.text().contains("1:"));
    }

    @Test
    void searchRespectsGlobAndMaxResults() throws Exception {
        for (int i = 0; i < 30; i++) {
            Files.writeString(workspace.resolve("src/f" + i + ".txt"), "needle\nneedle\nneedle\n");
        }
        ToolResult r = fx.searchFiles.call(args("pattern", "needle", "max_results", 10));
        assertTrue(r.text().contains("[truncated at 10"));
        ToolResult globbed = fx.searchFiles.call(
                args("pattern", "class", "glob", "**/*.java"));
        assertTrue(globbed.text().contains("Main.java"));
        assertFalse(globbed.text().contains(".txt"));
    }

    @Test
    void invalidRegexIsToolError() throws Exception {
        ToolResult r = fx.searchFiles.call(args("pattern", "[unclosed"));
        assertTrue(r.isError());
        assertTrue(r.text().contains("Invalid regex"));
    }
}
