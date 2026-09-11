package io.tokenguard.codemode;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.mcp.Tool;
import io.tokenguard.mcp.ToolResult;
import io.tokenguard.util.Json;

import java.util.List;

/**
 * search_api (Engine 3, plan §5.1 code-mode profile): replaces dozens of tool
 * schemas with on-demand documentation — the agent asks for what it needs,
 * paying tokens only for the relevant entries.
 */
public final class SearchApiTool implements Tool {

    private record Entry(String signature, String doc, String example, String keywords) {}

    private static final List<Entry> CATALOG = List.of(
        new Entry("fs.read(path, {startLine?, endLine?}) -> string",
                "Read a workspace file (UTF-8, max 512 KB), optionally a line range.",
                "fs.read('src/main/java/App.java', {startLine: 10, endLine: 40})",
                "read file content lines open cat view source"),
        new Entry("fs.glob(pattern) -> string[]",
                "List workspace files matching a glob (gitignore-aware, max 1000).",
                "fs.glob('**/*.java')",
                "list files find glob directory ls enumerate paths"),
        new Entry("fs.grep(pattern, {glob?, maxResults?}) -> {path,line,text}[]",
                "Regex-search file contents; returns matching lines with locations (max 200).",
                "fs.grep('TODO|FIXME', {glob: '**/*.java', maxResults: 50})",
                "search grep regex find text content match locate"),
        new Entry("fs.stat(path) -> {size, mtime, isDirectory}",
                "File metadata without reading content.",
                "fs.stat('pom.xml').size",
                "stat size modified time metadata exists"),
        new Entry("index.symbol(name) -> string",
                "Signature, location and doc of a class/method/field (Java index).",
                "index.symbol('OrderService#findByStatus')",
                "symbol signature definition declaration class method lookup"),
        new Entry("index.callers(name) -> string",
                "Who calls a method — cross-file, from the structural index.",
                "index.callers('findByStatus')",
                "callers usages references who calls invocations"),
        new Entry("index.callees(name) -> string",
                "What a method calls.",
                "index.callees('com.acme.OrderService#run()')",
                "callees calls outgoing dependencies of method"),
        new Entry("index.outline(path) -> string",
                "Compact structural outline of a Java file: types, members, line ranges.",
                "index.outline('src/main/java/com/acme/OrderService.java')",
                "outline structure skeleton members overview file summary"),
        new Entry("index.search(query) -> string",
                "Fuzzy symbol-name search across the codebase.",
                "index.search('order')",
                "search symbols fuzzy find class name discover"),
        new Entry("mcp.call(server, tool, args) -> string",
                "Invoke a downstream MCP tool (P1.4); compose results in-script so "
                        + "intermediate data never enters model context. Read-style calls "
                        + "are cached. Requires configured [servers.*].",
                "mcp.call('github', 'list_issues', {repo: 'acme/app', state: 'open'})",
                "mcp downstream proxy tool invoke compose external server call"));

    @Override public String name() { return "search_api"; }

    @Override public String description() {
        return "Search the documentation of the script APIs available in execute_script. "
                + "Call this first to discover the right functions, then write a script.";
    }

    @Override public ObjectNode inputSchema() {
        ObjectNode props = Json.obj();
        props.set("query", Json.prop("string",
                "What you want to do, e.g. 'find files containing a string'"));
        return Json.schemaObject(props, "query");
    }

    @Override public CachePolicy cachePolicy() { return CachePolicy.SEARCH_RESULT; }

    @Override public String semanticText(ObjectNode args) { return Json.str(args, "query", null); }

    @Override public List<String> semanticFields() { return List.of("query"); }

    @Override
    public ToolResult call(ObjectNode args) {
        String query = Json.str(args, "query", "").toLowerCase();
        StringBuilder sb = new StringBuilder();
        List<Entry> ranked = CATALOG.stream()
                .map(e -> new Object[]{e, score(e, query)})
                .sorted((a, b) -> Integer.compare((int) b[1], (int) a[1]))
                .filter(p -> (int) p[1] > 0 || query.isBlank())
                .limit(5)
                .map(p -> (Entry) p[0])
                .toList();
        if (ranked.isEmpty()) ranked = CATALOG;
        sb.append("Script API (use with execute_script; read-only, workspace-jailed):\n");
        for (Entry e : ranked) {
            sb.append("\n").append(e.signature()).append('\n')
              .append("  ").append(e.doc()).append('\n')
              .append("  e.g. ").append(e.example()).append('\n');
        }
        return ToolResult.text(sb.toString());
    }

    private static int score(Entry e, String query) {
        int s = 0;
        for (String word : query.split("\\W+")) {
            if (word.isEmpty()) continue;
            if (e.keywords().contains(word)) s += 2;
            if (e.signature().toLowerCase().contains(word)) s += 1;
        }
        return s;
    }
}
