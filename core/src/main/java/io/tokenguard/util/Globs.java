package io.tokenguard.util;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.function.Predicate;

/**
 * Glob matching for workspace-relative paths. Java's PathMatcher requires at
 * least one directory for a leading "**&#47;", silently missing root-level
 * files — every user-facing glob goes through here so that fix exists once.
 */
public final class Globs {

    private Globs() {}

    public static Predicate<Path> matcher(String glob) {
        PathMatcher main = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        PathMatcher rootLevel = glob.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3))
                : null;
        return p -> main.matches(p) || (rootLevel != null && rootLevel.matches(p));
    }
}
