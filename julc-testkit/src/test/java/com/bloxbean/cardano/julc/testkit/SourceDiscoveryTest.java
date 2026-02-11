package com.bloxbean.cardano.julc.testkit;

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
}
