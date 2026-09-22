package org.julclang.playground.service;

import org.julclang.tools.service.ToolsService;
import org.julclang.playground.scenario.ScenarioRegistry;

import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.tools.api.InputValidator;
import org.julclang.tools.model.CheckRequest;
import org.julclang.tools.model.CompileRequest;
import org.julclang.tools.model.EvalExpressionRequest;
import org.julclang.tools.model.EvaluateRequest;
import org.julclang.playground.model.ExampleDto;
import org.julclang.tools.model.ScenarioOverrides;
import org.julclang.tools.repl.ExpressionEvaluator;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.JulcVm;
import org.julclang.vm.java.JavaVmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolsServiceTest {

    static final JulcCompiler COMPILER = new JulcCompiler(StdlibRegistry.defaultRegistry());
    static final ToolsService SERVICE = new ToolsService(COMPILER,
            LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader()),
            () -> JulcVm.withProvider(new JavaVmProvider()));

    static final String SIGNER = "aa".repeat(28);

    static final String SIMPLE_SPENDING = """
            @SpendingValidator
            class SimpleSpending {
                @Param static byte[] authorizedSigner;

                @Entrypoint
                static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
                    PlutusData txInfo = ContextsLib.getTxInfo(ctx);
                    return ContextsLib.signedBy(txInfo, authorizedSigner);
                }
            }
            """;

    static final String ALWAYS_TRUE = """
            @SpendingValidator
            class AlwaysTrue {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;

    @Test
    void check_extractsMetadata() {
        var result = ToolsService.check(new CheckRequest(SIMPLE_SPENDING));

        assertEquals(200, result.status());
        assertTrue(result.body().valid(), () -> String.valueOf(result.body().diagnostics()));
        assertEquals("SimpleSpending", result.body().contractName());
        assertEquals("SPENDING", result.body().purpose());
        assertEquals("authorizedSigner", result.body().params().getFirst().name());
    }

    @Test
    void check_rejectsBlankSourceWithDiagnostic() {
        var result = ToolsService.check(new CheckRequest(" "));

        assertEquals(200, result.status());
        assertFalse(result.body().valid());
        assertEquals("JULC000", result.body().diagnostics().getFirst().code());
    }

    @Test
    void compile_withBlueprint_returnsScriptArtifacts() {
        var result = SERVICE.compile(new CompileRequest(SIMPLE_SPENDING));

        assertEquals(200, result.status());
        var body = result.body();
        assertTrue(body.uplcText().startsWith("(program"));
        assertNotNull(body.pirText());
        assertNotNull(body.blueprintJson());
        assertEquals(56, body.scriptHash().length());
        assertTrue(body.compiledCode().length() > 2 * body.scriptSizeBytes());
        assertEquals(List.of("authorizedSigner"), body.params().stream().map(p -> p.name()).toList());
        assertTrue(body.diagnostics().isEmpty(), () -> String.valueOf(body.diagnostics()));
    }

    @Test
    void compile_withoutBlueprint_omitsBlueprintOnly() {
        var result = SERVICE.compile(new CompileRequest(ALWAYS_TRUE, null, null, false));

        assertEquals(200, result.status());
        assertNull(result.body().blueprintJson());
        assertNotNull(result.body().compiledCode());
        assertEquals(56, result.body().scriptHash().length());
    }

    @Test
    void compile_errorReturnsDiagnosticsWithStatus200() {
        var result = SERVICE.compile(new CompileRequest("""
                @SpendingValidator
                class Broken {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return undefinedThing;
                    }
                }
                """));

        // A compiler error is a normal 200 response without script artifacts, never an internal failure.
        assertEquals(200, result.status());
        assertNull(result.body().uplcText());
        assertNull(result.body().compiledCode());
        assertNull(result.failure());
    }

    @Test
    void compile_oversizedLibraryIsRejectedBeforeCompiling() {
        var library = "x".repeat(InputValidator.MAX_LIBRARY_LENGTH + 1);
        var result = SERVICE.compile(new CompileRequest(ALWAYS_TRUE, null, library, false));

        assertEquals(200, result.status());
        assertTrue(result.body().diagnostics().getFirst().message().contains("maximum length"));
        assertNotNull(ToolsService.validateCompile(new CompileRequest(ALWAYS_TRUE, null, library, false)));
        assertNull(ToolsService.validateCompile(new CompileRequest(ALWAYS_TRUE)));
    }

    @Test
    void evaluate_appliesParamsAndSigners() {
        var signed = SERVICE.evaluate(spendingRequest(Map.of("authorizedSigner", SIGNER), List.of(SIGNER)));
        var unsigned = SERVICE.evaluate(spendingRequest(Map.of("authorizedSigner", SIGNER), List.of("bb".repeat(28))));

        assertEquals(200, signed.status());
        assertTrue(signed.body().success(), signed.body().error());
        assertTrue(signed.body().budgetCpu() > 0);
        assertEquals(200, unsigned.status());
        assertFalse(unsigned.body().success());
        assertNotNull(unsigned.body().error());
    }

    @Test
    void evaluate_missingParamIsBadRequest() {
        var result = SERVICE.evaluate(spendingRequest(Map.of("other", SIGNER), List.of(SIGNER)));

        assertEquals(400, result.status());
        assertEquals("Missing param value: authorizedSigner", result.body().error());
    }

    @Test
    void evaluate_invalidSourceReportsCheckFailure() {
        var result = SERVICE.evaluate(new EvaluateRequest("class NotAValidator {}", Map.of(),
                new ScenarioOverrides(List.of(), null, null), Map.of(), null));

        assertEquals(200, result.status());
        assertFalse(result.body().success());
        assertEquals("Check failed", result.body().error());
    }

    @Test
    void evalExpression_usesGivenEvaluator() {
        var evaluator = new ExpressionEvaluator(COMPILER, JulcVm.withProvider(new JavaVmProvider()),
                LibrarySourceResolver.scanClasspathSources(JulcCompiler.class.getClassLoader()));

        var ok = ToolsService.evalExpression(evaluator, new EvalExpressionRequest("MathLib.abs(-5)"));
        var blank = ToolsService.evalExpression(evaluator, new EvalExpressionRequest(""));

        assertEquals(200, ok.status());
        assertTrue(ok.body().success(), ok.body().error());
        assertEquals("5", ok.body().result());
        assertEquals(200, blank.status());
        assertFalse(blank.body().success());
    }

    @Test
    void scenarios_knownAndUnknownPurposes() {
        var spending = ScenarioRegistry.scenarios("spending");
        var unknown = ScenarioRegistry.scenarios("unknown");

        assertEquals("SPENDING", spending.body().purpose());
        assertFalse(spending.body().scenarios().isEmpty());
        assertNull(spending.body().message());
        assertEquals("UNKNOWN", unknown.body().purpose());
        assertTrue(unknown.body().scenarios().isEmpty());
        assertTrue(unknown.body().message().contains("unknown"));
    }

    @Test
    void examples_listFilterAndLookup() {
        var catalog = new ExampleCatalog();

        assertEquals(3, catalog.list(null).body().size());
        assertEquals(3, catalog.list("JAVA").body().size());
        assertTrue(catalog.list("jrl").body().isEmpty());
        var found = catalog.get("SimpleSpending.java");
        assertEquals(200, found.status());
        assertTrue(((ExampleDto) found.body()).source().contains("@SpendingValidator"));
        var missing = catalog.get("Nope.java");
        assertEquals(404, missing.status());
        assertEquals(Map.of("error", "Example not found: Nope.java"), missing.body());
    }

    private static EvaluateRequest spendingRequest(Map<String, String> params, List<String> signers) {
        return new EvaluateRequest(SIMPLE_SPENDING, params,
                new ScenarioOverrides(signers, null, null), Map.of(), null);
    }
}
