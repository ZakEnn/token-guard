package io.tokenguard.codemode;

import io.tokenguard.astindex.QueryEngine;
import io.tokenguard.astindex.WorkspaceIndexer;
import io.tokenguard.cache.DependencyTracker;
import io.tokenguard.util.Hashes;
import io.tokenguard.util.IgnoreRules;
import io.tokenguard.util.PathJail;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The ONLY capabilities scripts have (plan §5.2/§6.4): a read-only,
 * workspace-jailed, secret-filtered view of files plus the structural index.
 * No write, network, process, or environment surface exists here — and the
 * Context itself has no IO capability, so this API is the entire attack
 * surface. Every method re-validates paths independently of the caller.
 */
final class HostApi {

    static final long READ_CAP_BYTES = 512 * 1024;
    static final int GREP_MAX = 200;
    static final int GLOB_MAX = 1000;

    /** §6.4: secrets are unreadable even inside the jail. */
    private static final List<PathMatcher> DENY = Stream.of(
                    ".env*", "**/.env*", "*.pem", "**/*.pem", "*.key", "**/*.key",
                    "id_rsa*", "**/id_rsa*", "*.p12", "**/*.p12", ".git/**", ".git")
            .map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g))
            .toList();

    private final PathJail jail;
    private final IgnoreRules ignore;
    private final WorkspaceIndexer indexer;
    private final QueryEngine queryEngine;
    private final DependencyTracker tracker;

    HostApi(PathJail jail, IgnoreRules ignore, WorkspaceIndexer indexer,
            QueryEngine queryEngine, DependencyTracker tracker) {
        this.jail = jail;
        this.ignore = ignore;
        this.indexer = indexer;
        this.queryEngine = queryEngine;
        this.tracker = tracker;
    }

    ProxyObject fs() {
        return ProxyObject.fromMap(Map.of(
                "read", (ProxyExecutable) this::fsRead,
                "glob", (ProxyExecutable) this::fsGlob,
                "grep", (ProxyExecutable) this::fsGrep,
                "stat", (ProxyExecutable) this::fsStat));
    }

    ProxyObject index() {
        return ProxyObject.fromMap(Map.of(
                "symbol", (ProxyExecutable) a -> indexQuery("symbol", a),
                "callers", (ProxyExecutable) a -> indexQuery("callers", a),
                "callees", (ProxyExecutable) a -> indexQuery("callees", a),
                "outline", (ProxyExecutable) a -> indexQuery("outline", a),
                "search", (ProxyExecutable) a -> indexQuery("search", a)));
    }

    ProxyObject mcp() {
        return ProxyObject.fromMap(Map.of(
                "call", (ProxyExecutable) this::mcpCall));
    }

    // ---- mcp (P1.4: downstream composition inside the sandbox) ----

    /** Dispatches through execute_tool's path so cache/metrics/trace apply. */
    private volatile java.util.function.Function<com.fasterxml.jackson.databind.node.ObjectNode,
            io.tokenguard.mcp.ToolResult> mcpDispatch;

    void setMcpDispatch(java.util.function.Function<com.fasterxml.jackson.databind.node.ObjectNode,
            io.tokenguard.mcp.ToolResult> dispatch) {
        this.mcpDispatch = dispatch;
    }

    /**
     * mcp.call(server, tool, args) — plan §5.2, implemented in P1.4 (gated on
     * P0.4 sandbox limits: statement/CPU/time limits bound how many downstream
     * calls a hijacked script can make). Intermediate downstream results
     * composed here never enter model context (KC-2).
     */
    private Object mcpCall(Value... args) {
        var dispatch = mcpDispatch;
        if (dispatch == null)
            throw new HostError("mcp.call unavailable: no downstream MCP servers are "
                    + "configured. Add [servers.*] blocks to ~/.token-guard/config.toml "
                    + "(or run token-guard import-servers).");
        String server = str(args, 0, "mcp.call(server, tool, args)");
        String tool = str(args, 1, "mcp.call(server, tool, args)");
        com.fasterxml.jackson.databind.node.ObjectNode wrapped = io.tokenguard.util.Json.obj();
        wrapped.put("name", server + "/" + tool);
        wrapped.set("arguments", args.length > 2 && args[2] != null && args[2].hasMembers()
                ? valueToJson(args[2]) : io.tokenguard.util.Json.obj());
        io.tokenguard.mcp.ToolResult r = dispatch.apply(wrapped);
        if (r.isError()) throw new HostError(r.text());
        String text = r.text();
        if (text.length() > READ_CAP_BYTES)
            text = text.substring(0, (int) READ_CAP_BYTES)
                    + "\n[truncated: mcp.call result exceeded 512 KB — narrow the request]";
        return text;
    }

    /** Guest Value -> Jackson tree (strings/numbers/booleans/arrays/objects). */
    private static com.fasterxml.jackson.databind.JsonNode valueToJsonNode(Value v) {
        var nf = io.tokenguard.util.Json.MAPPER.getNodeFactory();
        if (v == null || v.isNull()) return nf.nullNode();
        if (v.isBoolean()) return nf.booleanNode(v.asBoolean());
        if (v.isNumber()) return v.fitsInLong() ? nf.numberNode(v.asLong())
                : nf.numberNode(v.asDouble());
        if (v.isString()) return nf.textNode(v.asString());
        if (v.hasArrayElements()) {
            var arr = io.tokenguard.util.Json.arr();
            for (long i = 0; i < v.getArraySize(); i++)
                arr.add(valueToJsonNode(v.getArrayElement(i)));
            return arr;
        }
        if (v.hasMembers()) return valueToJson(v);
        return nf.textNode(v.toString());
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode valueToJson(Value v) {
        com.fasterxml.jackson.databind.node.ObjectNode obj = io.tokenguard.util.Json.obj();
        for (String key : v.getMemberKeys())
            obj.set(key, valueToJsonNode(v.getMember(key)));
        return obj;
    }

    // ---- fs ----

    private Object fsRead(Value... args) {
        Path file = checkedPath(str(args, 0, "fs.read(path)"));
        try {
            if (!Files.isRegularFile(file)) throw new HostError("Not a file: " + rel(file));
            if (Files.size(file) > READ_CAP_BYTES)
                throw new HostError("fs.read cap is 512 KB; " + rel(file)
                        + " is larger. Use startLine/endLine or fs.grep to narrow.");
            byte[] bytes = Files.readAllBytes(file);
            tracker.recordFile(rel(file), Hashes.sha256(bytes));
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (args.length > 1 && args[1].hasMembers()) {
                int start = member(args[1], "startLine", 1);
                int end = member(args[1], "endLine", Integer.MAX_VALUE);
                List<String> lines = content.lines().toList();
                int from = Math.clamp(start - 1, 0, lines.size());
                int to = Math.clamp(end, from, lines.size());
                content = String.join("\n", lines.subList(from, to));
            }
            return content;
        } catch (IOException e) {
            throw new HostError("fs.read failed: " + e.getMessage());
        }
    }

    private Object fsGlob(Value... args) {
        String pattern = str(args, 0, "fs.glob(pattern)");
        var matcher = io.tokenguard.util.Globs.matcher(pattern);
        List<Object> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(jail.root())) {
            s.filter(Files::isRegularFile)
             .map(p -> jail.root().relativize(p))
             .filter(p -> !ignore.ignored(p) && !denied(p))
             .filter(matcher)
             .limit(GLOB_MAX)
             .forEach(p -> out.add(p.toString().replace('\\', '/')));
        } catch (IOException e) {
            throw new HostError("fs.glob failed: " + e.getMessage());
        }
        return ProxyArray.fromList(out);
    }

    private Object fsGrep(Value... args) {
        Pattern pattern = Pattern.compile(str(args, 0, "fs.grep(pattern)"));
        java.util.function.Predicate<Path> globMatcher = null;
        int max = GREP_MAX;
        if (args.length > 1 && args[1].hasMembers()) {
            Value opts = args[1];
            if (opts.hasMember("glob"))
                globMatcher = io.tokenguard.util.Globs.matcher(opts.getMember("glob").asString());
            max = Math.clamp(member(opts, "maxResults", GREP_MAX), 1, GREP_MAX);
        }
        List<Object> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(jail.root())) {
            List<Path> files = s.filter(Files::isRegularFile)
                    .filter(p -> {
                        Path r = jail.root().relativize(p);
                        return !ignore.ignored(r) && !denied(r);
                    })
                    .toList();
            for (Path file : files) {
                if (out.size() >= max) break;
                Path relPath = jail.root().relativize(file);
                if (globMatcher != null && !globMatcher.test(relPath)) continue;
                try {
                    if (Files.size(file) > READ_CAP_BYTES * 4) continue;
                    byte[] bytes = Files.readAllBytes(file);
                    if (looksBinary(bytes)) continue;
                    List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
                    boolean matched = false;
                    for (int i = 0; i < lines.size() && out.size() < max; i++) {
                        if (!pattern.matcher(lines.get(i)).find()) continue;
                        matched = true;
                        out.add(ProxyObject.fromMap(Map.of(
                                "path", relPath.toString().replace('\\', '/'),
                                "line", i + 1,
                                "text", lines.get(i))));
                    }
                    if (matched)
                        tracker.recordFile(relPath.toString().replace('\\', '/'),
                                Hashes.sha256(bytes));
                } catch (IOException skip) {
                }
            }
        } catch (IOException e) {
            throw new HostError("fs.grep failed: " + e.getMessage());
        }
        return ProxyArray.fromList(out);
    }

    private Object fsStat(Value... args) {
        Path file = checkedPath(str(args, 0, "fs.stat(path)"));
        try {
            return ProxyObject.fromMap(Map.of(
                    "size", Files.size(file),
                    "mtime", Files.getLastModifiedTime(file).toMillis(),
                    "isDirectory", Files.isDirectory(file)));
        } catch (IOException e) {
            throw new HostError("fs.stat failed: " + e.getMessage());
        }
    }

    // ---- index ----

    private Object indexQuery(String type, Value... args) {
        String target = str(args, 0, "index." + type + "(target)");
        indexer.refreshIfStale();
        QueryEngine.Answer answer = queryEngine.query(type, target, 1);
        answer.deps().forEach(tracker::recordFile);
        return answer.text();
    }

    // ---- shared guards ----

    private Path checkedPath(String supplied) {
        Path real;
        try {
            real = jail.resolve(supplied);
        } catch (Exception e) {
            throw new HostError("Access denied: " + supplied + " is outside the workspace.");
        }
        if (denied(jail.root().relativize(real)))
            throw new HostError("Access denied: " + supplied + " matches the secret-file policy.");
        return real;
    }

    private String rel(Path real) {
        return jail.relativize(real);
    }

    private static boolean denied(Path rel) {
        for (PathMatcher m : DENY) {
            if (m.matches(rel)) return true;
        }
        return false;
    }

    private static boolean looksBinary(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    private static String str(Value[] args, int i, String signature) {
        if (args.length <= i || !args[i].isString())
            throw new HostError("Usage: " + signature + " — argument " + (i + 1)
                    + " must be a string.");
        return args[i].asString();
    }

    private static int member(Value obj, String name, int dflt) {
        return obj.hasMember(name) && obj.getMember(name).isNumber()
                ? obj.getMember(name).asInt() : dflt;
    }

    /** Guest-visible failure: surfaces as a JS exception the script can catch. */
    static final class HostError extends RuntimeException {
        HostError(String msg) { super(msg); }
    }
}
