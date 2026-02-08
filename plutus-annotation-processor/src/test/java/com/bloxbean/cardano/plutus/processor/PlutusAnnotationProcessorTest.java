package com.bloxbean.cardano.plutus.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PlutusAnnotationProcessor} using the in-process Java compiler.
 */
class PlutusAnnotationProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void compilesValidatorAndGeneratesJson() throws Exception {
        var source = """
                import com.bloxbean.cardano.plutus.onchain.annotation.*;
                import java.math.BigInteger;

                @Validator
                class SimpleValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return redeemer == ctx;
                    }
                }
                """;

        var result = compileWithProcessor(source, "SimpleValidator");
        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics());

        // Verify JSON output was generated
        Path jsonFile = tempDir.resolve("META-INF/plutus/SimpleValidator.plutus.json");
        assertTrue(Files.exists(jsonFile), "JSON output should exist at " + jsonFile);

        String json = Files.readString(jsonFile);
        var output = ValidatorOutput.fromJson(json);
        assertEquals("PlutusScriptV3", output.type());
        assertEquals("SimpleValidator", output.description());
        assertNotNull(output.cborHex());
        assertFalse(output.cborHex().isEmpty());
        assertNotNull(output.hash());
        assertEquals(56, output.hash().length(), "Script hash should be 56 hex chars (28 bytes)");
    }

    @Test
    void compilesMintingPolicyAndGeneratesJson() throws Exception {
        var source = """
                import com.bloxbean.cardano.plutus.onchain.annotation.*;
                import java.math.BigInteger;

                @MintingPolicy
                class SimpleMinting {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return redeemer > 0;
                    }
                }
                """;

        var result = compileWithProcessor(source, "SimpleMinting");
        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics());

        Path jsonFile = tempDir.resolve("META-INF/plutus/SimpleMinting.plutus.json");
        assertTrue(Files.exists(jsonFile));

        var output = ValidatorOutput.fromJson(Files.readString(jsonFile));
        assertEquals("PlutusScriptV3-Minting", output.type());
        assertEquals("SimpleMinting", output.description());
    }

    @Test
    void validatorOutputFromJsonRoundTrips() {
        var original = new ValidatorOutput("PlutusScriptV3", "TestValidator",
                "82015820abcdef", "aabbccdd11223344");
        String json = original.toJson();
        var parsed = ValidatorOutput.fromJson(json);

        assertEquals(original.type(), parsed.type());
        assertEquals(original.description(), parsed.description());
        assertEquals(original.cborHex(), parsed.cborHex());
        assertEquals(original.hash(), parsed.hash());
    }

    @Test
    void validatorOutputFromJsonRejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidatorOutput.fromJson("{}"));
    }

    @Test
    void scriptLoaderThrowsForMissingResource() {
        // PlutusScriptLoader should throw for a class with no compiled script
        assertThrows(IllegalArgumentException.class,
                () -> PlutusScriptLoader.load(PlutusAnnotationProcessorTest.class));
    }

    // --- Compilation infrastructure ---

    record CompileOutput(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {}

    private CompileOutput compileWithProcessor(String source, String className) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "System Java compiler must be available");

        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = javac.getStandardFileManager(diagnostics, null, null)) {
            // Set output directory
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempDir.toFile()));

            // Create in-memory source file
            var sourceFile = new SimpleJavaFileObject(
                    URI.create("string:///" + className + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };

            var options = List.of(
                    "--enable-preview",
                    "--source", "24",
                    "-proc:only",
                    "-processor", PlutusAnnotationProcessor.class.getName()
            );

            var task = javac.getTask(null, fileManager, diagnostics,
                    options, null, List.of(sourceFile));
            boolean success = task.call();

            return new CompileOutput(success, diagnostics.getDiagnostics());
        }
    }
}
