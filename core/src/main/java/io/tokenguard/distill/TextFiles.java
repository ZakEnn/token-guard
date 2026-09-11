package io.tokenguard.distill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared text-file reading: binary sniffing and size guards for Engine 4 tools. */
final class TextFiles {

    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private TextFiles() {}

    static boolean looksBinary(Path file) throws IOException {
        byte[] head = new byte[8192];
        try (var in = Files.newInputStream(file)) {
            int n = in.read(head);
            for (int i = 0; i < n; i++) {
                if (head[i] == 0) return true;
            }
        }
        return false;
    }

    /** @throws IOException on unreadable; caller handles binary/size checks first. */
    static String read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
