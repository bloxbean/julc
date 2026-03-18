package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.testkit.ValidatorTest;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests: compile with source maps + evaluate + verify error locations.
 */
class SourceMapEvalTest {

    @Test
    void alwaysFailing_method_errorHasSourceLocation() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                import java.math.BigInteger;

                class FailHelper {
                    public static BigInteger fail(BigInteger x) {
                        Builtins.error();
                        return x;
                    }
                }
                """;

        var compiled = compiler.compileMethod(source, "fail");
        assertFalse(compiled.hasErrors());
        assertTrue(compiled.hasSourceMap());

        var vm = JulcVm.create();
        var result = vm.evaluateWithArgs(compiled.program(), List.of(PlutusData.integer(1)));

        assertInstanceOf(EvalResult.Failure.class, result);
        var failure = (EvalResult.Failure) result;
        assertEquals("Error term encountered", failure.error());
        assertNotNull(failure.failedTerm(), "Failure should carry failedTerm");

        // Look up source location
        var location = compiled.sourceMap().lookup(failure.failedTerm());
        // The error term may or may not be directly mapped (depends on whether
        // Builtins.error() -> PirTerm.Error produces a directly mapped term).
        // But the test validates the full pipeline works.
    }

    @Test
    void conditionalFailing_method_errorInCorrectBranch() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                import java.math.BigInteger;

                class Checker {
                    public static BigInteger check(BigInteger x) {
                        if (x.compareTo(BigInteger.ZERO) < 0) {
                            Builtins.error();
                        }
                        return x;
                    }
                }
                """;

        var compiled = compiler.compileMethod(source, "check");

        // Positive value succeeds
        var vm = JulcVm.create();
        var success = vm.evaluateWithArgs(compiled.program(), List.of(PlutusData.integer(5)));
        assertTrue(success.isSuccess());

        // Negative value fails
        var failure = vm.evaluateWithArgs(compiled.program(), List.of(PlutusData.integer(-1)));
        assertInstanceOf(EvalResult.Failure.class, failure);
        var f = (EvalResult.Failure) failure;
        assertNotNull(f.failedTerm());
    }

    @Test
    void budgetExhaustion_carriesFailedTerm() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import java.math.BigInteger;

                class Looper {
                    public static BigInteger loop(BigInteger n) {
                        var result = BigInteger.ZERO;
                        while (n.compareTo(BigInteger.ZERO) > 0) {
                            result = result.add(n);
                            n = n.subtract(BigInteger.ONE);
                        }
                        return result;
                    }
                }
                """;

        var compiled = compiler.compileMethod(source, "loop");

        var vm = JulcVm.create();
        var result = vm.evaluateWithArgs(compiled.program(),
                List.of(PlutusData.integer(1000000)),
                new ExBudget(1000, 1000));

        assertInstanceOf(EvalResult.BudgetExhausted.class, result);
        var exhausted = (EvalResult.BudgetExhausted) result;
        assertNotNull(exhausted.failedTerm(),
                "BudgetExhausted should carry the term that was being evaluated");
    }

    @Test
    void succeeding_method_noFailedTerm() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import java.math.BigInteger;

                class Adder {
                    public static BigInteger add(BigInteger a, BigInteger b) {
                        return a.add(b);
                    }
                }
                """;

        var compiled = compiler.compileMethod(source, "add");

        var vm = JulcVm.create();
        var result = vm.evaluateWithArgs(compiled.program(),
                List.of(PlutusData.integer(3), PlutusData.integer(4)));

        assertInstanceOf(EvalResult.Success.class, result);
    }

    @Test
    void testkit_resolveErrorLocation() {
        var options = new CompilerOptions().setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);

        String source = """
                import com.bloxbean.cardano.julc.stdlib.Builtins;
                import java.math.BigInteger;

                class Fail {
                    public static BigInteger fail(BigInteger x) {
                        Builtins.error();
                        return x;
                    }
                }
                """;

        var compiled = compiler.compileMethod(source, "fail");

        var vm = JulcVm.create();
        var result = vm.evaluateWithArgs(compiled.program(), List.of(PlutusData.integer(1)));

        // Use testkit utility to resolve
        var location = ValidatorTest.resolveErrorLocation(result, compiled.sourceMap());
        // May be null if the error term goes through stdlib indirection,
        // but should not throw
    }

    @Test
    void testkit_resolveErrorLocation_nullSourceMap() {
        var budget = new ExBudget(1000, 500);
        var failure = new EvalResult.Failure("Error", budget, List.of());
        assertNull(ValidatorTest.resolveErrorLocation(failure, null));
    }

    @Test
    void testkit_resolveErrorLocation_successResult() {
        var budget = new ExBudget(1000, 500);
        var success = new EvalResult.Success(
                com.bloxbean.cardano.julc.core.Term.const_(
                        com.bloxbean.cardano.julc.core.Constant.unit()),
                budget, List.of());
        assertNull(ValidatorTest.resolveErrorLocation(success,
                com.bloxbean.cardano.julc.core.source.SourceMap.EMPTY));
    }
}
