package com.bloxbean.cardano.julc.processor;

import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.ValidatorOutput;
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
 * Tests for {@link JulcAnnotationProcessor} using the in-process Java compiler.
 */
class JulcAnnotationProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void compilesValidatorAndGeneratesJson() throws Exception {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
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
        assertFalse(output.isParameterized());
    }

    @Test
    void compilesMintingPolicyAndGeneratesJson() throws Exception {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
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
    void compilesParameterizedValidator() throws Exception {
        var source = """
                import com.bloxbean.cardano.julc.stdlib.annotation.*;
                import java.math.BigInteger;

                @Validator
                class ParamValidator {
                    @Param byte[] owner;
                    @Param BigInteger deadline;

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        return deadline > 0;
                    }
                }
                """;

        var result = compileWithProcessor(source, "ParamValidator");
        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics());

        Path jsonFile = tempDir.resolve("META-INF/plutus/ParamValidator.plutus.json");
        assertTrue(Files.exists(jsonFile));

        var output = ValidatorOutput.fromJson(Files.readString(jsonFile));
        assertEquals("PlutusScriptV3", output.type());
        assertTrue(output.isParameterized());
        assertEquals(2, output.paramList().size());
        assertEquals("owner", output.paramList().get(0).name());
        assertEquals("byte[]", output.paramList().get(0).type());
        assertEquals("deadline", output.paramList().get(1).name());
        assertEquals("BigInteger", output.paramList().get(1).type());
        // Hash should be empty for parameterized validators
        assertEquals("", output.hash());
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
        assertFalse(parsed.isParameterized());
    }

    @Test
    void validatorOutputParamsRoundTrips() {
        var original = new ValidatorOutput("PlutusScriptV3", "ParamValidator",
                "82015820abcdef", "", "owner:byte[],deadline:BigInteger");
        String json = original.toJson();
        var parsed = ValidatorOutput.fromJson(json);

        assertEquals(original.type(), parsed.type());
        assertEquals(original.description(), parsed.description());
        assertEquals(original.cborHex(), parsed.cborHex());
        assertEquals(original.hash(), parsed.hash());
        assertEquals(original.params(), parsed.params());
        assertTrue(parsed.isParameterized());
    }

    @Test
    void validatorOutputBackwardCompat() {
        // 4-arg constructor should default params to ""
        var output = new ValidatorOutput("PlutusScriptV3", "Simple", "abcd", "1234");
        assertEquals("", output.params());
        assertFalse(output.isParameterized());
        assertTrue(output.paramList().isEmpty());
    }

    @Test
    void validatorOutputParamList() {
        var output = new ValidatorOutput("PlutusScriptV3", "Test", "abcd", "",
                "owner:byte[],deadline:BigInteger,config:TokenConfig");
        var params = output.paramList();
        assertEquals(3, params.size());
        assertEquals("owner", params.get(0).name());
        assertEquals("byte[]", params.get(0).type());
        assertEquals("deadline", params.get(1).name());
        assertEquals("BigInteger", params.get(1).type());
        assertEquals("config", params.get(2).name());
        assertEquals("TokenConfig", params.get(2).type());
    }

    @Test
    void validatorOutputFromJsonRejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidatorOutput.fromJson("{}"));
    }

    @Test
    void validatorOutputFromJsonBackwardCompatNoParams() {
        // JSON without "params" field should still parse (old format)
        String json = """
                {
                  "type": "PlutusScriptV3",
                  "description": "OldValidator",
                  "cborHex": "abcd",
                  "hash": "1234"
                }
                """;
        var output = ValidatorOutput.fromJson(json);
        assertEquals("PlutusScriptV3", output.type());
        assertEquals("OldValidator", output.description());
        assertEquals("", output.params());
        assertFalse(output.isParameterized());
    }

    @Test
    void scriptLoaderThrowsForMissingResource() {
        // JulcScriptLoader should throw for a class with no compiled script
        assertThrows(IllegalArgumentException.class,
                () -> JulcScriptLoader.load(JulcAnnotationProcessorTest.class));
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
                    "-processor", JulcAnnotationProcessor.class.getName()
            );

            var task = javac.getTask(null, fileManager, diagnostics,
                    options, null, List.of(sourceFile));
            boolean success = task.call();

            return new CompileOutput(success, diagnostics.getDiagnostics());
        }
    }
}
