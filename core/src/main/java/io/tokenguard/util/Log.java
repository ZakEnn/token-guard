package io.tokenguard.util;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * All logging goes to stderr: stdout is reserved exclusively for MCP protocol
 * frames (plan §1.3 — a single stray byte on stdout corrupts the stdio channel).
 */
public final class Log {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final PrintStream ERR = System.err;
    private static volatile boolean debug = false;
    private static volatile boolean json = false;   // P4.4: --log-json

    private Log() {}

    public static void setDebug(boolean on) { debug = on; }
    public static void setJson(boolean on) { json = on; }

    public static void debug(String msg) { if (debug) write("DEBUG", msg, null); }
    public static void info(String msg)  { write("INFO", msg, null); }
    public static void warn(String msg)  { write("WARN", msg, null); }
    public static void error(String msg, Throwable t) { write("ERROR", msg, t); }

    private static void write(String level, String msg, Throwable t) {
        if (json) {
            // hand-built JSON (tree-model discipline would drag Jackson into
            // every log call); escape the two characters that can break framing
            String esc = msg.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n");
            ERR.println("{\"ts\":\"" + TS.format(LocalTime.now()) + "\",\"level\":\"" + level
                    + "\",\"msg\":\"" + esc + "\""
                    + (t != null ? ",\"exception\":\"" + t.getClass().getName() + ": "
                        + String.valueOf(t.getMessage()).replace("\"", "\\\"") + "\"" : "")
                    + "}");
            return;
        }
        ERR.println("[token-guard " + TS.format(LocalTime.now()) + " " + level + "] " + msg);
        if (t != null) t.printStackTrace(ERR);
    }
}
