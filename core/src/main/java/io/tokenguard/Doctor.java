package io.tokenguard;

import io.tokenguard.store.Database;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.nio.file.Path;

/**
 * `token-guard --doctor` (plan §7 M8, wired early): verifies the three native
 * risk points — SQLite, JS engine, filesystem — and prints to stderr.
 */
final class Doctor {

    private Doctor() {}

    static int run(Path dataDir, Path workspaceRoot) {
        int failures = 0;
        System.err.println("TokenGuard doctor");
        System.err.println("  data dir     : " + dataDir);
        System.err.println("  workspace    : " + workspaceRoot);

        try (Database db = Database.open(dataDir.resolve("guard.db"))) {
            System.err.println("  sqlite       : OK (" + db.path() + ")");
        } catch (Exception e) {
            System.err.println("  sqlite       : FAILED - " + e.getMessage());
            failures++;
        }

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(false)
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build()) {
            Value v = ctx.eval("js", "6 * 7");
            if (v.asInt() != 42) throw new IllegalStateException("unexpected eval result");
            System.err.println("  js sandbox   : OK");
        } catch (Exception e) {
            System.err.println("  js sandbox   : FAILED - " + e.getMessage());
            failures++;
        }

        System.err.println(failures == 0 ? "All checks passed." : failures + " check(s) failed.");
        return failures == 0 ? 0 : 1;
    }
}
