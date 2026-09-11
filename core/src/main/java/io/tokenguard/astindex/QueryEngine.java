package io.tokenguard.astindex;

import io.tokenguard.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine 2 query side: compact structural answers (signatures, edges, line
 * refs) - never raw file bodies. Every answer carries the set of files that
 * backed it, which becomes the cache entry's invalidation set (plan 6.1.4),
 * plus their total size (the naive-baseline cost for savings metrics, A6).
 */
public final class QueryEngine {

    public record Answer(String text, Map<String, byte[]> deps, long depsBytes) {}

    private record Sym(long id, String kind, String fq, String sig, int start, int end,
                       String doc, String path, byte[] fileHash, String parseStatus,
                       long fileSize) {}

    private static final int MAX_ROWS = 60;

    private final Database db;
    private final String workspaceId;

    public QueryEngine(Database db, String workspaceId) {
        this.db = db;
        this.workspaceId = workspaceId;
    }

    public Answer query(String type, String target, int depth) {
        return switch (type) {
            case "outline" -> outline(target);
            case "symbol" -> symbol(target);
            case "callers" -> callers(target);
            case "callees" -> callees(target);
            case "dependencies" -> dependencies(target);
            case "hierarchy" -> hierarchy(target, Math.max(1, depth));
            case "search" -> search(target);
            case "repo_map" -> repoMap(1000);
            default -> new Answer("Unknown query_type '" + type + "'. Valid: outline, symbol, "
                    + "callers, callees, dependencies, hierarchy, search, repo_map.", Map.of(), 0);
        };
    }

    /**
     * P3.2 (enhancement plan; Aider's repo-map design, research_proposition.md
     * §2.4/KC-5): rank files by PageRank over the file-level reference graph
     * derived from symbol_edges, render compact per-file signature blocks in
     * rank order, and fit the result to a token budget. Fully deterministic
     * (rank ties broken by path).
     */
    public Answer repoMap(int mapTokens) {
        int budget = Math.clamp(mapTokens, 200, 8000);
        List<Sym> all = bySql(symSql("1=1") + " ORDER BY f.path, s.start_line", workspaceId);
        if (all.isEmpty())
            return new Answer("Repo map: index is empty (only .java files are indexed in v1).",
                    Map.of(), 0);

        // fq(type) -> file, symbolId -> file
        Map<String, String> typeToFile = new LinkedHashMap<>();
        Map<Long, String> symToFile = new LinkedHashMap<>();
        Map<String, List<Sym>> byFile = new LinkedHashMap<>();
        for (Sym s : all) {
            symToFile.put(s.id(), s.path());
            byFile.computeIfAbsent(s.path(), k -> new ArrayList<>()).add(s);
            if (List.of("class", "interface", "enum", "record").contains(s.kind()))
                typeToFile.put(s.fq(), s.path());
        }

        // file-level reference graph from all edge kinds
        Map<String, Map<String, Integer>> refs = new LinkedHashMap<>();
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT e.from_id, e.to_fq FROM symbol_edges e "
                            + "JOIN symbols s ON e.from_id = s.id "
                            + "JOIN indexed_files f ON s.file_id = f.id "
                            + "WHERE f.workspace_id = ?")) {
                ps.setString(1, workspaceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String from = symToFile.get(rs.getLong(1));
                        String toFq = rs.getString(2);
                        String toType = toFq.contains("#")
                                ? toFq.substring(0, toFq.indexOf('#')) : toFq;
                        String to = typeToFile.get(toType);
                        if (from == null || to == null || from.equals(to)) continue;
                        refs.computeIfAbsent(from, k -> new LinkedHashMap<>())
                            .merge(to, 1, Integer::sum);
                    }
                }
            } catch (SQLException e) {
                throw new Database.StoreException("repo_map edge query failed", e);
            }
            return null;
        });

        // PageRank, damping 0.85, 20 iterations, dangling mass redistributed
        List<String> files = byFile.keySet().stream().sorted().toList();
        Map<String, Double> rank = new LinkedHashMap<>();
        for (String f : files) rank.put(f, 1.0 / files.size());
        for (int it = 0; it < 20; it++) {
            Map<String, Double> next = new LinkedHashMap<>();
            double dangling = 0;
            for (String f : files) {
                Map<String, Integer> out = refs.get(f);
                if (out == null || out.isEmpty()) dangling += rank.get(f);
            }
            for (String f : files)
                next.put(f, (1 - 0.85) / files.size() + 0.85 * dangling / files.size());
            for (String f : files) {
                Map<String, Integer> out = refs.get(f);
                if (out == null || out.isEmpty()) continue;
                int total = out.values().stream().mapToInt(Integer::intValue).sum();
                for (var e : out.entrySet())
                    next.merge(e.getKey(), 0.85 * rank.get(f) * e.getValue() / total, Double::sum);
            }
            rank = next;
        }
        final Map<String, Double> finalRank = rank;
        List<String> ordered = files.stream()
                .sorted((a, b) -> {
                    int cmp = Double.compare(finalRank.get(b), finalRank.get(a));
                    return cmp != 0 ? cmp : a.compareTo(b);
                }).toList();

        // Render per-file blocks in rank order, greedily fit the budget.
        // Greedy prefix accumulation == binary-search result (monotone estimate).
        StringBuilder acc = new StringBuilder("Repo map (ranked by reference importance):\n");
        List<Sym> included = new ArrayList<>();
        int omittedFiles = 0;
        String omissionTemplate = "[999 more files omitted - raise map_tokens]\n";
        long reserve = io.tokenguard.util.TokenEstimator.estimate(omissionTemplate);
        for (String f : ordered) {
            StringBuilder block = new StringBuilder(f).append('\n');
            List<Sym> syms = byFile.get(f);
            int shown = 0;
            for (Sym s : syms) {
                if (shown == 12) {
                    block.append("    [").append(syms.size() - 12).append(" more members]\n");
                    break;
                }
                boolean member = "field".equals(s.kind()) || "method".equals(s.kind())
                        || "ctor".equals(s.kind());
                block.append(member ? "    " : "  ").append(s.kind()).append(' ')
                     .append(member ? s.sig() : s.fq()).append('\n');
                shown++;
            }
            long would = io.tokenguard.util.TokenEstimator.estimate(acc.toString() + block)
                    + reserve;
            if (would > budget) {
                omittedFiles = ordered.size() - ordered.indexOf(f);
                break;
            }
            acc.append(block);
            included.addAll(syms);
        }
        if (omittedFiles > 0)
            acc.append('[').append(omittedFiles).append(" more files omitted - raise map_tokens]\n");
        return answer(acc, included);
    }

    private Answer outline(String path) {
        String rel = path.replace('\\', '/');
        List<Sym> syms = bySql(symSql("f.path = ?") + " ORDER BY s.start_line",
                workspaceId, rel);
        if (syms.isEmpty())
            return new Answer(noResult("file", rel), Map.of(), 0);
        StringBuilder sb = new StringBuilder(rel);
        if (!"ok".equals(syms.get(0).parseStatus()))
            sb.append(" [parse ").append(syms.get(0).parseStatus())
              .append(" - outline may be incomplete; use read_file for detail]");
        sb.append('\n');
        for (Sym s : syms) {
            boolean member = "field".equals(s.kind()) || "method".equals(s.kind())
                    || "ctor".equals(s.kind());
            sb.append("  ".repeat(member ? 2 : 1))
              .append(s.kind()).append(' ').append(member ? s.sig() : s.fq())
              .append("  (").append(s.start()).append('-').append(s.end()).append(')');
            if (s.doc() != null) sb.append("  // ").append(s.doc());
            sb.append('\n');
        }
        return answer(sb, syms);
    }

    private Answer symbol(String name) {
        List<Sym> syms = findSymbols(name);
        if (syms.isEmpty()) return new Answer(noResult("symbol", name), Map.of(), 0);
        StringBuilder sb = new StringBuilder();
        List<Sym> shown = syms.subList(0, Math.min(syms.size(), 10));
        for (Sym s : shown) {
            sb.append(s.kind()).append(' ').append(s.fq()).append('\n')
              .append("  ").append(s.sig()).append('\n')
              .append("  at ").append(s.path()).append(':')
              .append(s.start()).append('-').append(s.end()).append('\n');
            if (s.doc() != null) sb.append("  ").append(s.doc()).append('\n');
        }
        if (syms.size() > 10)
            sb.append('[').append(syms.size() - 10).append(" more matches omitted]\n");
        return answer(sb, shown);
    }

    private Answer callers(String target) {
        String methodName = target.contains("#")
                ? target.substring(target.indexOf('#') + 1) : target;
        methodName = methodName.contains("(")
                ? methodName.substring(0, methodName.indexOf('(')) : methodName;
        String typePart = target.contains("#") ? target.substring(0, target.indexOf('#')) : null;

        List<Sym> rows = bySql(
                "SELECT s.id, s.kind, s.fq_name, s.signature, s.start_line, s.end_line, "
                        + "s.doc_summary, f.path, f.content_hash, f.parse_status, f.size "
                        + "FROM symbol_edges e "
                        + "JOIN symbols s ON e.from_id = s.id "
                        + "JOIN indexed_files f ON s.file_id = f.id "
                        + "WHERE f.workspace_id = ? AND e.kind = 'calls' "
                        + "AND (e.to_fq = ? OR e.to_fq LIKE ? OR e.to_fq = ?) "
                        + "ORDER BY f.path, s.start_line",
                workspaceId,
                typePart != null ? typePart + "#" + methodName : "#" + methodName,
                "%#" + methodName,
                "?#" + methodName);
        List<Sym> kept = rows.subList(0, Math.min(rows.size(), MAX_ROWS));
        if (kept.isEmpty()) return new Answer(noResult("callers of", target), Map.of(), 0);
        StringBuilder sb = new StringBuilder("Callers of " + target + ":\n");
        for (Sym s : kept)
            sb.append("  ").append(s.fq()).append("  (").append(s.path()).append(':')
              .append(s.start()).append(")\n");
        if (rows.size() > MAX_ROWS)
            sb.append('[').append(rows.size() - MAX_ROWS).append(" more omitted]\n");
        sb.append("Note: calls in files with unresolved imports match by method name only.\n");
        return answer(sb, kept);
    }

    private Answer callees(String target) {
        List<Sym> owners = findSymbols(target);
        if (owners.isEmpty()) return new Answer(noResult("symbol", target), Map.of(), 0);
        Sym owner = owners.get(0);
        List<String> callees = db.with(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT DISTINCT to_fq FROM symbol_edges WHERE from_id = ? AND kind='calls' "
                            + "ORDER BY to_fq LIMIT " + MAX_ROWS)) {
                ps.setLong(1, owner.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            return out;
        });
        StringBuilder sb = new StringBuilder(owner.fq() + " calls:\n");
        if (callees.isEmpty()) sb.append("  (no outgoing calls indexed)\n");
        for (String callee : callees)
            sb.append("  ").append(callee.startsWith("?#")
                    ? callee.substring(2) + " (unresolved)" : callee).append('\n');
        return answer(sb, owners.subList(0, 1));
    }

    private Answer dependencies(String target) {
        List<Sym> syms = target.endsWith(".java") || target.contains("/")
                ? bySql(symSql("f.path = ?"), workspaceId, target.replace('\\', '/'))
                : findSymbols(target);
        syms = syms.stream().filter(s -> !"method".equals(s.kind())
                && !"field".equals(s.kind()) && !"ctor".equals(s.kind())).toList();
        if (syms.isEmpty()) return new Answer(noResult("type or file", target), Map.of(), 0);
        StringBuilder sb = new StringBuilder();
        List<Sym> shown = syms.subList(0, Math.min(3, syms.size()));
        for (Sym s : shown) {
            sb.append(s.fq()).append(" depends on:\n");
            Map<String, List<String>> byKind = edgesFrom(s.id());
            for (var e : byKind.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ")
                  .append(String.join(", ", e.getValue())).append('\n');
            }
            if (byKind.isEmpty()) sb.append("  (none indexed)\n");
        }
        return answer(sb, shown);
    }

    private Answer hierarchy(String target, int depth) {
        List<Sym> syms = findSymbols(target).stream()
                .filter(s -> List.of("class", "interface", "enum", "record").contains(s.kind()))
                .toList();
        if (syms.isEmpty()) return new Answer(noResult("type", target), Map.of(), 0);
        Sym type = syms.get(0);
        StringBuilder sb = new StringBuilder("Hierarchy of " + type.fq() + ":\n");
        sb.append("  supertypes: ");
        Map<String, List<String>> edges = edgesFrom(type.id());
        List<String> supers = new ArrayList<>();
        supers.addAll(edges.getOrDefault("extends", List.of()));
        supers.addAll(edges.getOrDefault("implements", List.of()));
        sb.append(supers.isEmpty() ? "(none indexed)" : String.join(", ", supers)).append('\n');

        List<Sym> subs = bySql(
                "SELECT s.id, s.kind, s.fq_name, s.signature, s.start_line, s.end_line, "
                        + "s.doc_summary, f.path, f.content_hash, f.parse_status, f.size "
                        + "FROM symbol_edges e "
                        + "JOIN symbols s ON e.from_id = s.id "
                        + "JOIN indexed_files f ON s.file_id = f.id "
                        + "WHERE f.workspace_id = ? AND e.kind IN ('extends','implements') "
                        + "AND (e.to_fq = ? OR e.to_fq LIKE ?)",
                workspaceId, type.fq(), "%." + simpleName(type.fq()));
        sb.append("  subtypes in workspace: ");
        sb.append(subs.isEmpty() ? "(none)"
                : String.join(", ", subs.stream().map(Sym::fq).distinct().toList()));
        sb.append('\n');
        List<Sym> all = new ArrayList<>(List.of(type));
        all.addAll(subs);
        return answer(sb, all);
    }

    private Answer search(String query) {
        List<Sym> syms = bySql(symSql("lower(s.fq_name) LIKE lower(?)")
                        + " ORDER BY length(s.fq_name)",
                workspaceId, "%" + query + "%");
        syms = syms.subList(0, Math.min(syms.size(), MAX_ROWS));
        if (syms.isEmpty()) return new Answer(noResult("symbols matching", query), Map.of(), 0);
        StringBuilder sb = new StringBuilder(syms.size() + " symbol(s) matching '" + query + "':\n");
        for (Sym s : syms)
            sb.append("  ").append(s.kind()).append(' ').append(s.fq())
              .append("  (").append(s.path()).append(':').append(s.start()).append(")\n");
        return answer(sb, syms);
    }

    // ---- helpers ----

    private List<Sym> findSymbols(String name) {
        List<Sym> exact = bySql(symSql("s.fq_name = ?"), workspaceId, name);
        if (!exact.isEmpty()) return exact;
        List<Sym> suffix = bySql(symSql("s.fq_name LIKE ?"), workspaceId, "%." + name);
        if (!suffix.isEmpty()) return suffix;
        List<Sym> methodSuffix = bySql(symSql("s.fq_name LIKE ?"), workspaceId, "%#" + name + "(%");
        if (!methodSuffix.isEmpty()) return methodSuffix;
        return bySql(symSql("s.fq_name LIKE ?"), workspaceId, "%" + name + "%");
    }

    private static String symSql(String where) {
        return "SELECT s.id, s.kind, s.fq_name, s.signature, s.start_line, s.end_line, "
                + "s.doc_summary, f.path, f.content_hash, f.parse_status, f.size "
                + "FROM symbols s JOIN indexed_files f ON s.file_id = f.id "
                + "WHERE f.workspace_id = ? AND " + where;
    }

    private Map<String, List<String>> edgesFrom(long symbolId) {
        return db.with(c -> {
            Map<String, List<String>> m = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT kind, to_fq FROM symbol_edges WHERE from_id=? ORDER BY kind, to_fq")) {
                ps.setLong(1, symbolId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        m.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                         .add(rs.getString(2));
                }
            }
            return m;
        });
    }

    private List<Sym> bySql(String sql, Object... params) {
        return db.with(c -> {
            List<Sym> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Sym(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getString(7),
                                rs.getString(8), rs.getBytes(9), rs.getString(10),
                                rs.getLong(11)));
                    }
                }
            } catch (SQLException e) {
                throw new Database.StoreException("query failed", e);
            }
            return out;
        });
    }

    private static Answer answer(StringBuilder text, List<Sym> syms) {
        Map<String, byte[]> deps = new LinkedHashMap<>();
        long bytes = 0;
        for (Sym s : syms) {
            if (deps.put(s.path(), s.fileHash()) == null) bytes += s.fileSize();
        }
        return new Answer(text.toString(), deps, bytes);
    }

    private static String simpleName(String fq) {
        int dot = fq.lastIndexOf('.');
        return dot >= 0 ? fq.substring(dot + 1) : fq;
    }

    private static String noResult(String what, String target) {
        return "No " + what + " '" + target + "' in the index. The index covers .java files "
                + "only (v1); try query_type=search with a shorter name, or search_files for "
                + "non-Java code.";
    }
}
