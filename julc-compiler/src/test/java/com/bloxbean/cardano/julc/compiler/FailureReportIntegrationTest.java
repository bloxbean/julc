package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.trace.FailureReportBuilder;
import com.bloxbean.cardano.julc.vm.trace.FailureReportFormatter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests: compile real {@code @Validator} classes with source maps,
 * evaluate with failing arguments, and verify the BuiltinTrace + FailureReport
 * contain the expected comparison values and source location.
 */
class FailureReportIntegrationTest {

    // Minting validator that checks: 99 == 42 → always fails
    static final String EQUALITY_FAIL_MINT = """
            import java.math.BigInteger;

            @Validator
            class EqualityFailMint {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger expected = BigInteger.valueOf(42);
                    BigInteger actual = BigInteger.valueOf(99);
                    return actual == expected;
                }
            }
            """;

    // Minting validator that checks: 5 >= 10 → always fails
    static final String COMPARISON_FAIL_MINT = """
            import java.math.BigInteger;

            @Validator
            class ComparisonFailMint {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger amount = BigInteger.valueOf(5);
                    BigInteger threshold = BigInteger.valueOf(10);
                    return amount >= threshold;
                }
            }
            """;

    // Minting validator that always succeeds
    static final String ALWAYS_PASS_MINT = """
            @Validator
            class AlwaysPassMint {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;

    @Test
    void comparisonFailure_capturesLessThanEqualsWithValues() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
        var compiled = compiler.compile(COMPARISON_FAIL_MINT);
        assertFalse(compiled.hasErrors(), "Compilation should succeed: " + compiled.diagnostics());
        assertTrue(compiled.hasSourceMap());

        var vm = JulcVm.create();
        vm.setSourceMap(compiled.sourceMap());

        var result = vm.evaluateWithArgs(compiled.program(), List.of(buildMintingCtx()));

        assertInstanceOf(EvalResult.Failure.class, result, "Validator should fail: " + result);

        // Verify builtin trace contains comparison with actual values
        var builtinTrace = vm.getLastBuiltinTrace();
        assertFalse(builtinTrace.isEmpty(), "BuiltinTrace should capture builtins");

        var compEntry = builtinTrace.stream()
                .filter(e -> e.fun() == DefaultFun.LessThanEqualsInteger
                        && "False".equals(e.resultSummary()))
                .findFirst().orElse(null);
        assertNotNull(compEntry,
                "Should capture LessThanEqualsInteger → False. Got: " + builtinTrace);
        assertTrue(compEntry.argSummary().contains("10"),
                "Should contain threshold value 10. Got: " + compEntry.argSummary());
        assertTrue(compEntry.argSummary().contains("5"),
                "Should contain amount value 5. Got: " + compEntry.argSummary());

        // Build and format FailureReport
        var report = FailureReportBuilder.build(result, compiled.sourceMap(),
                List.of(), builtinTrace);
        assertNotNull(report);

        var formatted = FailureReportFormatter.format(report);
        assertTrue(formatted.contains("FAIL"), "Report should start with FAIL");
        assertTrue(formatted.contains("False"), "Report should contain → False");
        assertTrue(formatted.contains("Last builtins:"), "Report should show builtins section");
        assertTrue(formatted.contains("CPU="), "Report should show budget");

        vm.setSourceMap(null);
    }

    @Test
    void equalityFailure_capturesEqualsIntegerWithValues() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
        var compiled = compiler.compile(EQUALITY_FAIL_MINT);
        assertFalse(compiled.hasErrors(), "Compilation should succeed: " + compiled.diagnostics());

        var vm = JulcVm.create();
        vm.setSourceMap(compiled.sourceMap());

        var result = vm.evaluateWithArgs(compiled.program(), List.of(buildMintingCtx()));

        assertInstanceOf(EvalResult.Failure.class, result, "Validator should fail: " + result);

        var builtinTrace = vm.getLastBuiltinTrace();
        var equalsEntry = builtinTrace.stream()
                .filter(e -> e.fun() == DefaultFun.EqualsInteger
                        && "False".equals(e.resultSummary()))
                .findFirst().orElse(null);
        assertNotNull(equalsEntry,
                "Should capture EqualsInteger → False. Got: " + builtinTrace);
        assertTrue(equalsEntry.argSummary().contains("99"),
                "Should contain value 99. Got: " + equalsEntry.argSummary());
        assertTrue(equalsEntry.argSummary().contains("42"),
                "Should contain value 42. Got: " + equalsEntry.argSummary());

        vm.setSourceMap(null);
    }

    @Test
    void validatorSuccess_builtinTraceCapturedButNoFailureReport() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
        var compiled = compiler.compile(ALWAYS_PASS_MINT);

        var vm = JulcVm.create();
        vm.setSourceMap(compiled.sourceMap());

        var result = vm.evaluateWithArgs(compiled.program(), List.of(buildMintingCtx()));
        assertTrue(result.isSuccess(), "Validator should succeed: " + result);

        // BuiltinTrace is still active on success (ring buffer captured builtins from ScriptContext deconstruction)
        var builtinTrace = vm.getLastBuiltinTrace();
        // Even a trivial validator that returns true will have some builtins from ScriptContext processing
        // (at minimum the IfThenElse guard). But an always-true may have zero if it's optimized away.
        // Just verify the API works without crashing.
        assertNotNull(builtinTrace);

        // FailureReportBuilder returns null for success
        assertNull(FailureReportBuilder.build(result, compiled.sourceMap()));

        vm.setSourceMap(null);
    }

    @Test
    void evaluateWithDiagnostics_failure_producesReport() {
        var compiled = ValidatorTest.compileWithSourceMap(EQUALITY_FAIL_MINT);

        var report = ValidatorTest.evaluateWithDiagnostics(compiled, buildMintingCtx());
        assertNotNull(report, "Failing validator should produce a FailureReport");
        assertEquals("Error term encountered", report.errorMessage());
        assertFalse(report.lastBuiltins().isEmpty(),
                "FailureReport should include builtin trace");

        var formatted = FailureReportFormatter.format(report);
        assertTrue(formatted.contains("FAIL"));
        assertTrue(formatted.contains("False"));
    }

    @Test
    void evaluateWithDiagnostics_success_returnsNull() {
        var compiled = ValidatorTest.compileWithSourceMap(ALWAYS_PASS_MINT);

        var report = ValidatorTest.evaluateWithDiagnostics(compiled, buildMintingCtx());
        assertNull(report, "Succeeding validator should return null");
    }

    @Test
    void assertValidatesWithDiagnostics_failureMessage_containsBuiltinValues() {
        var compiled = ValidatorTest.compileWithSourceMap(EQUALITY_FAIL_MINT);

        var error = assertThrows(AssertionError.class,
                () -> ValidatorTest.assertValidatesWithDiagnostics(compiled, buildMintingCtx()));

        var message = error.getMessage();
        assertTrue(message.contains("FAIL"), "Error message should contain FAIL");
        assertTrue(message.contains("False"), "Error message should contain → False");
        assertTrue(message.contains("Last builtins:"),
                "Error message should contain Last builtins section");
    }

    // --- ScriptContext helpers ---

    private static PlutusData buildMintingCtx() {
        var txInfo = buildMinimalTxInfo();
        var redeemer = PlutusData.integer(0);
        // MintingScript = Constr(0, [policyId])
        var scriptInfo = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    private static PlutusData buildMinimalTxInfo() {
        return PlutusData.constr(0,
                PlutusData.list(),                          // 0: inputs
                PlutusData.list(),                          // 1: referenceInputs
                PlutusData.list(),                          // 2: outputs
                PlutusData.integer(2000000),                // 3: fee
                PlutusData.map(),                           // 4: mint
                PlutusData.list(),                          // 5: certificates
                PlutusData.map(),                           // 6: withdrawals
                alwaysInterval(),                           // 7: validRange
                PlutusData.list(),                          // 8: signatories
                PlutusData.map(),                           // 9: redeemers
                PlutusData.map(),                           // 10: datums
                PlutusData.bytes(new byte[32]),             // 11: txId
                PlutusData.map(),                           // 12: votes
                PlutusData.list(),                          // 13: proposalProcedures
                PlutusData.constr(1),                       // 14: currentTreasuryAmount (None)
                PlutusData.constr(1)                        // 15: treasuryDonation (None)
        );
    }

    private static PlutusData alwaysInterval() {
        var negInf = PlutusData.constr(0);
        var posInf = PlutusData.constr(2);
        var trueVal = PlutusData.constr(1);
        var lowerBound = PlutusData.constr(0, negInf, trueVal);
        var upperBound = PlutusData.constr(0, posInf, trueVal);
        return PlutusData.constr(0, lowerBound, upperBound);
    }
}
