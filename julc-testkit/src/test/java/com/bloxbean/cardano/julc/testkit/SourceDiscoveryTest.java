package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
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
    void compileFqcn_discoversTransitiveSamePackageLibrary() {
        CompileResult result = SourceDiscovery.compile(
                "com.example.libs.ValidatorUsesTransitiveSamePackageLibrary", TEST_SOURCE_ROOT);
        assertNotNull(result);
        assertFalse(result.hasErrors(), "Expected no errors: " + result.diagnostics());
        assertNotNull(result.program());
    }

    @Test
    void compileFqcn_ignoresOnchainLibraryTextInComments(@TempDir Path tempDir) throws IOException {
        Path pkgDir = tempDir.resolve("com/example");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("UseLib.java"), """
                package com.example;

                @SpendingValidator
                class UseLib {
                    @Entrypoint
                    static boolean validate(long redeemer, long ctx) {
                        return true;
                    }
                }
                """);
        Files.writeString(pkgDir.resolve("CommentOnly.java"), """
                package com.example;

                /** Mentions @OnchainLibrary but is not a library. */
                class CommentOnly {}
                """);

        CompileResult result = SourceDiscovery.compile("com.example.UseLib", tempDir);

        assertNotNull(result);
        assertFalse(result.hasErrors(), "Expected no errors: " + result.diagnostics());
    }

    @Test
    void compileFqcn_reportsParseErrorForPrefilteredLibraryCandidate(@TempDir Path tempDir) throws IOException {
        Path pkgDir = tempDir.resolve("com/example");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("UseLib.java"), """
                package com.example;

                @SpendingValidator
                class UseLib {
                    @Entrypoint
                    static boolean validate(long redeemer, long ctx) {
                        return true;
                    }
                }
                """);
        Files.writeString(pkgDir.resolve("Broken.java"), """
                package com.example;

                // @OnchainLibrary
                class Broken {
                """);

        var error = assertThrows(AssertionError.class,
                () -> SourceDiscovery.compile("com.example.UseLib", tempDir));

        assertTrue(error.getMessage().contains("Could not parse @OnchainLibrary candidate"));
        assertTrue(error.getMessage().contains("Broken.java"));
    }

    @Test
    void compileFqcn_rejectsOnchainLibraryValidatorRoleConflict(@TempDir Path tempDir) throws IOException {
        Path pkgDir = tempDir.resolve("com/example");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("UseLib.java"), """
                package com.example;

                @SpendingValidator
                class UseLib {
                    @Entrypoint
                    static boolean validate(long redeemer, long ctx) {
                        return true;
                    }
                }
                """);
        Files.writeString(pkgDir.resolve("Confused.java"), """
                package com.example;

                @OnchainLibrary
                @SpendingValidator
                class Confused {}
                """);

        var error = assertThrows(AssertionError.class,
                () -> SourceDiscovery.compile("com.example.UseLib", tempDir));

        assertTrue(error.getMessage().contains("must not combine @OnchainLibrary"));
        assertTrue(error.getMessage().contains("@SpendingValidator"));
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
