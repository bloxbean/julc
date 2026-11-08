package org.julclang.processor;

import org.julclang.clientlib.JulcScriptLoader;
import org.julclang.clientlib.ValidatorOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                @SpendingValidator
                class SimpleValidator {
                    @Entrypoint
                    static boolean validate(BigInteger datum, BigInteger redeemer, PlutusData ctx) {
                        return datum.equals(redeemer);
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
        assertEquals("spending", output.purpose());
        assertEquals("SimpleValidator", output.description());
        assertNotNull(output.cborHex());
        assertFalse(output.cborHex().isEmpty());
        assertNotNull(output.hash());
        assertEquals(56, output.hash().length(), "Script hash should be 56 hex chars (28 bytes)");
        assertFalse(output.isParameterized());
    }

    @Test
    void compilesMintingValidatorAndGeneratesJson() throws Exception {
        var source = """
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                @MintingValidator
                class SimpleMinting {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return redeemer.compareTo(BigInteger.ZERO) > 0;
                    }
                }
                """;

        var result = compileWithProcessor(source, "SimpleMinting");
        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics());

        Path jsonFile = tempDir.resolve("META-INF/plutus/SimpleMinting.plutus.json");
        assertTrue(Files.exists(jsonFile));

        var output = ValidatorOutput.fromJson(Files.readString(jsonFile));
        assertEquals("PlutusScriptV3", output.type());
        assertEquals("minting", output.purpose());
        assertEquals("SimpleMinting", output.description());
    }

    @Test
    void compilesParameterizedValidator() throws Exception {
        var source = """
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                @SpendingValidator
                class ParamValidator {
                    @Param byte[] owner;
                    @Param static BigInteger deadline;

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return deadline.compareTo(BigInteger.ZERO) > 0;
                    }
                }
                """;

        var result = compileWithProcessor(source, "ParamValidator");
        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics());

        Path jsonFile = tempDir.resolve("META-INF/plutus/ParamValidator.plutus.json");
        assertTrue(Files.exists(jsonFile));

        var output = ValidatorOutput.fromJson(Files.readString(jsonFile));
        assertEquals("PlutusScriptV3", output.type());
        assertEquals("spending", output.purpose());
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
        assertEquals(original.purpose(), parsed.purpose());
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
        assertEquals(original.purpose(), parsed.purpose());
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
        assertEquals("", output.purpose());
        assertEquals("OldValidator", output.description());
        assertEquals("", output.params());
        assertFalse(output.isParameterized());
    }

    @Test
    void generatesBlueprintJson() throws Exception {
        var source = """
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                @SpendingValidator
                class BpValidator {
                    @Entrypoint
                    static boolean validate(BigInteger datum, BigInteger redeemer, PlutusData ctx) {
                        return datum.equals(redeemer);
                    }
                }
                """;

        var result = compileWithProcessor(source, "BpValidator");
        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics());

        // Verify CIP-57 blueprint was generated
        Path blueprintFile = tempDir.resolve("META-INF/plutus/plutus.json");
        assertTrue(Files.exists(blueprintFile), "CIP-57 blueprint should exist at " + blueprintFile);

        String json = Files.readString(blueprintFile);
        assertTrue(json.contains("\"preamble\""), "Blueprint should have preamble");
        assertTrue(json.contains("\"validators\""), "Blueprint should have validators");
        assertTrue(json.contains("\"title\": \"BpValidator\""), "Blueprint should contain validator title");
        assertTrue(json.contains("\"compiledCode\""), "Blueprint should contain compiledCode");
        assertTrue(json.contains("\"hash\""), "Blueprint should contain hash");
    }

    @Test
    void blueprintUsesProcessorOptions() throws Exception {
        var source = """
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                @SpendingValidator
                class OptValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """;

        var result = compileWithProcessorAndOptions(source, "OptValidator",
                List.of("-Ajulc.projectName=my-project", "-Ajulc.projectVersion=1.2.3"));
        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics());

        Path blueprintFile = tempDir.resolve("META-INF/plutus/plutus.json");
        assertTrue(Files.exists(blueprintFile));

        String json = Files.readString(blueprintFile);
        assertTrue(json.contains("\"title\": \"my-project\""), "Blueprint preamble should use project name option");
        assertTrue(json.contains("\"version\": \"1.2.3\""), "Blueprint preamble should use project version option");
    }

    @Test
    void targetOptionIsExactAndReported() throws Exception {
        var source = """
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                @SpendingValidator class TargetValidator {
                    @Entrypoint static boolean validate(BigInteger r, PlutusData c) {
                        return true;
                    }
                }
                """;

        var success = compileWithProcessorAndOptions(source, "TargetValidator", List.of(
                "-Ajulc.target=plutus-v3-pv11-uplc-1.1.0"));
        assertTrue(success.success(), success.diagnostics().toString());
        assertTrue(success.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.getMessage(null).contains(
                        "target: plutus-v3-pv11-uplc-1.1.0")));

        var failure = compileWithProcessorAndOptions(source, "TargetValidator", List.of(
                "-Ajulc.target=plutus-v3-pv12-uplc-1.1.0"));
        assertFalse(failure.success());
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.getMessage(null).contains("not supported")));
    }

    @Test
    void optimizationOptionsAreExactAndReported() throws Exception {
        var source = """
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                @SpendingValidator class OptimizationValidator {
                    @Entrypoint static boolean validate(BigInteger r, PlutusData c) {
                        return true;
                    }
                }
                """;

        for (var options : List.of(
                List.of("-Ajulc.optimization=pv11-costed"),
                List.of("-Ajulc.optimization=pv11-costed", "-Ajulc.costProfile=plutus-v3-pv11-costs-v1"),
                List.of("-Ajulc.optimization=pv11-costed", "-Ajulc.costProfile=cardano-node-11.0.1-plutus-v3-pv11"))) {
            var success = compileWithProcessorAndOptions(source, "OptimizationValidator", options);
            assertTrue(success.success(), success.diagnostics().toString());
            assertTrue(success.diagnostics().stream().anyMatch(diagnostic ->
                    diagnostic.getMessage(null).contains("optimization: pv11-costed")));
        }

        var unknown = compileWithProcessorAndOptions(source, "OptimizationValidator",
                List.of("-Ajulc.optimization=pv11-costed", "-Ajulc.costProfile=latest"));
        assertFalse(unknown.success());
        assertTrue(unknown.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("not supported")));

        var failure = compileWithProcessorAndOptions(
                source, "OptimizationValidator", List.of(
                        "-Ajulc.optimization=PV11_COSTED"));
        assertFalse(failure.success());
        assertTrue(failure.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.getMessage(null).contains("not supported")));
    }

    @Test
    void optimizationDefaultsToPv11Safe() throws Exception {
        var source = """
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                @SpendingValidator class DefaultOptimizationValidator {
                    @Entrypoint static boolean validate(BigInteger r, PlutusData c) {
                        return true;
                    }
                }
                """;

        var result = compileWithProcessor(
                source, "DefaultOptimizationValidator");

        assertTrue(result.success(), result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.getMessage(null).contains("optimization: pv11-safe")));
    }

    @Test
    void blueprintDefaultsWhenNoOptions() throws Exception {
        var source = """
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                @SpendingValidator
                class DefValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """;

        var result = compileWithProcessor(source, "DefValidator");
        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics());

        Path blueprintFile = tempDir.resolve("META-INF/plutus/plutus.json");
        assertTrue(Files.exists(blueprintFile));

        String json = Files.readString(blueprintFile);
        assertTrue(json.contains("\"title\": \"julc-project\""), "Blueprint should use default project name");
        assertTrue(json.contains("\"version\": \"0.0.0\""), "Blueprint should use default project version");
    }

    @Test
    void blueprintCanBeDisabledWithoutDisablingCompilation() throws Exception {
        var source = """
                import org.julclang.stdlib.annotation.*;
                import org.julclang.core.PlutusData;

                @MultiValidator
                class MultiWithoutBlueprint {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """;

        var result = compileWithProcessorAndOptions(source, "MultiWithoutBlueprint",
                List.of("-Ajulc.blueprint=false"));

        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics());
        assertTrue(Files.exists(tempDir.resolve(
                "META-INF/plutus/MultiWithoutBlueprint.plutus.json")));
        assertFalse(Files.exists(tempDir.resolve("META-INF/plutus/plutus.json")));
    }

    @Test
    void blueprintEnabledPublishesExplicitMultiPurposeInterfaces() throws Exception {
        var source = """
                import org.julclang.stdlib.annotation.*;
                import org.julclang.ledger.ScriptContext;
                import java.math.BigInteger;
                @MultiValidator class MultiBlueprint {
                    record Datum(BigInteger value) {}
                    record Spend(BigInteger value) {}
                    record Mint(byte[] tokenName) {}
                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(Datum datum, Spend redeemer, ScriptContext ctx) {
                        return true;
                    }
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(Mint redeemer, ScriptContext ctx) { return true; }
                }
                """;

        var result = compileWithProcessor(source, "MultiBlueprint");
        assertTrue(result.success(), "Compilation should succeed: " + result.diagnostics());
        String json = Files.readString(tempDir.resolve("META-INF/plutus/plutus.json"));
        assertTrue(json.contains("\"title\": \"MultiBlueprint.mint\""));
        assertTrue(json.contains("\"title\": \"MultiBlueprint.spend\""));
        assertTrue(json.contains("\"purpose\": \"mint\""));
        assertTrue(json.contains("\"purpose\": \"spend\""));
    }

    @Test
    void failedProcessingNeverPublishesAPartialAggregateBlueprint() throws Exception {
        var sources = new LinkedHashMap<String, String>();
        sources.put("GoodValidator", """
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                @MintingValidator
                class GoodValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """);
        sources.put("BrokenValidator", """
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                @MintingValidator
                class BrokenValidator {
                    @Entrypoint
                    static boolean validate(float redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """);

        var result = compileWithProcessorSources(sources, List.of());

        assertFalse(result.success());
        assertFalse(Files.exists(tempDir.resolve("META-INF/plutus/plutus.json")),
                "a failed annotation-processing build must not publish a partial aggregate");
    }

    @Test
    void scriptLoaderThrowsForMissingResource() {
        // JulcScriptLoader should throw for a class with no compiled script
        assertThrows(IllegalArgumentException.class,
                () -> JulcScriptLoader.load(JulcAnnotationProcessorTest.class));
    }

    @Test
    void rejectsNestedOnchainLibrary() throws Exception {
        var source = """
                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                @SpendingValidator
                class UsesNested {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return true;
                    }
                }

                class Outer {
                    @OnchainLibrary
                    static class Inner {
                        public static boolean check() {
                            return true;
                        }
                    }
                }
                """;

        var result = compileWithProcessor(source, "UsesNested");

        assertFalse(result.success(), "Nested @OnchainLibrary should be rejected");
        assertTrue(result.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.getMessage(null).contains("top-level class")),
                "Expected top-level diagnostic, got: " + result.diagnostics());
    }

    // --- ADR-071 D3: JOUST's @StateContract is an alias of @OnchainLibrary ---

    static final String COUNTER = """
            package app;

            import java.math.BigInteger;
            import org.julclang.state.api.StateContract;

            @StateContract
            public class Counter {
                public static BigInteger twice(BigInteger x) {
                    return x.add(x);
                }
            }
            """;

    static final String USES_COUNTER = """
            package app;

            import org.julclang.stdlib.annotation.*;
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;

            @SpendingValidator
            public class UsesCounter {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
                    return Counter.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                }
            }
            """;

    /** The test resources' stand-in for JOUST's annotation, which its API jar ships in META-INF/plutus-sources. */
    private String stateContractSource() throws Exception {
        return Files.readString(Path.of(getClass().getResource(
                "/META-INF/plutus-sources/org/julclang/state/api/StateContract.java").toURI()));
    }

    /** A @StateContract class is a library: a validator calls it by its bare name, with no import, as any library. */
    @Test
    void aStateContractClassIsALibrary() throws Exception {
        var result = compileWithProcessorSources(Map.of("org/julclang/state/api/StateContract", stateContractSource(),
                "app/Counter", COUNTER, "app/UsesCounter", USES_COUNTER), List.of());
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("with 2 library file(s)")),
                "Expected Counter and StateContract, got: " + result.diagnostics());
    }

    /** A @StateContract class compiles to the same script as the same class marked @OnchainLibrary. */
    @Test
    void aStateContractClassCompilesToTheSameScriptAsAnOnchainLibraryOne() throws Exception {
        String direct = COUNTER.replace("import org.julclang.state.api.StateContract;\n", "")
                .replace("@StateContract", "@org.julclang.stdlib.annotation.OnchainLibrary");
        for (var options : List.of(List.<String>of(), List.of("-Ajulc.optimization=none"),
                List.of("-Ajulc.sourceMap=true"))) {
            var marked = compileWithProcessorSources(Map.of("org/julclang/state/api/StateContract",
                    stateContractSource(), "app/Counter", COUNTER, "app/UsesCounter", USES_COUNTER), options);
            assertTrue(marked.success(), "Expected success, got: " + marked.diagnostics());
            String markedScript = script("UsesCounter");
            var plain = compileWithProcessorSources(Map.of("app/Counter", direct, "app/UsesCounter", USES_COUNTER),
                    options);
            assertTrue(plain.success(), "Expected success, got: " + plain.diagnostics());
            assertEquals(script("UsesCounter"), markedScript, "options " + options);
        }
    }

    /**
     * JOUST's processor generates the validator in a later round and must still receive @StateContract, whichever
     * processor runs first: JuLC does not claim a round holding the alias.
     */
    @Test
    void aFrameworkReceivesStateContractAndItsGeneratedValidatorCompiles() throws Exception {
        for (boolean julcFirst : List.of(true, false)) {
            var framework = new StateContractFramework(Map.of("app.UsesCounter", USES_COUNTER));
            var julc = new JulcAnnotationProcessor();
            var result = compileWithProcessors(Map.of("org/julclang/state/api/StateContract", stateContractSource(),
                    "app/Counter", COUNTER), List.of(), julcFirst ? List.of(julc, framework) : List.of(framework, julc));
            assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
            assertEquals(List.of("Counter"), framework.received, "julcFirst=" + julcFirst);
            assertTrue(Files.exists(tempDir.resolve("META-INF/plutus/UsesCounter.plutus.json")), "julcFirst=" + julcFirst);
        }
    }

    @Test
    void aNestedStateContractClassIsRejected() throws Exception {
        String nested = """
                package app;

                import java.math.BigInteger;
                import org.julclang.state.api.StateContract;

                public class Outer {
                    @StateContract
                    public static class Counter {
                        public static BigInteger twice(BigInteger x) {
                            return x.add(x);
                        }
                    }
                }
                """;
        var result = compileWithProcessorSources(Map.of("org/julclang/state/api/StateContract", stateContractSource(),
                "app/Outer", nested), List.of());
        assertFalse(result.success(), "Expected failure, got: " + result.diagnostics());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).equals(
                "@StateContract must be declared on a top-level class. Nested on-chain libraries are not supported.")),
                "Expected the top-level diagnostic, got: " + result.diagnostics());
    }

    /** A compilation of libraries alone: JuLC still claims its annotations, so -Xlint:processing has nothing to say. */
    @Test
    void aLibraryOnlyCompilationClaimsItsAnnotationsAsBefore() throws Exception {
        String direct = COUNTER.replace("import org.julclang.state.api.StateContract;\n", "")
                .replace("@StateContract", "@org.julclang.stdlib.annotation.OnchainLibrary");
        var result = compileWithProcessorSources(Map.of("app/Counter", direct), List.of("-Xlint:processing", "-Werror"));
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    /** A stand-in for JOUST's processor: it supports @StateContract, never claims it, and generates validators. */
    private static final class StateContractFramework extends AbstractProcessor {
        final List<String> received = new ArrayList<>();
        private final Map<String, String> generated;
        private boolean done;

        StateContractFramework(Map<String, String> generated) {
            this.generated = generated;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("org.julclang.state.api.StateContract");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            for (TypeElement annotation : annotations) {
                roundEnv.getElementsAnnotatedWith(annotation).forEach(e -> received.add(e.getSimpleName().toString()));
            }
            if (!done && !annotations.isEmpty()) {
                done = true;
                generated.forEach((name, source) -> {
                    try (var writer = processingEnv.getFiler().createSourceFile(name).openWriter()) {
                        writer.write(source);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            return false;
        }
    }

    private String script(String validator) throws IOException {
        return ValidatorOutput.fromJson(Files.readString(
                tempDir.resolve("META-INF/plutus/" + validator + ".plutus.json"))).cborHex();
    }

    // --- Compilation infrastructure ---

    record CompileOutput(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {}

    private CompileOutput compileWithProcessor(String source, String className) throws IOException {
        return compileWithProcessorAndOptions(source, className, List.of());
    }

    private CompileOutput compileWithProcessorAndOptions(String source, String className,
                                                         List<String> extraOptions) throws IOException {
        return compileWithProcessorSources(Map.of(className, source), extraOptions);
    }

    private CompileOutput compileWithProcessors(Map<String, String> sources, List<String> extraOptions,
                                                List<Processor> processors) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = javac.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempDir.toFile()));
            Path generatedDir = tempDir.resolve("generated-sources");
            Files.createDirectories(generatedDir);
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generatedDir.toFile()));
            var options = new ArrayList<>(List.of("--enable-preview", "--source", "25", "-proc:only"));
            options.addAll(extraOptions);
            var sourceFiles = sources.entrySet().stream().map(entry -> (JavaFileObject) new SimpleJavaFileObject(
                    URI.create("string:///" + entry.getKey() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return entry.getValue();
                }
            }).toList();
            var task = javac.getTask(null, fileManager, diagnostics, options, null, sourceFiles);
            task.setProcessors(processors);
            return new CompileOutput(task.call(), diagnostics.getDiagnostics());
        }
    }

    private CompileOutput compileWithProcessorSources(Map<String, String> sources,
                                                       List<String> extraOptions) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "System Java compiler must be available");

        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = javac.getStandardFileManager(diagnostics, null, null)) {
            // Set output directory
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempDir.toFile()));

            // Create in-memory source files
            var sourceFiles = sources.entrySet().stream().map(entry ->
                    new SimpleJavaFileObject(
                            URI.create("string:///" + entry.getKey() + ".java"),
                            JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                            return entry.getValue();
                        }
                    }).toList();

            var options = new ArrayList<>(List.of(
                    "--enable-preview",
                    "--source", "25",
                    "-proc:only",
                    "-processor", JulcAnnotationProcessor.class.getName()
            ));
            options.addAll(extraOptions);

            var task = javac.getTask(null, fileManager, diagnostics,
                    options, null, sourceFiles);
            boolean success = task.call();

            return new CompileOutput(success, diagnostics.getDiagnostics());
        }
    }
}
