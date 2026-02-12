package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourceDiscoveryTest {

    @Test
    void sourceFileFor_convertsClassToPath() {
        // A hypothetical class com.example.validators.VestingValidator
        // should resolve to src/main/java/com/example/validators/VestingValidator.java
        Path result = SourceDiscovery.sourceFileFor(
                com.bloxbean.cardano.julc.testkit.SourceDiscovery.class,
                Path.of("src/main/java"));

        assertEquals(
                Path.of("src/main/java/com/bloxbean/cardano/julc/testkit/SourceDiscovery.java"),
                result);
    }

    @Test
    void sourceFileFor_innerClassUsesOuterPath() {
        // Inner classes use the outer class name in their canonical path
        // but Class.getName() returns Outer$Inner — we need to handle dollar sign
        Path result = SourceDiscovery.sourceFileFor(
                java.util.Map.Entry.class,
                Path.of("src/main/java"));

        // Map$Entry → should still produce a path (even if file doesn't exist)
        assertNotNull(result);
        assertTrue(result.toString().endsWith(".java"));
    }

    @Test
    void sourceFileFor_customSourceRoot() {
        Path result = SourceDiscovery.sourceFileFor(
                String.class,
                Path.of("custom/src"));

        assertEquals(
                Path.of("custom/src/java/lang/String.java"),
                result);
    }

    // --- FQCN-based compilation tests ---

    private static final Path TEST_SOURCE_ROOT = Path.of("src/test/resources/testdata");

    @Test
    void compileFqcn_compilesSimpleValidator() {
        CompileResult result = SourceDiscovery.compile(
                "com.example.validators.SimpleValidator", TEST_SOURCE_ROOT);
        assertNotNull(result);
        assertFalse(result.hasErrors(), "Expected no errors: " + result.diagnostics());
        assertNotNull(result.program());
    }

    @Test
    void compileFqcn_nonExistentClassGivesClearError() {
        var error = assertThrows(AssertionError.class, () ->
                SourceDiscovery.compile("com.example.NonExistent", TEST_SOURCE_ROOT));
        assertTrue(error.getMessage().contains("Cannot read validator source"),
                "Expected clear error about missing source, got: " + error.getMessage());
        assertTrue(error.getMessage().contains("com.example.NonExistent"),
                "Error should mention the FQCN, got: " + error.getMessage());
    }
}
