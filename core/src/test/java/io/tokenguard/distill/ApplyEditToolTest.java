package io.tokenguard.distill;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ApplyEditToolTest {

    @TempDir Path workspace;
    @TempDir Path dataDir;
    DistillFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new DistillFixture(workspace, dataDir);
    }

    @AfterEach
    void tearDown() { fx.close(); }

    private ObjectNode editArgs(String path, String... oldNewPairs) {
        ObjectNode args = Json.obj();
        args.put("path", path);
        ArrayNode edits = Json.arr();
        for (int i = 0; i < oldNewPairs.length; i += 2) {
            ObjectNode e = Json.obj();
            e.put("old_string", oldNewPairs[i]);
            e.put("new_string", oldNewPairs[i + 1]);
            edits.add(e);
        }
        args.set("edits", edits);
        return args;
    }

    @Test
    void editReturnsDiffNotFullFile() throws Exception {
        Path f = workspace.resolve("code.java");
        Files.writeString(f, "int a = 1;\nint b = 2;\nint c = 3;\nint d = 4;\nint e = 5;\n"
                + "int f = 6;\nint g = 7;\nint h = 8;\nint i = 9;\nint j = 10;\n");
        ToolResult r = fx.applyEdit.call(editArgs("code.java", "int e = 5;", "int e = 55;"));
        assertFalse(r.isError());
        assertTrue(r.text().contains("+int e = 55;"));
        assertTrue(r.text().contains("-int e = 5;"));
        assertFalse(r.text().contains("int j = 10;"), "far-away lines must not be included");
        assertTrue(Files.readString(f).contains("int e = 55;"));
    }

    @Test
    void ambiguousOldStringRejected() throws Exception {
        Files.writeString(workspace.resolve("f.txt"), "dup\nother\ndup\n");
        ToolResult r = fx.applyEdit.call(editArgs("f.txt", "dup", "x"));
        assertTrue(r.isError());
        assertTrue(r.text().contains("more than once"));
        assertEquals("dup\nother\ndup\n", Files.readString(workspace.resolve("f.txt")),
                "file must be untouched on error");
    }

    @Test
    void missingOldStringSuggestsReRead() throws Exception {
        Files.writeString(workspace.resolve("f.txt"), "content\n");
        ToolResult r = fx.applyEdit.call(editArgs("f.txt", "nope", "x"));
        assertTrue(r.isError());
        assertTrue(r.text().contains("re-read"));
    }

    @Test
    void emptyOldStringCreatesFile() throws Exception {
        ToolResult r = fx.applyEdit.call(editArgs("newdir/new.txt", "", "hello\nworld\n"));
        assertFalse(r.isError());
        assertTrue(r.text().contains("created newdir/new.txt"));
        assertEquals("hello\nworld\n", Files.readString(workspace.resolve("newdir/new.txt")));
    }

    @Test
    void readAfterOwnEditReportsUnchanged() throws Exception {
        Files.writeString(workspace.resolve("f.txt"), "one\ntwo\n");
        fx.readFile.call(DistillFixture.args("path", "f.txt"));
        fx.applyEdit.call(editArgs("f.txt", "two", "TWO"));
        ToolResult r = fx.readFile.call(DistillFixture.args("path", "f.txt"));
        assertTrue(r.text().contains("unchanged"),
                "agent already saw its own edit via the diff; re-read must not resend");
    }

    @Test
    void escapeOutsideWorkspaceBlocked() {
        assertThrows(Exception.class,
                () -> fx.applyEdit.call(editArgs("../evil.txt", "", "x")));
    }
}
