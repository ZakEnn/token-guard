package io.tokenguard.codemode;

import io.tokenguard.astindex.QueryEngine;
import io.tokenguard.astindex.WorkspaceIndexer;
import io.tokenguard.cache.DependencyTracker;
import io.tokenguard.store.Database;
import io.tokenguard.util.IgnoreRules;
import io.tokenguard.util.PathJail;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** M5 gate: red-team escape suite + functionality (plan §7 step 11, §6.4). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeModeTest {

    @TempDir static Path workspace;
    @TempDir static Path dataDir;
    static Database db;
    static ScriptSandbox sandbox;

    @BeforeAll
    static void setUp() throws Exception {
        Path pkg = workspace.resolve("src/main/java/com/acme");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Svc.java"), """
                package com.acme;
                public class Svc {
                    public int add(int a, int b) { return a + b; }
                    public int twice(int a) { return add(a, a); }
                }
                """);
        Files.writeString(workspace.resolve("notes.txt"), "alpha\nbeta\ngamma\n");
        Files.writeString(workspace.resolve(".env"), "SECRET_TOKEN=hunter2\n");
        Files.writeString(workspace.resolve("server.pem"), "-----BEGIN PRIVATE KEY-----\n");

        db = Database.open(dataDir.resolve("guard.db"));
        PathJail jail = new PathJail(workspace);
        IgnoreRules ignore = new IgnoreRules(workspace);
        WorkspaceIndexer indexer = new WorkspaceIndexer(db, jail, ignore, "ws");
        indexer.refresh();
        sandbox = ScriptSandbox.create(jail, ignore, indexer, new QueryEngine(db, "ws"),
                new DependencyTracker(), 2000, 2000, 2_000_000, "256MB");
    }

    @AfterAll
    static void tearDown() {
        sandbox.close();
        db.close();
    }

    // ---- escape attempts ----

    @Test
    void hostClassAccessDenied() {
        ScriptSandbox.Outcome o = sandbox.run("Java.type('java.lang.Runtime').getRuntime()", 0);
        assertFalse(o.ok());
        assertFalse(o.engineFault(), "escape attempt is a script error, not an engine fault");
    }

    @Test
    void pathTraversalDenied() {
        ScriptSandbox.Outcome o = sandbox.run("fs.read('../../etc/passwd')", 0);
        assertFalse(o.ok());
        assertTrue(o.error().contains("outside the workspace"), o.error());
    }

    @Test
    void absolutePathOutsideWorkspaceDenied() {
        ScriptSandbox.Outcome o = sandbox.run("fs.read('C:/Windows/system.ini')", 0);
        assertFalse(o.ok());
    }

    @Test
    void secretFilesDeniedEvenInsideJail() {
        ScriptSandbox.Outcome env = sandbox.run("fs.read('.env')", 0);
        assertFalse(env.ok());
        assertTrue(env.error().contains("secret-file policy"), env.error());
        ScriptSandbox.Outcome pem = sandbox.run("fs.read('server.pem')", 0);
        assertFalse(pem.ok());
        ScriptSandbox.Outcome glob = sandbox.run("fs.glob('**/*')", 0);
        assertTrue(glob.ok());
        assertFalse(glob.value().contains(".env"), "secrets must not be listable");
        assertFalse(glob.value().contains("server.pem"));
    }

    @Test
    void busyLoopIsKilledByWatchdog() {
        long t0 = System.currentTimeMillis();
        ScriptSandbox.Outcome o = sandbox.run("while(true){}", 500);
        long elapsed = System.currentTimeMillis() - t0;
        assertFalse(o.ok());
        assertTrue(o.error().contains("time limit") || o.error().contains("statement limit"),
                o.error());
        assertTrue(elapsed < 10_000, "kill must be prompt, took " + elapsed + " ms");
        assertFalse(o.engineFault(), "resource kill must not trip the circuit breaker");
    }

    @Test
    void limitsModeIsReportedAndWatchdogAlwaysActive() {
        // P0.4: on Oracle GraalVM the probe enables sandbox options; on this
        // community stack it must fall back. Either way the watchdog is on.
        assertTrue(sandbox.limitsMode().contains("watchdog"), sandbox.limitsMode());
    }

    @Test
    void allocationLoopIsKilledPromptlyWithoutHostOom() {
        // P0.4 red-team: allocation-heavy spin. Under sandbox-options the heap
        // cap fires; under watchdog-only the 500 ms kill fires before the
        // small per-iteration allocations threaten the host heap.
        long t0 = System.currentTimeMillis();
        ScriptSandbox.Outcome o = sandbox.run(
                "const a = []; while (true) a.push('x'.repeat(1024));", 500);
        long elapsed = System.currentTimeMillis() - t0;
        assertFalse(o.ok());
        assertTrue(o.error().contains("limit"), o.error());
        assertTrue(elapsed < 10_000, "kill must be prompt, took " + elapsed + " ms");
        assertFalse(o.engineFault(), "resource kill must not trip the circuit breaker");
    }

    @Test
    void cpuSpinIsKilledByConfiguredEnforcement() {
        // P0.4 red-team: pure CPU spin (no allocation). sandbox-options mode
        // kills via MaxCPUTime; watchdog-only mode via statement limit/timer.
        ScriptSandbox.Outcome o = sandbox.run("let x = 0; while (true) { x++; }", 500);
        assertFalse(o.ok());
        assertTrue(o.error().contains("limit"), o.error());
        assertFalse(o.engineFault());
    }

    @Test
    void oversizedReturnIsTruncatedWithLabel() {
        ScriptSandbox.Outcome o = sandbox.run("'x'.repeat(1000000)", 0);
        assertTrue(o.ok());
        assertTrue(o.value().length() < 70_000);
        assertTrue(o.value().contains("[truncated:"));
    }

    @Test
    void noNetworkOrProcessPrimitivesExist() {
        for (String probe : new String[]{"fetch('http://x')", "new XMLHttpRequest()",
                "process.exit(1)", "require('fs')", "load('nashorn:mozilla_compat.js')"}) {
            ScriptSandbox.Outcome o = sandbox.run(probe, 0);
            assertFalse(o.ok(), probe + " must fail");
        }
    }

    @Test
    void sandboxSurvivesAndIsolatesAcrossRuns() {
        sandbox.run("globalThis.leak = 'polluted'", 0);
        ScriptSandbox.Outcome o = sandbox.run("typeof leak", 0);
        assertTrue(o.ok());
        assertEquals("undefined", o.value(), "fresh Context per run — no shared state (§6.4)");
    }

    // ---- functionality ----

    @Test
    void scriptComposesGrepReadAndReturnsSlice() {
        ScriptSandbox.Outcome o = sandbox.run("""
                const hits = fs.grep('beta', {glob: '**/*.txt'});
                const line = fs.read(hits[0].path, {startLine: hits[0].line, endLine: hits[0].line});
                ({file: hits[0].path, content: line})
                """, 0);
        assertTrue(o.ok(), () -> o.error());
        assertTrue(o.value().contains("notes.txt"));
        assertTrue(o.value().contains("beta"));
    }

    @Test
    void scriptUsesStructuralIndex() {
        ScriptSandbox.Outcome o = sandbox.run("index.callers('add')", 0);
        assertTrue(o.ok(), () -> o.error());
        assertTrue(o.value().contains("Svc#twice"), o.value());
    }

    @Test
    void scriptErrorsCarryLineInfo() {
        ScriptSandbox.Outcome o = sandbox.run("const a = 1;\nnope.deref();", 0);
        assertFalse(o.ok());
        assertTrue(o.error().contains("line 2"), o.error());
    }
}
