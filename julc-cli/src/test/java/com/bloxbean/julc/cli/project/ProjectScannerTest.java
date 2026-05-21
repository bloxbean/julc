package com.bloxbean.julc.cli.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectScannerTest {

    @Test
    void scanSeparatesValidatorsFromLibraries(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("MyValidator.java"), """
                @SpendingValidator
                public class MyValidator {
                    @Entrypoint
                    public static boolean validate(PlutusData r, ScriptContext ctx) { return true; }
                }
                """);
        Files.writeString(tempDir.resolve("Helper.java"), """
                public class Helper {
                    public static boolean check() { return true; }
                }
                """);

        var result = ProjectScanner.scan(tempDir);
        assertEquals(1, result.validators().size());
        assertTrue(result.validators().containsKey("MyValidator"));
        assertEquals(1, result.libraries().size());
        assertTrue(result.libraries().containsKey("Helper"));
    }

    @Test
    void scanKeysLibrariesByFqcnToAvoidSimpleNameCollisions(@TempDir Path tempDir) throws IOException {
        Path left = tempDir.resolve("com/left/Utils.java");
        Path right = tempDir.resolve("com/right/Utils.java");
        Files.createDirectories(left.getParent());
        Files.createDirectories(right.getParent());
        Files.writeString(left, """
                package com.left;
                public class Utils {}
                """);
        Files.writeString(right, """
                package com.right;
                public class Utils {}
                """);

        var result = ProjectScanner.scan(tempDir);

        assertEquals(2, result.libraries().size());
        assertTrue(result.libraries().containsKey("com.left.Utils"));
        assertTrue(result.libraries().containsKey("com.right.Utils"));
    }

    @Test
    void scanDetectsAllAnnotations(@TempDir Path tempDir) throws IOException {
        String[] annotations = {
                "@SpendingValidator", "@MintingValidator", "@WithdrawValidator",
                "@CertifyingValidator", "@VotingValidator", "@ProposingValidator",
                "@MultiValidator"
        };
        for (int i = 0; i < annotations.length; i++) {
            Files.writeString(tempDir.resolve("V" + i + ".java"),
                    annotations[i] + "\npublic class V" + i + " {}");
        }

        var result = ProjectScanner.scan(tempDir);
        assertEquals(annotations.length, result.validators().size());
        assertEquals(0, result.libraries().size());
    }

    @Test
    void scanIgnoresValidatorAnnotationTextInComments(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("Helper.java"), """
                /**
                 * Mentions @SpendingValidator but is not a validator.
                 */
                public class Helper {}
                """);

        var result = ProjectScanner.scan(tempDir);

        assertTrue(result.validators().isEmpty());
        assertEquals(1, result.libraries().size());
        assertTrue(result.libraries().containsKey("Helper"));
    }

    @Test
    void scanRejectsLegacyValidatorAnnotations(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("Old.java"), """
                @Validator
                public class Old {}
                """);

        var ex = assertThrows(IllegalArgumentException.class, () -> ProjectScanner.scan(tempDir));
        assertTrue(ex.getMessage().contains("Use @SpendingValidator instead"));
    }

    @Test
    void scanRejectsOnchainLibraryValidatorRoleConflict(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("Confused.java"), """
                @OnchainLibrary
                @SpendingValidator
                public class Confused {}
                """);

        var ex = assertThrows(IllegalArgumentException.class, () -> ProjectScanner.scan(tempDir));
        assertTrue(ex.getMessage().contains("must not combine @OnchainLibrary"));
        assertTrue(ex.getMessage().contains("@SpendingValidator"));
    }

    @Test
    void scanReportsParseErrorForPrefilteredValidatorCandidate(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("Broken.java"), """
                // @SpendingValidator
                class Broken {
                """);

        var ex = assertThrows(IllegalArgumentException.class, () -> ProjectScanner.scan(tempDir));

        assertTrue(ex.getMessage().contains("Could not parse validator candidate"));
        assertTrue(ex.getMessage().contains("Broken.java"));
    }

    @Test
    void scanEmptyDirectory(@TempDir Path tempDir) throws IOException {
        var result = ProjectScanner.scan(tempDir);
        assertTrue(result.validators().isEmpty());
        assertTrue(result.libraries().isEmpty());
    }

    @Test
    void scanNonExistentDirectory() throws IOException {
        var result = ProjectScanner.scan(Path.of("/nonexistent/path"));
        assertTrue(result.validators().isEmpty());
    }

    @Test
    void resolveScriptType() {
        assertEquals("PlutusScriptV3", ProjectScanner.resolveScriptType("@MintingValidator class X {}"));
        assertEquals("PlutusScriptV3", ProjectScanner.resolveScriptType("@WithdrawValidator class X {}"));
        assertEquals("PlutusScriptV3", ProjectScanner.resolveScriptType("@SpendingValidator class X {}"));
        assertEquals("PlutusScriptV3", ProjectScanner.resolveScriptType("""
                /** Mentions @MintingValidator in a comment. */
                class X {}
                """));
        var conflict = assertThrows(IllegalArgumentException.class,
                () -> ProjectScanner.resolveScriptType("@OnchainLibrary @SpendingValidator class X {}"));
        assertTrue(conflict.getMessage().contains("must not combine @OnchainLibrary"));
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ProjectScanner.resolveScriptType("@MintingPolicy class X {}"));
        assertTrue(ex.getMessage().contains("Use @MintingValidator instead"));
    }
}
