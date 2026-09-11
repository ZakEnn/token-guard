package io.tokenguard.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Confines all file access to the canonicalized workspace root (§6.4). Symlinks
 * are resolved BEFORE the containment check — the classic jail escape. Shared
 * by the standard tools (M1) and the script sandbox FileSystem (M5) so there is
 * exactly one jail implementation to get right.
 */
public final class PathJail {

    private final Path root;

    public PathJail(Path workspaceRoot) throws IOException {
        this.root = workspaceRoot.toRealPath();
    }

    public Path root() { return root; }

    /**
     * Resolve a user/script-supplied path and prove it stays inside the jail.
     * Works for not-yet-existing files by canonicalizing the nearest existing
     * ancestor (needed by apply_edit for file creation).
     */
    public Path resolve(String supplied) throws IOException {
        if (supplied == null || supplied.isBlank())
            throw new JailViolation("Empty path");
        Path p = Path.of(supplied);
        Path abs = (p.isAbsolute() ? p : root.resolve(p)).normalize();

        if (Files.exists(abs)) {
            Path real = abs.toRealPath();
            check(real, supplied);
            return real;
        }
        Path ancestor = abs.getParent();
        Path remainder = abs.getFileName();
        while (ancestor != null && !Files.exists(ancestor)) {
            remainder = ancestor.getFileName().resolve(remainder);
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) throw new JailViolation("Path outside workspace: " + supplied);
        Path real = ancestor.toRealPath().resolve(remainder).normalize();
        check(real, supplied);
        return real;
    }

    private void check(Path real, String supplied) {
        if (!real.startsWith(root))
            throw new JailViolation("Path outside workspace: " + supplied);
    }

    /** Workspace-relative display form (stable across OSes, '/' separators). */
    public String relativize(Path real) {
        return root.relativize(real).toString().replace('\\', '/');
    }

    public static final class JailViolation extends RuntimeException {
        public JailViolation(String msg) { super(msg); }
    }
}
