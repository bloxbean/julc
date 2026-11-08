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
import java.io.File;
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


    // --- ADR-071 D1: on-chain libraries marked by a meta-annotated annotation ---

    static final String STATE_LIKE = """
            package lib;

            import org.julclang.stdlib.annotation.OnchainLibrary;
            import java.lang.annotation.*;

            @OnchainLibrary
            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.TYPE)
            public @interface StateLike {}
            """;

    static final String META_LIBRARY = """
            package lib;

            import java.math.BigInteger;

            @StateLike
            public class MetaMath {
                public static BigInteger twice(BigInteger x) {
                    return x.add(x);
                }
            }
            """;

    static final String USES_META = """
            package app;

            import org.julclang.stdlib.annotation.*;
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import lib.MetaMath;

            @SpendingValidator
            public class UsesMeta {
                @Entrypoint
                static boolean validate(BigInteger redeemer, PlutusData ctx) {
                    return MetaMath.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                }
            }
            """;

    @Test
    void metaAnnotatedLibraryIsCompiledIntoTheValidator() throws Exception {
        var result = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib/MetaMath", META_LIBRARY,
                "app/UsesMeta", USES_META), List.of());
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
        assertTrue(Files.exists(tempDir.resolve("META-INF/plutus/UsesMeta.plutus.json")));
    }

    /** The test resource's marker: a framework annotation from the class path, its source in META-INF/plutus-sources. */
    static final String STATE_MARKER = "org.julclang.processor.testmarker.StateMarker";

    static final String MARKED_COUNTER = """
            package app;

            import java.math.BigInteger;
            import org.julclang.processor.testmarker.StateMarker;

            @StateMarker
            public class Counter {
                public static BigInteger twice(BigInteger x) {
                    return x.add(x);
                }
            }
            """;

    /**
     * The framework case: the marker annotation comes from the class path (its source shipped as a library source in
     * META-INF/plutus-sources, here a test resource), and the validator calling the library is generated by another
     * processor in a later round. The framework claims its annotation and runs first, so javac never calls JuLC's
     * processor in round 1: the library is admitted by the exact name the generated validator uses.
     */
    @Test
    void metaAnnotatedLibraryIsDiscoveredForAValidatorGeneratedInALaterRound() throws Exception {
        var result = compileWithFramework(Map.of("app/Counter", MARKED_COUNTER), Map.of("app.UsesMeta", """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import app.Counter;

                @SpendingValidator
                public class UsesMeta {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Counter.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """), true);
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
        assertTrue(Files.exists(tempDir.resolve("META-INF/plutus/UsesMeta.plutus.json")));
    }

    /** A framework's generated validator imports the marked classes it calls; a wildcard import is not enough. */
    @Test
    void aWildcardImportDoesNotFindAMarkedClass() throws Exception {
        var result = compileWithFramework(Map.of("app/Counter", MARKED_COUNTER), Map.of("v.UsesMeta", """
                package v;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import app.*;

                @SpendingValidator
                public class UsesMeta {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Counter.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """), true);
        // ADR-071 D1: a marked class is reached through a single-type import; a wildcard import fails as before.
        assertFalse(result.success(), "A wildcard import must not find a marked class");
    }

    @Test
    void aGeneratedLibraryReachesAMetaAnnotatedLibraryOfAnEarlierRound() throws Exception {
        var result = compileWithFramework(Map.of("app/Counter", MARKED_COUNTER), Map.of(
                "app.Helper", """
                        package app;

                        import org.julclang.stdlib.annotation.OnchainLibrary;
                        import java.math.BigInteger;
                        import app.Counter;

                        @OnchainLibrary
                        public class Helper {
                            public static BigInteger four(BigInteger x) {
                                return Counter.twice(Counter.twice(x));
                            }
                        }
                        """,
                "app.GenV", """
                        package app;

                        import org.julclang.stdlib.annotation.*;
                        import java.math.BigInteger;
                        import org.julclang.core.PlutusData;

                        @SpendingValidator
                        public class GenV {
                            @Entrypoint
                            static boolean validate(BigInteger redeemer, PlutusData ctx) {
                                return Helper.four(redeemer).compareTo(BigInteger.TEN) == 0;
                            }
                        }
                        """), true);
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    /** JuLC's processor runs first here: a round holding the framework's annotation must still reach the framework. */
    @Test
    void aRoundWithAFrameworkAnnotationIsLeftToTheFramework() throws Exception {
        var result = compileWithFramework(Map.of("app/Counter", MARKED_COUNTER), Map.of("app.UsesMeta", """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import app.Counter;

                @SpendingValidator
                public class UsesMeta {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Counter.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """), false);
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
        assertTrue(Files.exists(tempDir.resolve("META-INF/plutus/UsesMeta.plutus.json")),
                "The framework never saw @StateMarker: " + result.diagnostics());
    }

    @Test
    void aNestedMetaAnnotatedLibraryIsRejectedWhenTheValidatorIsGeneratedInALaterRound() throws Exception {
        var holder = """
                package app;

                import java.math.BigInteger;
                import org.julclang.processor.testmarker.StateMarker;

                @StateMarker
                public class Holder {
                    public static BigInteger twice(BigInteger x) {
                        return x.add(x);
                    }

                    @StateMarker
                    public static class Inner {
                        public static boolean ok() {
                            return true;
                        }
                    }
                }
                """;
        var result = compileWithFramework(Map.of("app/Holder", holder), Map.of("app.UsesHolder", """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import app.Holder;

                @SpendingValidator
                public class UsesHolder {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Holder.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """), true);
        assertFalse(result.success(), "A nested meta-annotated library should be rejected");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("@StateMarker makes Inner")
                && d.getMessage(null).contains("top-level class")), "Got: " + result.diagnostics());
    }

    /** The outer class is not a library itself: naming it is enough for its nested library class to be rejected. */
    @Test
    void rejectsNestedMetaAnnotatedLibrary() throws Exception {
        var nested = """
                package lib;

                public class Holder {
                    public static boolean ok() {
                        return true;
                    }

                    @StateLike
                    public static class Inner {
                        public static boolean check() {
                            return true;
                        }
                    }
                }
                """;
        var validator = """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import lib.Holder;

                @SpendingValidator
                public class UsesHolder {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Holder.ok();
                    }
                }
                """;
        var result = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib/Holder", nested,
                "app/UsesHolder", validator), List.of());
        assertFalse(result.success(), "A nested meta-annotated library should be rejected");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("@StateLike makes Inner")
                        && d.getMessage(null).contains("top-level class")), "Got: " + result.diagnostics());
    }

    @Test
    void aNestedDirectLibraryKeepsItsMessage() throws Exception {
        var nested = """
                import org.julclang.stdlib.annotation.OnchainLibrary;

                public class Holder {
                    @OnchainLibrary
                    public static class Inner {
                        public static boolean check() {
                            return true;
                        }
                    }
                }
                """;
        var result = compileWithProcessorSources(Map.of("Holder", nested), List.of());
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).equals(
                "@OnchainLibrary must be declared on a top-level class. Nested on-chain libraries are not supported.")),
                "Got: " + result.diagnostics());
    }

    @Test
    void aLibraryWithTwoMarkersCompilesWithBoth() throws Exception {
        String other = STATE_LIKE.replace("StateLike", "OtherLike");
        String library = META_LIBRARY.replace("@StateLike", "@StateLike @OtherLike");
        var result = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib/OtherLike", other,
                "lib/MetaMath", library, "app/UsesMeta", USES_META), List.of());
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    @Test
    void aMarkerDefaultThatReadsALibraryConstantCompiles() throws Exception {
        String scaled = """
                package lib;

                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.lang.annotation.*;

                @OnchainLibrary
                @Retention(RetentionPolicy.CLASS)
                @Target(ElementType.TYPE)
                public @interface StateLike {
                    int value() default Limits.N;
                }
                """;
        String limits = """
                package lib;

                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class Limits {
                    public static final int N = 2;
                }
                """;
        var result = compileWithProcessorSources(Map.of("lib/StateLike", scaled, "lib/Limits", limits,
                "lib/MetaMath", META_LIBRARY, "app/UsesMeta", USES_META), List.of());
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    @Test
    void aValidatorMarkedThroughASamePackageMarkerIsJulc0134() throws Exception {
        var both = """
                package lib;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                @StateLike
                @SpendingValidator
                public class Both {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return true;
                    }
                }
                """;
        var result = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib/Both", both), List.of());
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains(
                "must not combine @StateLike (which implies @OnchainLibrary) with validator annotation(s): @SpendingValidator")),
                "Got: " + result.diagnostics());
    }

    @Test
    void aMetaAnnotatedLibraryCompilesToTheSameScriptAsADirectOne() throws Exception {
        var meta = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib/MetaMath", META_LIBRARY,
                "app/UsesMeta", USES_META), List.of());
        assertTrue(meta.success(), "Expected success, got: " + meta.diagnostics());
        String metaScript = ValidatorOutput.fromJson(Files.readString(
                tempDir.resolve("META-INF/plutus/UsesMeta.plutus.json"))).cborHex();

        String direct = META_LIBRARY.replace("@StateLike", "@org.julclang.stdlib.annotation.OnchainLibrary");
        var plain = compileWithProcessorSources(Map.of("lib/MetaMath", direct, "app/UsesMeta", USES_META), List.of());
        assertTrue(plain.success(), "Expected success, got: " + plain.diagnostics());
        assertEquals(ValidatorOutput.fromJson(Files.readString(
                tempDir.resolve("META-INF/plutus/UsesMeta.plutus.json"))).cborHex(), metaScript);
    }

    /**
     * The library order is the order the validator names them, marked or not, so the script is the same without
     * optimization and with source maps, where the order shows (ADR-071 I3).
     */
    @Test
    void aMarkedLibraryKeepsItsPlaceInTheLibraryOrder() throws Exception {
        String other = """
                package lib;

                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;

                @OnchainLibrary
                public class Other {
                    public static BigInteger thrice(BigInteger x) {
                        return x.add(x).add(x);
                    }
                }
                """;
        String validator = """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import lib.MetaMath;
                import lib.Other;

                @SpendingValidator
                public class UsesBoth {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return MetaMath.twice(redeemer).compareTo(Other.thrice(redeemer)) < 0;
                    }
                }
                """;
        String direct = META_LIBRARY.replace("@StateLike", "@org.julclang.stdlib.annotation.OnchainLibrary");
        for (var options : List.of(List.of("-Ajulc.optimization=none"), List.of("-Ajulc.sourceMap=true"))) {
            var marked = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib/MetaMath", META_LIBRARY,
                    "lib/Other", other, "app/UsesBoth", validator), options);
            assertTrue(marked.success(), "Expected success, got: " + marked.diagnostics());
            String markedScript = script("UsesBoth");
            var plain = compileWithProcessorSources(Map.of("lib/MetaMath", direct, "lib/Other", other, "app/UsesBoth", validator),
                    options);
            assertTrue(plain.success(), "Expected success, got: " + plain.diagnostics());
            assertEquals(script("UsesBoth"), markedScript, options.toString());
        }
    }

    /** ADR-071 D1: a marked class is found only through a single-type import, even from its own package. */
    @Test
    void aBareSamePackageCallToAMarkedClassFailsWithTheImportToAdd() throws Exception {
        String validator = """
                package lib;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                @SpendingValidator
                public class UsesBare {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return MetaMath.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """;
        var bare = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib/MetaMath", META_LIBRARY,
                "lib/UsesBare", validator), List.of());
        assertFalse(bare.success());
        assertTrue(bare.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains(
                "MetaMath is an on-chain library through @StateLike; JuLC finds such a class only through a"
                        + " single-type import, even from its own package: add `import lib.MetaMath;`.")),
                "Got: " + bare.diagnostics());

        var imported = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib/MetaMath", META_LIBRARY,
                "lib/UsesBare", validator.replace("import org.julclang.core.PlutusData;",
                        "import org.julclang.core.PlutusData;\nimport lib.MetaMath;")), List.of());
        assertTrue(imported.success(), "Expected success, got: " + imported.diagnostics());
    }

    /** A library that calls a marked library needs the import too. */
    @Test
    void aLibraryCallingAMarkedLibraryImportsIt() throws Exception {
        String helper = """
                package lib;

                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;

                @OnchainLibrary
                public class Helper {
                    public static BigInteger four(BigInteger x) {
                        return MetaMath.twice(MetaMath.twice(x));
                    }
                }
                """;
        String validator = """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import lib.Helper;

                @SpendingValidator
                public class UsesHelper {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Helper.four(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """;
        var bare = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib/MetaMath", META_LIBRARY,
                "lib/Helper", helper, "app/UsesHelper", validator), List.of());
        assertFalse(bare.success());
        assertTrue(bare.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("add `import lib.MetaMath;`")),
                "Got: " + bare.diagnostics());

        var imported = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib/MetaMath", META_LIBRARY,
                "lib/Helper", helper.replace("import java.math.BigInteger;", "import java.math.BigInteger;\nimport lib.MetaMath;"),
                "app/UsesHelper", validator), List.of());
        assertTrue(imported.success(), "Expected success, got: " + imported.diagnostics());
    }

    /** A marker is not followed through a wildcard import; the failure names the import to add. */
    @Test
    void aMarkerReachedThroughAWildcardImportFailsWithTheImportToAdd() throws Exception {
        String library = """
                package lib2;

                import java.math.BigInteger;
                import lib.*;

                @StateLike
                public class Wild {
                    public static BigInteger twice(BigInteger x) {
                        return x.add(x);
                    }
                }
                """;
        String validator = """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import lib2.Wild;

                @SpendingValidator
                public class UsesWild {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Wild.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """;
        var result = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib2/Wild", library,
                "app/UsesWild", validator), List.of());
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("add `import lib.StateLike;`")),
                "Got: " + result.diagnostics());
    }

    /** Regression: a package line read by javac and by JuLC's pattern alike, with a space before the semicolon. */
    @Test
    void librariesWhosePackageLineHasASpaceStillResolve() throws Exception {
        String lib = """
                package %s ;

                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;

                @OnchainLibrary
                public class Lib {
                    public static BigInteger twice(BigInteger x) {
                        return x.add(x);
                    }
                }
                """;
        var validator = """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import p.*;

                @SpendingValidator
                public class V {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Lib.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """;
        var sources = new LinkedHashMap<String, String>();
        sources.put("q/Lib", lib.formatted("q"));
        sources.put("p/Lib", lib.formatted("p"));
        sources.put("app/V", validator);
        var result = compileWithProcessorSources(sources, List.of());
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    /** A compilation of libraries alone: JuLC still claims its annotations, so -Xlint:processing has nothing to say. */
    @Test
    void aLibraryOnlyCompilationClaimsItsAnnotationsAsBefore() throws Exception {
        String direct = META_LIBRARY.replace("@StateLike", "@org.julclang.stdlib.annotation.OnchainLibrary");
        var result = compileWithProcessorSources(Map.of("lib/MetaMath", direct), List.of("-Xlint:processing", "-Werror"));
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    /**
     * A class-path source of the same name as a marked class of this compilation is compiled, as before ADR-071, and a
     * note says so: the test resources ship {@code org.julclang.processor.testshadow.Shadowed} returning
     * {@code x + x + 1000}, a stale copy of the project's class.
     */
    @Test
    void aClassPathCopyOfAMarkedClassIsCompiledAsBeforeWithANote() throws Exception {
        String own = """
                package org.julclang.processor.testshadow;

                import java.math.BigInteger;
                import org.julclang.processor.testmarker.StateMarker;

                @StateMarker
                public class Shadowed {
                    public static BigInteger twice(BigInteger x) {
                        return x.add(x);
                    }
                }
                """;
        String validator = """
                package org.julclang.processor.testshadow;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.processor.testshadow.Shadowed;

                @SpendingValidator
                public class UsesShadowed {
                    static boolean positive(BigInteger Shadowed) {
                        return Shadowed.signum() > 0;
                    }

                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Shadowed.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """;
        var marked = compileWithFramework(Map.of("org/julclang/processor/testshadow/Shadowed", own),
                Map.of("org.julclang.processor.testshadow.UsesShadowed", validator), true);
        assertTrue(marked.success(), "Expected success, got: " + marked.diagnostics());
        assertTrue(marked.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.NOTE
                && d.getMessage(null).contains("JuLC compiles org.julclang.processor.testshadow.Shadowed from the on-chain"
                + " library source")), "Got: " + marked.diagnostics());
    }

    @Test
    void aMarkerWithoutASourceIsReportedWithTheRemedy() throws Exception {
        Path markerClasses = tempDir.resolve("nosrc-classes");
        Files.createDirectories(markerClasses);
        compileClasses(Map.of("fw/NoSrc", """
                package fw;

                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.lang.annotation.*;

                @OnchainLibrary
                @Retention(RetentionPolicy.CLASS)
                @Target(ElementType.TYPE)
                public @interface NoSrc {}
                """), markerClasses);
        var library = """
                package lib;

                import java.math.BigInteger;
                import fw.NoSrc;

                @NoSrc
                public class MetaMath {
                    public static BigInteger twice(BigInteger x) {
                        return x.add(x);
                    }
                }
                """;
        var result = compileWithProcessors(Map.of("lib/MetaMath", library, "app/UsesMeta", USES_META),
                List.of("-classpath", markerClasses + File.pathSeparator + System.getProperty("java.class.path")),
                List.of(new JulcAnnotationProcessor()));
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains(
                "@NoSrc makes MetaMath an on-chain library, but JuLC has no source for fw.NoSrc")
                && d.getMessage(null).contains("META-INF/plutus-sources")), "Got: " + result.diagnostics());
    }

    /** A library javac finds only on -sourcepath was never collected; it is admitted by the name the validator uses. */
    @Test
    void aLibraryOnlyOnTheSourcePathIsAdmitted() throws Exception {
        Path sourcePath = tempDir.resolve("sourcepath");
        Files.createDirectories(sourcePath.resolve("lib"));
        Files.writeString(sourcePath.resolve("lib/Helper.java"), """
                package lib;

                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;

                @OnchainLibrary
                public class Helper {
                    public static BigInteger twice(BigInteger x) {
                        return x.add(x);
                    }
                }
                """);
        var validator = """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import lib.Helper;

                @SpendingValidator
                public class V {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Helper.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """;
        var result = compileWithProcessorSources(Map.of("app/V", validator), List.of("-sourcepath", sourcePath.toString()));
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    /** A validator's script does not depend on which other validators the compilation holds (ADR-071 I5). */
    @Test
    void aValidatorCompilesAloneAsItDoesWithOthers() throws Exception {
        var wildcard = """
                package v;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import app.Counter;

                @SpendingValidator
                public class Wild {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Counter.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """;
        var explicit = """
                package v;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import app.Counter;

                @SpendingValidator
                public class Explicit {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Counter.twice(redeemer).compareTo(BigInteger.ONE) == 0;
                    }
                }
                """;
        var alone = compileWithFramework(Map.of("app/Counter", MARKED_COUNTER), Map.of("v.Wild", wildcard), true);
        assertTrue(alone.success(), "Expected success, got: " + alone.diagnostics());
        String aloneScript = script("Wild");

        var together = compileWithFramework(Map.of("app/Counter", MARKED_COUNTER),
                Map.of("v.Explicit", explicit, "v.Wild", wildcard), true);
        assertTrue(together.success(), "Expected success, got: " + together.diagnostics());
        assertEquals(aloneScript, script("Wild"));
    }

    @Test
    void aLowercaseMarkerIsFollowed() throws Exception {
        String marker = STATE_LIKE.replace("StateLike", "stateLike");
        String library = META_LIBRARY.replace("@StateLike", "@stateLike");
        var result = compileWithProcessorSources(Map.of("lib/stateLike", marker, "lib/MetaMath", library, "app/UsesMeta", USES_META),
                List.of());
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    /** The marker contract: a class-valued default names its class through an import, which the resolver follows. */
    @Test
    void aMarkerClassDefaultThatImportsALibraryCompiles() throws Exception {
        var sources = new LinkedHashMap<String, String>();
        sources.put("lib/Scaled", """
                package lib;

                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.lang.annotation.*;
                import lib.Limits;

                @OnchainLibrary
                @Retention(RetentionPolicy.CLASS)
                @Target(ElementType.TYPE)
                public @interface Scaled {
                    Class<?> type() default Limits.class;
                }
                """);
        sources.put("lib/Limits", """
                package lib;

                import org.julclang.stdlib.annotation.OnchainLibrary;

                @OnchainLibrary
                public class Limits {
                    public static final int N = 2;
                }
                """);
        sources.put("lib/MetaMath", """
                package lib;

                import java.math.BigInteger;

                @Scaled
                public class MetaMath {
                    public static BigInteger twice(BigInteger x) {
                        return x.add(x);
                    }
                }
                """);
        sources.put("app/UsesMeta", """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import lib.MetaMath;

                @SpendingValidator
                public class UsesMeta {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return MetaMath.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """);
        var result = compileWithProcessorSources(sources, List.of());
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    /**
     * A field named through its class ({@code BigInteger.ZERO}) is never asked of javac as a class, so an unrelated
     * source on the source path is never read, and a -Werror build passes as before.
     */
    @Test
    void aFieldQualifierNeverReadsTheSourcePath() throws Exception {
        Path sourcePath = tempDir.resolve("sourcepath");
        Files.createDirectories(sourcePath.resolve("app"));
        Files.writeString(sourcePath.resolve("app/ZERO.java"), "package app;\npublic class ZERO {}\n");
        var validator = """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                @SpendingValidator
                public class V {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return BigInteger.ZERO.signum() == 0 && redeemer.signum() > 0;
                    }
                }
                """;
        var result = compileWithProcessorSources(Map.of("app/V", validator),
                List.of("-sourcepath", sourcePath.toString(), "-Werror"));
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    @Test
    void aNestedLibraryImportedByItsOwnNameIsRejected() throws Exception {
        var holder = """
                package app;

                import org.julclang.processor.testmarker.StateMarker;

                public class Holder {
                    @StateMarker
                    public static class Inner {
                        public static boolean ok() {
                            return true;
                        }
                    }
                }
                """;
        var result = compileWithFramework(Map.of("app/Holder", holder, "app/Counter", MARKED_COUNTER),
                Map.of("v.UsesInner", """
                        package v;

                        import org.julclang.stdlib.annotation.*;
                        import java.math.BigInteger;
                        import org.julclang.core.PlutusData;
                        import app.Holder.Inner;

                        @SpendingValidator
                        public class UsesInner {
                            @Entrypoint
                            static boolean validate(BigInteger redeemer, PlutusData ctx) {
                                return Inner.ok();
                            }
                        }
                        """), true);
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("@StateMarker makes Inner")),
                "Got: " + result.diagnostics());
    }

    @Test
    void aNestedLibraryInAnotherClassOfTheSameFileIsRejected() throws Exception {
        var outer = """
                package app;

                import java.math.BigInteger;
                import org.julclang.processor.testmarker.StateMarker;

                @StateMarker
                public class Outer {
                    public static BigInteger twice(BigInteger x) {
                        return x.add(x);
                    }
                }

                class Holder {
                    @StateMarker
                    static class Inner {
                        static boolean ok() {
                            return true;
                        }
                    }
                }
                """;
        var result = compileWithFramework(Map.of("app/Outer", outer), Map.of("app.UsesOuter", """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import app.Outer;

                @SpendingValidator
                public class UsesOuter {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        return Outer.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }
                }
                """), true);
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("@StateMarker makes Inner")),
                "Got: " + result.diagnostics());
    }

    /**
     * A variable named like the class (a parameter of another method, a local of an inner block) does not hide the
     * class where the entrypoint names it.
     */
    @Test
    void aVariableInAnotherMethodDoesNotHideAMarkedClass() throws Exception {
        var result = compileWithFramework(Map.of("app/Counter", MARKED_COUNTER), Map.of("app.UsesCounter", """
                package app;

                import org.julclang.stdlib.annotation.*;
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import app.Counter;

                @SpendingValidator
                public class UsesCounter {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, PlutusData ctx) {
                        if (redeemer.signum() < 0) {
                            BigInteger Counter = redeemer;
                            return Counter.signum() > 0;
                        }
                        return Counter.twice(redeemer).compareTo(BigInteger.TEN) == 0;
                    }

                    static boolean positive(BigInteger Counter) {
                        return Counter.signum() > 0;
                    }
                }
                """), true);
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    /**
     * A direct-only project whose pool holds an unused class-path library (the test resources' {@code Shadowed}): an
     * unrelated source of that name on the source path is never read, so -Werror passes as before.
     */
    @Test
    void anUnusedClassPathLibraryNeverReadsTheSourcePath() throws Exception {
        Path sourcePath = tempDir.resolve("sourcepath");
        Files.createDirectories(sourcePath.resolve("org/julclang/processor/testshadow"));
        Files.writeString(sourcePath.resolve("org/julclang/processor/testshadow/Shadowed.java"),
                "package org.julclang.processor.testshadow;\npublic class Shadowed {}\n");
        var direct = META_LIBRARY.replace("@StateLike", "@org.julclang.stdlib.annotation.OnchainLibrary");
        var result = compileWithProcessorSources(Map.of("lib/MetaMath", direct, "app/UsesMeta", USES_META),
                List.of("-sourcepath", sourcePath.toString(), "-Werror"));
        assertTrue(result.success(), "Expected success, got: " + result.diagnostics());
    }

    @Test
    void aMarkedClassNestedInADirectLibraryIsRejected() throws Exception {
        var outer = """
                package lib;

                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;

                @OnchainLibrary
                public class MetaMath {
                    public static BigInteger twice(BigInteger x) {
                        return x.add(x);
                    }

                    @StateLike
                    public static class Inner {
                        public static boolean ok() {
                            return true;
                        }
                    }
                }
                """;
        var result = compileWithProcessorSources(Map.of("lib/StateLike", STATE_LIKE, "lib/MetaMath", outer, "app/UsesMeta", USES_META),
                List.of());
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("@StateLike makes Inner")),
                "Got: " + result.diagnostics());
    }

    @Test
    void everyMarkerWithoutASourceIsReported() throws Exception {
        Path markerClasses = tempDir.resolve("nosrc-classes");
        Files.createDirectories(markerClasses);
        compileClasses(Map.of("fw/NoSrc", """
                package fw;

                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.lang.annotation.*;

                @OnchainLibrary
                @Retention(RetentionPolicy.CLASS)
                @Target(ElementType.TYPE)
                public @interface NoSrc {}
                """), markerClasses);
        var library = """
                package lib;

                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;
                import fw.NoSrc;

                @OnchainLibrary
                @NoSrc
                public class MetaMath {
                    public static BigInteger twice(BigInteger x) {
                        return x.add(x);
                    }
                }
                """;
        var result = compileWithProcessors(Map.of("lib/MetaMath", library, "app/UsesMeta", USES_META),
                List.of("-classpath", markerClasses + File.pathSeparator + System.getProperty("java.class.path")),
                List.of(new JulcAnnotationProcessor()));
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains(
                "@NoSrc makes MetaMath an on-chain library, but JuLC has no source for fw.NoSrc")),
                "Got: " + result.diagnostics());
    }

    private String script(String validator) throws IOException {
        return ValidatorOutput.fromJson(Files.readString(
                tempDir.resolve("META-INF/plutus/" + validator + ".plutus.json"))).cborHex();
    }

    /**
     * Compiles {@code sources} with the test marker on the class path, a framework processor that generates
     * {@code generated} (name -> source) when it first sees {@code @StateMarker}, and JuLC's processor; the framework
     * runs first when {@code frameworkFirst}.
     */
    private CompileOutput compileWithFramework(Map<String, String> sources, Map<String, String> generated,
                                               boolean frameworkFirst) throws Exception {
        String marker = Files.readString(Path.of(getClass().getResource(
                "/META-INF/plutus-sources/org/julclang/processor/testmarker/StateMarker.java").toURI()));
        Path markerClasses = tempDir.resolve("marker-classes");
        Files.createDirectories(markerClasses);
        compileClasses(Map.of("org/julclang/processor/testmarker/StateMarker", marker), markerClasses);

        var framework = new AbstractProcessor() {
            boolean done;

            @Override
            public Set<String> getSupportedAnnotationTypes() {
                return Set.of(STATE_MARKER);
            }

            @Override
            public SourceVersion getSupportedSourceVersion() {
                return SourceVersion.latestSupported();
            }

            @Override
            public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
                if (done || annotations.isEmpty()) return false;
                done = true;
                generated.forEach((name, source) -> {
                    try (var writer = processingEnv.getFiler().createSourceFile(name).openWriter()) {
                        writer.write(source);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                return true;
            }
        };
        var julc = new JulcAnnotationProcessor();
        return compileWithProcessors(sources,
                List.of("-classpath", markerClasses + File.pathSeparator + System.getProperty("java.class.path")),
                frameworkFirst ? List.of(framework, julc) : List.of(julc, framework));
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
            Path generated = tempDir.resolve("generated-sources");
            Files.createDirectories(generated);
            fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
            var options = new ArrayList<>(List.of("--enable-preview", "--source", "25", "-proc:only"));
            options.addAll(extraOptions);
            var task = javac.getTask(null, fileManager, diagnostics, options, null, inMemory(sources));
            task.setProcessors(processors);
            return new CompileOutput(task.call(), diagnostics.getDiagnostics());
        }
    }

    /** Compiles {@code sources} to class files in {@code out}, without annotation processing. */
    private void compileClasses(Map<String, String> sources, Path out) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = javac.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            boolean ok = javac.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null, inMemory(sources)).call();
            assertTrue(ok, "Could not compile " + sources.keySet() + ": " + diagnostics.getDiagnostics());
        }
    }

    private static List<SimpleJavaFileObject> inMemory(Map<String, String> sources) {
        return sources.entrySet().stream().map(entry -> (SimpleJavaFileObject) new SimpleJavaFileObject(
                URI.create("string:///" + entry.getKey() + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return entry.getValue();
            }
        }).toList();
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
