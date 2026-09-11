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

class ReadFileToolTest {

    @TempDir Path workspace;
    @TempDir Path dataDir;
    DistillFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new DistillFixture(workspace, dataDir);
    }

    @AfterEach
    void tearDown() { fx.close(); }

    @Test
    void firstReadReturnsNumberedContent() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "alpha\nbeta\ngamma\n");
        ToolResult r = fx.readFile.call(args("path", "a.txt"));
        assertFalse(r.isError());
        assertTrue(r.text().contains("1: alpha"));
        assertTrue(r.text().contains("3: gamma"));
    }

    @Test
    void reReadUnchangedReturnsShortMessage() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "alpha\nbeta\n");
        fx.readFile.call(args("path", "a.txt"));
        ToolResult second = fx.readFile.call(args("path", "a.txt"));
        assertTrue(second.text().contains("unchanged"));
        assertFalse(second.text().contains("alpha"), "must not resend content");
    }

    @Test
    void reReadAfterExternalChangeReturnsDiffOnly() throws Exception {
        Path f = workspace.resolve("a.txt");
        Files.writeString(f, "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\n");
        fx.readFile.call(args("path", "a.txt"));
        Files.writeString(f, "line1\nline2\nline3\nline4\nline5\nCHANGED\nline7\nline8\nline9\nline10\n");
        ToolResult second = fx.readFile.call(args("path", "a.txt"));
        assertTrue(second.text().contains("Diff against your last-seen version"));
        assertTrue(second.text().contains("+CHANGED"));
        assertTrue(second.text().contains("-line6"));
        assertFalse(second.text().contains("1: line1"), "must not resend the full file");
    }

    @Test
    void rangeReadReturnsSlice() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "l1\nl2\nl3\nl4\nl5\n");
        ToolResult r = fx.readFile.call(args("path", "a.txt", "start_line", 2, "end_line", 3));
        assertTrue(r.text().contains("2: l2"));
        assertTrue(r.text().contains("3: l3"));
        assertFalse(r.text().contains("4: l4"));
    }

    @Test
    void binaryFileRejected() throws Exception {
        Files.write(workspace.resolve("bin.dat"), new byte[]{1, 2, 0, 3, 4});
        ToolResult r = fx.readFile.call(args("path", "bin.dat"));
        assertTrue(r.isError());
        assertTrue(r.text().contains("Binary"));
    }

    @Test
    void pathEscapeIsBlocked() {
        assertThrows(Exception.class,
                () -> fx.readFile.call(args("path", "../outside.txt")));
    }

    @Test
    void oversizedFullReadIsTruncatedWithLabel() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 2500; i++) big.append("line").append(i).append('\n');
        Files.writeString(workspace.resolve("big.txt"), big.toString());
        ToolResult r = fx.readFile.call(args("path", "big.txt"));
        assertTrue(r.text().contains("[truncated:"));
        assertTrue(r.text().contains("2000: line2000"));
        assertFalse(r.text().contains("2001: line2001"));
    }
}
