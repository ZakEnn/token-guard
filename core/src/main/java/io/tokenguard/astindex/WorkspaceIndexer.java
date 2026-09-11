package io.tokenguard.astindex;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.tokenguard.store.Database;
import io.tokenguard.util.Hashes;
import io.tokenguard.util.IgnoreRules;
import io.tokenguard.util.Log;
import io.tokenguard.util.PathJail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Engine 2 indexer (plan §1.3): incremental, hash-diff driven — only files
 * whose (mtime,size) or content hash changed are reparsed. Parse failures are
 * per-file, never per-index (§6.7.3): a file that breaks the parser is
 * expected input (mid-edit code), recorded as failed/partial and skipped.
 */
public final class WorkspaceIndexer {

    public record Stats(int ok, int partial, int failed, long symbols, long lastRefreshMs) {}

    private static final long REFRESH_DEBOUNCE_MS = 2000;

    private final Database db;
    private final PathJail jail;
    private final IgnoreRules ignore;
    private final String workspaceId;
    private final JavaParser parser;
    private volatile long lastRefreshAt = 0;
    private volatile long lastRefreshDuration = 0;

    public WorkspaceIndexer(Database db, PathJail jail, IgnoreRules ignore, String workspaceId) {
        this.db = db;
        this.jail = jail;
        this.ignore = ignore;
        this.workspaceId = workspaceId;

        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());
        for (Path root : discoverSourceRoots()) {
            try {
                solver.add(new JavaParserTypeSolver(root));
            } catch (Exception e) {
                Log.debug("type solver skipped root " + root + ": " + e.getMessage());
            }
        }
        ParserConfiguration cfg = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(solver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        this.parser = new JavaParser(cfg);
    }

    /** Conventional Maven/Gradle package roots; falls back to the workspace root. */
    private List<Path> discoverSourceRoots() {
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> s = Files.walk(jail.root(), 6)) {
            s.filter(Files::isDirectory)
             .filter(p -> !ignore.ignored(jail.root().relativize(p)))
             .filter(p -> p.endsWith(Path.of("src", "main", "java"))
                     || p.endsWith(Path.of("src", "test", "java")))
             .forEach(roots::add);
        } catch (IOException e) {
            Log.debug("source root discovery failed: " + e.getMessage());
        }
        if (roots.isEmpty()) roots.add(jail.root());
        return roots;
    }

    /** Debounced hash-diff refresh; safe to call before every query (§1.4). */
    public synchronized void refreshIfStale() {
        if (System.currentTimeMillis() - lastRefreshAt < REFRESH_DEBOUNCE_MS) return;
        refresh();
    }

    public synchronized Stats refresh() {
        long t0 = System.currentTimeMillis();
        Map<String, long[]> known = loadKnown(); // path -> [id, mtime, size]
        Set<String> seen = new HashSet<>();
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> s = Files.walk(jail.root())) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.toString().endsWith(".java"))
             .filter(p -> !ignore.ignored(jail.root().relativize(p)))
             .forEach(javaFiles::add);
        } catch (IOException e) {
            Log.warn("index walk failed: " + e.getMessage());
        }

        for (Path file : javaFiles) {
            String rel = jail.relativize(file);
            seen.add(rel);
            try {
                long mtime = Files.getLastModifiedTime(file).toMillis();
                long size = Files.size(file);
                long[] row = known.get(rel);
                if (row != null && row[1] == mtime && row[2] == size) continue; // fast path
                byte[] bytes = Files.readAllBytes(file);
                byte[] hash = Hashes.sha256(bytes);
                if (row != null && java.util.Arrays.equals(hash, loadHash(row[0]))) {
                    updateStat(row[0], mtime, size);
                    continue;
                }
                indexFile(rel, bytes, hash, mtime, size);
            } catch (IOException e) {
                Log.debug("index skip " + rel + ": " + e.getMessage());
            }
        }
        // Drop vanished files (symbols cascade)
        for (Map.Entry<String, long[]> e : known.entrySet()) {
            if (!seen.contains(e.getKey())) deleteFile(e.getValue()[0]);
        }
        lastRefreshAt = System.currentTimeMillis();
        lastRefreshDuration = lastRefreshAt - t0;
        return stats();
    }

    private void indexFile(String rel, byte[] bytes, byte[] hash, long mtime, long size) {
        String status;
        String error = null;
        CompilationUnit cu = null;
        try {
            var result = parser.parse(new String(bytes, StandardCharsets.UTF_8));
            cu = result.getResult().orElse(null);
            if (cu == null) {
                status = "failed";
                error = result.getProblems().isEmpty() ? "no result"
                        : result.getProblem(0).getMessage();
            } else {
                status = result.getProblems().isEmpty() ? "ok" : "partial";
                if (!result.getProblems().isEmpty())
                    error = result.getProblem(0).getMessage();
            }
        } catch (Exception e) {
            status = "failed";
            error = e.getMessage();
        }

        final String fStatus = status;
        final String fError = error;
        final CompilationUnit fCu = cu;
        db.with(c -> {
            long fileId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO indexed_files(workspace_id, path, content_hash, mtime, size, "
                            + "parse_status, parse_error) VALUES (?,?,?,?,?,?,?) "
                            + "ON CONFLICT(workspace_id, path) DO UPDATE SET content_hash=excluded.content_hash, "
                            + "mtime=excluded.mtime, size=excluded.size, parse_status=excluded.parse_status, "
                            + "parse_error=excluded.parse_error",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, workspaceId);
                ps.setString(2, rel);
                ps.setBytes(3, hash);
                ps.setLong(4, mtime);
                ps.setLong(5, size);
                ps.setString(6, fStatus);
                ps.setString(7, fError);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM indexed_files WHERE workspace_id=? AND path=?")) {
                ps.setString(1, workspaceId);
                ps.setString(2, rel);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    fileId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM symbols WHERE file_id=?")) {
                ps.setLong(1, fileId);
                ps.executeUpdate();
            }
            if (fCu != null) extractSymbols(c, fileId, fCu);
            return null;
        });
    }

    private void extractSymbols(java.sql.Connection c, long fileId, CompilationUnit cu)
            throws java.sql.SQLException {
        List<Object[]> symbolRows = new ArrayList<>(); // kind, fq, sig, start, end, doc
        List<Object[]> edgeRows = new ArrayList<>();   // fromIndexInSymbolRows, toFq, kind

        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            String typeFq = type.getFullyQualifiedName().orElse(type.getNameAsString());
            int typeRow = symbolRows.size();
            symbolRows.add(new Object[]{typeKind(type), typeFq,
                    type.getNameAsString(), line(type, true), line(type, false), doc(type)});

            for (String imp : cu.getImports().stream()
                    .map(i -> i.getNameAsString()).toList()) {
                edgeRows.add(new Object[]{typeRow, imp, "imports"});
            }
            if (type instanceof ClassOrInterfaceDeclaration cid) {
                for (ClassOrInterfaceType ext : cid.getExtendedTypes())
                    edgeRows.add(new Object[]{typeRow, resolveTypeName(ext), "extends"});
                for (ClassOrInterfaceType impl : cid.getImplementedTypes())
                    edgeRows.add(new Object[]{typeRow, resolveTypeName(impl), "implements"});
            }

            for (FieldDeclaration field : type.getFields()) {
                field.getVariables().forEach(v ->
                        symbolRows.add(new Object[]{"field", typeFq + "." + v.getNameAsString(),
                                field.getElementType().asString() + " " + v.getNameAsString(),
                                line(field, true), line(field, false), doc(field)}));
            }

            for (Object member : type.getMembers()) {
                if (member instanceof MethodDeclaration m) {
                    int row = symbolRows.size();
                    symbolRows.add(new Object[]{"method", methodFq(typeFq, m),
                            m.getDeclarationAsString(true, false, true),
                            line(m, true), line(m, false), doc(m)});
                    collectCalls(m, row, edgeRows);
                } else if (member instanceof ConstructorDeclaration ctor) {
                    int row = symbolRows.size();
                    symbolRows.add(new Object[]{"ctor", methodFq(typeFq, ctor),
                            ctor.getDeclarationAsString(true, false, true),
                            line(ctor, true), line(ctor, false), doc(ctor)});
                    collectCalls(ctor, row, edgeRows);
                }
            }
        }

        long[] ids = new long[symbolRows.size()];
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO symbols(file_id, kind, fq_name, signature, start_line, end_line, "
                        + "doc_summary) VALUES (?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < symbolRows.size(); i++) {
                Object[] r = symbolRows.get(i);
                ps.setLong(1, fileId);
                ps.setString(2, (String) r[0]);
                ps.setString(3, (String) r[1]);
                ps.setString(4, (String) r[2]);
                ps.setInt(5, (Integer) r[3]);
                ps.setInt(6, (Integer) r[4]);
                ps.setString(7, (String) r[5]);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    ids[i] = keys.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO symbol_edges(from_id, to_fq, kind) VALUES (?,?,?)")) {
            for (Object[] e : edgeRows) {
                ps.setLong(1, ids[(Integer) e[0]]);
                ps.setString(2, (String) e[1]);
                ps.setString(3, (String) e[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Best-effort resolution (§2 AST row): failures degrade to "?#name", never fail the file. */
    private void collectCalls(CallableDeclaration<?> callable, int fromRow, List<Object[]> edges) {
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            String target;
            try {
                var resolved = call.resolve();
                target = resolved.declaringType().getQualifiedName() + "#" + resolved.getName();
            } catch (Throwable t) {
                target = "?#" + call.getNameAsString();
            }
            edges.add(new Object[]{fromRow, target, "calls"});
        }
    }

    private String resolveTypeName(ClassOrInterfaceType type) {
        try {
            return type.resolve().describe();
        } catch (Throwable t) {
            return type.getNameWithScope();
        }
    }

    private static String methodFq(String typeFq, CallableDeclaration<?> m) {
        String params = String.join(",", m.getParameters().stream()
                .map(p -> p.getType().asString()).toList());
        return typeFq + "#" + m.getNameAsString() + "(" + params + ")";
    }

    private static String typeKind(TypeDeclaration<?> t) {
        String name = t.getClass().getSimpleName();
        return switch (name) {
            case "ClassOrInterfaceDeclaration" ->
                    ((ClassOrInterfaceDeclaration) t).isInterface() ? "interface" : "class";
            case "EnumDeclaration" -> "enum";
            case "RecordDeclaration" -> "record";
            case "AnnotationDeclaration" -> "annotation";
            default -> "type";
        };
    }

    private static int line(com.github.javaparser.ast.Node n, boolean begin) {
        return n.getRange().map(r -> begin ? r.begin.line : r.end.line).orElse(0);
    }

    private static String doc(com.github.javaparser.ast.nodeTypes.NodeWithJavadoc<?> n) {
        return n.getJavadoc().map(j -> {
            String text = j.getDescription().toText().strip().replaceAll("\\s+", " ");
            int dot = text.indexOf(". ");
            return dot > 0 ? text.substring(0, dot + 1) : (text.length() > 200
                    ? text.substring(0, 200) : text);
        }).orElse(null);
    }

    private Map<String, long[]> loadKnown() {
        return db.with(c -> {
            Map<String, long[]> m = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT path, id, mtime, size FROM indexed_files WHERE workspace_id=?")) {
                ps.setString(1, workspaceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        m.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3), rs.getLong(4)});
                }
            }
            return m;
        });
    }

    private byte[] loadHash(long fileId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT content_hash FROM indexed_files WHERE id=?")) {
                ps.setLong(1, fileId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getBytes(1) : null;
                }
            }
        });
    }

    private void updateStat(long fileId, long mtime, long size) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE indexed_files SET mtime=?, size=? WHERE id=?")) {
                ps.setLong(1, mtime);
                ps.setLong(2, size);
                ps.setLong(3, fileId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void deleteFile(long fileId) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM indexed_files WHERE id=?")) {
                ps.setLong(1, fileId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Stats stats() {
        return db.with(c -> {
            int ok = 0, partial = 0, failed = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT parse_status, count(*) FROM indexed_files WHERE workspace_id=? "
                            + "GROUP BY parse_status")) {
                ps.setString(1, workspaceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        switch (rs.getString(1)) {
                            case "ok" -> ok = rs.getInt(2);
                            case "partial" -> partial = rs.getInt(2);
                            case "failed" -> failed = rs.getInt(2);
                        }
                    }
                }
            }
            long symbols;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM symbols")) {
                rs.next();
                symbols = rs.getLong(1);
            }
            return new Stats(ok, partial, failed, symbols, lastRefreshDuration);
        });
    }
}
