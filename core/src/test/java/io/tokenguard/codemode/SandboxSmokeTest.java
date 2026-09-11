package io.tokenguard.codemode;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M0 step 2: prove Graal.js runs closed-by-default (full sandbox arrives in M5, §6.4). */
class SandboxSmokeTest {

    private Context closedContext() {
        return Context.newBuilder("js")
                .allowAllAccess(false)
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    @Test
    void evaluatesPureJs() {
        try (Context ctx = closedContext()) {
            Value v = ctx.eval("js", "[1,2,3].map(x => x * 2).reduce((a, b) => a + b, 0)");
            assertEquals(12, v.asInt());
        }
    }

    @Test
    void hostClassAccessIsDenied() {
        try (Context ctx = closedContext()) {
            assertThrows(PolyglotException.class,
                    () -> ctx.eval("js", "Java.type('java.lang.Runtime')"));
        }
    }
}
