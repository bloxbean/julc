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
                @Validator
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
    void scanDetectsAllAnnotations(@TempDir Path tempDir) throws IOException {
        String[] annotations = {
                "@Validator", "@SpendingValidator", "@MintingPolicy",
                "@MintingValidator", "@WithdrawValidator", "@CertifyingValidator",
                "@VotingValidator", "@ProposingValidator"
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
        assertEquals("PlutusScriptV3-Minting", ProjectScanner.resolveScriptType("@MintingPolicy class X {}"));
        assertEquals("PlutusScriptV3-Minting", ProjectScanner.resolveScriptType("@MintingValidator class X {}"));
        assertEquals("PlutusScriptV3-Withdraw", ProjectScanner.resolveScriptType("@WithdrawValidator class X {}"));
        assertEquals("PlutusScriptV3", ProjectScanner.resolveScriptType("@Validator class X {}"));
    }
}
