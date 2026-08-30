package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.TermExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java-source-level regression tests for the optimizer soundness fix
 * (adr/issues/julc-dce-soundness-issue.md).
 * <p>
 * Java is strict: a local variable initializer is evaluated even when the
 * variable is never read, so "assert by evaluating" guards must survive
 * compilation and optimization. Before the fix, dead code elimination deleted
 * any unused binding whose initializer it considered effect-free — including
 * ones that error at runtime — silently flipping should-fail validators to
 * should-pass.
 */
class OptimizerSourceSoundnessTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = CompilerTestVm.pv11("Java");
    }

    private CompileResult compileMethod(String source, String methodName) {
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, methodName);
        assertFalse(result.hasErrors(), "compile failed: " + result.diagnostics());
        return result;
    }

    @Test
    void unusedLocalDivisionGuardMustAbort() {
        var source = """
                import java.math.BigInteger;

                public class Guarded {
                    public static boolean check(BigInteger a) {
                        BigInteger assertNonZero = BigInteger.ONE.divide(a);
                        return true;
                    }
                }
                """;
        var result = compileMethod(source, "check");

        // a = 0: the unused initializer divides by zero — the script must abort
        var zero = vm.evaluateWithArgs(result.program(), List.of(PlutusData.integer(0)));
        assertFalse(zero.isSuccess(),
                "unused local `1/a` was optimized away — validator passed where it must abort");

        // a = 2: guard passes, result is true
        var two = vm.evaluateWithArgs(result.program(), List.of(PlutusData.integer(2)));
        assertTrue(two.isSuccess(), "expected success for a=2: " + two);
        assertTrue(TermExtractor.extractBoolean(TermExtractor.extractResultTerm(two)));
    }

    @Test
    void unusedLocalShapeAssertMustAbort() {
        var source = """
                import java.math.BigInteger;
                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.stdlib.Builtins;

                public class ShapeGuarded {
                    public static boolean check(PlutusData d) {
                        BigInteger mustBeInteger = Builtins.unIData(d);
                        return true;
                    }
                }
                """;
        var result = compileMethod(source, "check");

        // bytes payload: unIData must abort even though its result is unused
        var bytes = vm.evaluateWithArgs(result.program(),
                List.of(PlutusData.bytes(new byte[]{1, 2})));
        assertFalse(bytes.isSuccess(),
                "unused unIData shape assert was optimized away — validator passed on malformed data");

        // integer payload: shape assert passes
        var intData = vm.evaluateWithArgs(result.program(), List.of(PlutusData.integer(5)));
        assertTrue(intData.isSuccess(), "expected success for integer payload: " + intData);
    }

    @Test
    void switchCaseFieldExtractionIsFailClosedOnMalformedData() {
        // `case Active a` binds Active's fields; the extraction must run even
        // though the branch body never reads them, so constr payloads with
        // missing fields are rejected (fail-closed), matching Java record
        // pattern semantics where component accessors are always invoked.
        var source = """
            import java.math.BigInteger;

            sealed interface Status permits Active, Retired {}
            record Active(BigInteger since) implements Status {}
            record Retired(BigInteger at) implements Status {}

            @SpendingValidator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Status s = (Status) redeemer;
                    return switch (s) {
                        case Active a -> true;
                        case Retired r -> false;
                    };
                }
            }
            """;
        var result = new JulcCompiler().compile(source);
        assertFalse(result.hasErrors(), "compile failed: " + result.diagnostics());

        // Well-formed Active(7) → true
        var ok = vm.evaluateWithArgs(result.program(),
                List.of(scriptContext(PlutusData.constr(0, PlutusData.integer(7)))));
        assertTrue(ok.isSuccess(), "well-formed Active should pass: " + ok);

        // Malformed Active with no fields → field extraction must abort
        var malformed = vm.evaluateWithArgs(result.program(),
                List.of(scriptContext(PlutusData.constr(0))));
        assertFalse(malformed.isSuccess(),
                "unused field extraction was optimized away — malformed constr accepted");
    }

    private static PlutusData scriptContext(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),
                redeemer,
                PlutusData.integer(0));
    }
}
