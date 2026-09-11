package io.tokenguard.util;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Gitignore-aware filtering for list_directory / search_files (plan §5.1).
 * Deliberately minimal: built-in noise directories + the root .gitignore's
 * simple patterns. Negation (!) and nested .gitignore files are not supported —
 * over-listing is a token cost, not a correctness bug.
 */
public final class IgnoreRules {

    private static final Set<String> BUILTIN_DIRS = Set.of(
            ".git", ".hg", ".svn", "node_modules", "target", "build", "dist", "out",
            ".gradle", ".idea", ".vscode", "__pycache__", ".venv", "venv", ".next",
            ".token-guard");

    private final List<PathMatcher> matchers = new ArrayList<>();

    public IgnoreRules(Path workspaceRoot) {
        Path gitignore = workspaceRoot.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) return;
        try {
            for (String raw : Files.readAllLines(gitignore)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
                if (line.endsWith("/")) line = line.substring(0, line.length() - 1);
                // Unanchored patterns match at any depth; "**/x" alone misses
                // root-level entries (glob "**/" requires >=1 directory), so add both.
                List<String> globs = line.startsWith("/")
                        ? List.of(line.substring(1))
                        : (line.contains("/") ? List.of(line) : List.of(line, "**/" + line));
                for (String glob : globs) {
                    try {
                        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
                        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob + "/**"));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** @param relPath workspace-relative path using the platform separator */
    public boolean ignored(Path relPath) {
        for (Path part : relPath) {
            if (BUILTIN_DIRS.contains(part.toString())) return true;
        }
        for (PathMatcher m : matchers) {
            if (m.matches(relPath)) return true;
        }
        return false;
    }
}
