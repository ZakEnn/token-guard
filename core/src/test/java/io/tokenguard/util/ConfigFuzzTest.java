package io.tokenguard.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0.4 DoD (enhancement plan): malformed-TOML fuzz corpus (>=10 cases) —
 * startup must always degrade to defaults, never crash (CLAUDE.md §4.2 item).
 */
class ConfigFuzzTest {

    @TempDir Path tmp;

    private Config load(String name, byte[] content) throws Exception {
        Path f = tmp.resolve(name);
        Files.write(f, content);
        return assertDoesNotThrow(() -> Config.load(f), "Config.load must never throw: " + name);
    }

    private Config load(String name, String content) throws Exception {
        return load(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertDefaults(Config cfg) {
        assertEquals(512, cfg.getLong("cache.size_mb", 512));
        assertEquals(0.94, cfg.getDouble("cache.semantic_threshold", 0.94));
        assertTrue(cfg.getBool("telemetry.enabled", true));
        assertEquals("x", cfg.getString("backend.url", "x"));
    }

    @Test void binaryGarbage() throws Exception {
        byte[] b = new byte[4096];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (i * 31);
        assertDefaults(load("binary.toml", b));
    }

    @Test void invalidUtf8() throws Exception {
        assertDefaults(load("bad-utf8.toml",
                new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xFF, (byte) 0xFE, '[', 'c', ']'}));
    }

    @Test void truncatedSectionHeader() throws Exception {
        assertDefaults(load("trunc.toml", "[cache\nsize_mb = 9"));
    }

    @Test void keyWithoutEquals() throws Exception {
        assertDefaults(load("noeq.toml", "cache size_mb 9\njust some words\n"));
    }

    @Test void equalsWithoutKey() throws Exception {
        Config cfg = load("nokey.toml", "= 42\n[cache]\n= 43\n");
        assertEquals(512, cfg.getLong("cache.size_mb", 512));
    }

    @Test void hugeSingleLine() throws Exception {
        assertDefaults(load("huge.toml", "x = \"" + "y".repeat(1_000_000)));
    }

    @Test void nestedQuotes() throws Exception {
        Config cfg = load("quotes.toml", "[cache]\nname = \"a\"b\"c\"\nsize_mb = 7\n");
        assertEquals(7, cfg.getLong("cache.size_mb", 512), "later valid lines still parse");
    }

    @Test void byteOrderMarkPrefix() throws Exception {
        Config cfg = load("bom.toml", "﻿[cache]\nsize_mb = 9\n");
        // BOM breaks the section header detection — degrade, don't crash
        assertDoesNotThrow(() -> cfg.getLong("cache.size_mb", 512));
    }

    @Test void mixedLineEndingsAndTabs() throws Exception {
        Config cfg = load("crlf.toml", "[cache]\r\n\tsize_mb\t=\t9\r\ngarbage line\r\n");
        assertEquals(9, cfg.getLong("cache.size_mb", 512));
    }

    @Test void unicodeKeysAndValues() throws Exception {
        Config cfg = load("emoji.toml", "[caché]\nclé = \"värde✓🎯\"\n");
        assertEquals("värde✓🎯", cfg.getString("caché.clé", ""));
        assertDefaults(cfg);
    }

    @Test void embeddedNullBytes() throws Exception {
        assertDefaults(load("nulls.toml", "[ca\0che]\nsize\0_mb = 9\n"));
    }

    @Test void onlyCommentsAndBlanks() throws Exception {
        assertDefaults(load("comments.toml", "# hello\n\n   # world\n"));
    }

    @Test void nonNumericValuesFallBackPerKey() throws Exception {
        Config cfg = load("types.toml", "[cache]\nsize_mb = banana\nsemantic_threshold = twelve\n");
        assertEquals(512, cfg.getLong("cache.size_mb", 512));
        assertEquals(0.94, cfg.getDouble("cache.semantic_threshold", 0.94));
    }

    @Test void missingFileIsDefaults() {
        assertDefaults(assertDoesNotThrow(() -> Config.load(tmp.resolve("absent.toml"))));
    }
}
