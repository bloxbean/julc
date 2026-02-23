package com.bloxbean.cardano.julc.analyzer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InputReaderTest {

    @Test
    void normalize_plainHex() {
        assertEquals("abcdef01", InputReader.normalize("abcdef01"));
    }

    @Test
    void normalize_withOxPrefix() {
        assertEquals("abcdef01", InputReader.normalize("0xabcdef01"));
    }

    @Test
    void normalize_with0XPrefix() {
        assertEquals("abcdef01", InputReader.normalize("0Xabcdef01"));
    }

    @Test
    void normalize_stripsWhitespace() {
        assertEquals("abcdef01", InputReader.normalize("  abcd ef01  "));
    }

    @Test
    void normalize_stripsNewlines() {
        assertEquals("abcdef01", InputReader.normalize("abcd\nef01\n"));
    }

    @Test
    void normalize_emptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> InputReader.normalize(""));
    }

    @Test
    void normalize_blankThrows() {
        assertThrows(IllegalArgumentException.class, () -> InputReader.normalize("   "));
    }

    @Test
    void normalize_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> InputReader.normalize(null));
    }

    @Test
    void normalize_invalidHexThrows() {
        assertThrows(IllegalArgumentException.class, () -> InputReader.normalize("xyz123"));
    }

    @Test
    void normalize_oxOnlyThrows() {
        assertThrows(IllegalArgumentException.class, () -> InputReader.normalize("0x"));
    }

    @Test
    void readFile_readsHex(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("test.hex");
        Files.writeString(file, "0xabcdef01\n");
        assertEquals("abcdef01", InputReader.readFile(file));
    }

    @Test
    void readFile_notFound(@TempDir Path tempDir) {
        var file = tempDir.resolve("missing.hex");
        assertThrows(IOException.class, () -> InputReader.readFile(file));
    }

    @Test
    void readHex_inlineValue() throws IOException {
        assertEquals("abcdef01", InputReader.readHex("abcdef01"));
    }

    @Test
    void readHex_withOxPrefix() throws IOException {
        assertEquals("abcdef01", InputReader.readHex("0xabcdef01"));
    }

    @Test
    void normalize_uppercaseHex() {
        assertEquals("ABCDEF01", InputReader.normalize("ABCDEF01"));
    }

    @Test
    void normalize_mixedCaseHex() {
        assertEquals("aBcDeF01", InputReader.normalize("aBcDeF01"));
    }
}
