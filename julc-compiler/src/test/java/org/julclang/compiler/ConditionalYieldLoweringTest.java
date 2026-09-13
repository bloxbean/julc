package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for issue #137: a {@code yield} inside an {@code if} in a
 * switch-expression case block followed by more statements was silently discarded.
 * The block lowered as {@code Let("_if", ifExpr, rest)}, so the trailing statements
 * (typically the fall-through {@code yield}) ran unconditionally and a guard written
 * as {@code yield false} could be skipped.
 * <p>
 * This is the {@code yield} sibling of #79 ({@link EarlyReturnLoweringTest}). Every
 * expectation is plain Java semantics: {@code yield} exits its owning switch
 * expression, an inner switch expression owns its own yields, and statements after a
 * conditional only run for branches that fall through.
 */
class ConditionalYieldLoweringTest {

    static final String SOURCE = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;

            class ConditionalYields {
                sealed interface Action permits Check, Stop {}
                record Check(BigInteger n) implements Action {}
                record Stop() implements Action {}

                sealed interface Inner permits Small, Large {}
                record Small(BigInteger v) implements Inner {}
                record Large(BigInteger v) implements Inner {}

                // #137 reproducer: conditional yield followed by a trailing yield.
                static boolean conditionalYield(Action action) {
                    return switch (action) {
                        case Check c -> {
                            if (c.n().signum() > 0) { yield false; }
                            yield true;
                        }
                        case Stop s -> false;
                    };
                }

                // The else branch yields; the then branch falls through to the trailing yield.
                static BigInteger elseYields(Action action) {
                    return switch (action) {
                        case Check c -> {
                            if (c.n().signum() > 0) {
                                BigInteger positive = c.n();
                            } else {
                                yield BigInteger.valueOf(-1);
                            }
                            yield c.n().add(BigInteger.TEN);
                        }
                        case Stop s -> BigInteger.ZERO;
                    };
                }

                // Three nesting levels, each with its own fall-through continuation.
                static BigInteger nestedYields(Action action, BigInteger b, BigInteger d) {
                    return switch (action) {
                        case Check c -> {
                            if (c.n().signum() > 0) {
                                if (b.signum() > 0) {
                                    if (d.signum() < 0) {
                                        yield BigInteger.valueOf(-1);
                                    }
                                    BigInteger afterD = d.add(BigInteger.ONE);
                                    yield afterD;
                                }
                                BigInteger afterB = b.add(BigInteger.TEN);
                                yield afterB;
                            }
                            yield BigInteger.valueOf(100);
                        }
                        case Stop s -> BigInteger.ZERO;
                    };
                }

                // The inner switch owns its conditional yield; the outer arm's conditional
                // yield uses the inner result and must still short-circuit.
                static BigInteger innerSwitchOwnership(Action action, Inner inner) {
                    return switch (action) {
                        case Check c -> {
                            BigInteger scaled = switch (inner) {
                                case Small s -> {
                                    if (s.v().signum() < 0) { yield BigInteger.ZERO; }
                                    yield s.v();
                                }
                                case Large l -> l.v().multiply(BigInteger.TEN);
                            };
                            if (scaled.compareTo(c.n()) > 0) { yield BigInteger.valueOf(-1); }
                            yield scaled.add(c.n());
                        }
                        case Stop s -> BigInteger.ZERO;
                    };
                }

                // An instanceof-pattern if whose then branch contains a nested conditional yield.
                static boolean instanceOfYield(Action action, Inner inner) {
                    return switch (action) {
                        case Check c -> {
                            if (inner instanceof Large l) {
                                if (l.v().compareTo(c.n()) > 0) { yield false; }
                            }
                            yield true;
                        }
                        case Stop s -> false;
                    };
                }

                // Failure timing: the trailing failure must not run when the guard yields.
                static BigInteger yieldSkipsTrailingFailure(Action action) {
                    return switch (action) {
                        case Check c -> {
                            if (c.n().signum() > 0) { yield c.n(); }
                            Builtins.error();
                            yield BigInteger.ZERO;
                        }
                        case Stop s -> BigInteger.ZERO;
                    };
                }

                // Control: if/else yield as the last statement of the block (the shape used by
                // OutputLib.resolveDatum) lowered correctly before and must still do so.
                static BigInteger ifElseYieldLast(Action action) {
                    return switch (action) {
                        case Check c -> {
                            if (c.n().signum() > 0) {
                                yield c.n();
                            } else {
                                yield BigInteger.valueOf(-1);
                            }
                        }
                        case Stop s -> BigInteger.ZERO;
                    };
                }
            }
            """;

    private static final List<OptimizationLevel> LEVELS =
            List.of(OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE);

    private static PlutusData check(long n) {
        return PlutusData.constr(0, PlutusData.integer(n));
    }

    private static PlutusData stop() {
        return PlutusData.constr(1);
    }

    private static PlutusData small(long v) {
        return PlutusData.constr(0, PlutusData.integer(v));
    }

    private static PlutusData large(long v) {
        return PlutusData.constr(1, PlutusData.integer(v));
    }

    private static PlutusData integer(long v) {
        return PlutusData.integer(v);
    }

    private static CompileResult compile(String method, OptimizationLevel level) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level));
        var result = compiler.compileMethod(SOURCE, method);
        assertFalse(result.hasErrors(), () -> method + ": " + result.diagnostics());
        return result;
    }

    private static EvalResult evaluate(String provider, CompileResult compiled, PlutusData... args) {
        var vm = CompilerTestVm.pv11(provider);
        return provider.equals("Scalus")
                ? vm.evaluateWithArgs(compiled.program(), List.of(args))
                : vm.evaluateWithArgs(compiled.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                        List.of(args), null, EvalOptions.DEFAULT);
    }

    private static Term success(EvalResult result) {
        assertInstanceOf(EvalResult.Success.class, result, () -> "expected success: " + result);
        return ((EvalResult.Success) result).resultTerm();
    }

    /** Evaluate on the Java VM under the default profile. */
    private static Term run(String method, PlutusData... args) {
        return success(evaluate("Java", compile(method, OptimizationLevel.PV11_SAFE), args));
    }

    private static Term bool(boolean b) {
        return Term.const_(Constant.bool(b));
    }

    private static Term integerTerm(long v) {
        return Term.const_(Constant.integer(v));
    }

    // ===== #137 reproducer =====

    @Test
    void conditionalYieldTakenBranchShortCircuitsTrailingYield() {
        assertEquals(bool(false), run("conditionalYield", check(1)));
        assertEquals(bool(true), run("conditionalYield", check(0)));
        assertEquals(bool(true), run("conditionalYield", check(-3)));
        assertEquals(bool(false), run("conditionalYield", stop()));
    }

    @Test
    void reproducerAgreesAcrossProfilesOnJavaAndScalus() {
        for (var level : LEVELS) {
            var compiled = compile("conditionalYield", level);
            for (String provider : List.of("Java", "Scalus")) {
                var label = level + "/" + provider;
                assertEquals(bool(false), success(evaluate(provider, compiled, check(1))), label);
                assertEquals(bool(true), success(evaluate(provider, compiled, check(0))), label);
                assertEquals(bool(false), success(evaluate(provider, compiled, stop())), label);
            }
        }
    }

    @Test
    @Tag("pair-case-backends")
    void reproducerAgreesAcrossProfilesOnTruffle() {
        for (var level : LEVELS) {
            var compiled = compile("conditionalYield", level);
            assertEquals(bool(false), success(evaluate("Truffle", compiled, check(1))), level.toString());
            assertEquals(bool(true), success(evaluate("Truffle", compiled, check(0))), level.toString());
            assertEquals(bool(false), success(evaluate("Truffle", compiled, stop())), level.toString());
        }
    }

    // ===== Control-flow shapes =====

    @Test
    void elseBranchYieldShortCircuitsTrailingYield() {
        assertEquals(integerTerm(15), run("elseYields", check(5)));
        assertEquals(integerTerm(-1), run("elseYields", check(0)));
        assertEquals(integerTerm(-1), run("elseYields", check(-2)));
    }

    @Test
    void nestedYieldsShortCircuitAtEveryDepth() {
        // n <= 0: outermost fall-through
        assertEquals(integerTerm(100), run("nestedYields", check(0), integer(1), integer(1)));
        // n > 0, b <= 0: second-level fall-through
        assertEquals(integerTerm(7), run("nestedYields", check(1), integer(-3), integer(1)));
        // n > 0, b > 0, d < 0: deepest guard
        assertEquals(integerTerm(-1), run("nestedYields", check(1), integer(2), integer(-9)));
        // n > 0, b > 0, d >= 0: deepest fall-through
        assertEquals(integerTerm(5), run("nestedYields", check(1), integer(2), integer(4)));
    }

    @Test
    void innerSwitchYieldsBelongToInnerSwitch() {
        // Inner guard taken: Small(-5) scales to 0, not -5; outer guard not taken.
        assertEquals(integerTerm(10), run("innerSwitchOwnership", check(10), small(-5)));
        // Inner fall-through, outer fall-through.
        assertEquals(integerTerm(13), run("innerSwitchOwnership", check(10), small(3)));
        // Outer guard taken after inner fall-through.
        assertEquals(integerTerm(-1), run("innerSwitchOwnership", check(10), small(50)));
        // Non-block inner arm, outer guard taken / not taken.
        assertEquals(integerTerm(-1), run("innerSwitchOwnership", check(10), large(2)));
        assertEquals(integerTerm(120), run("innerSwitchOwnership", check(100), large(2)));
    }

    @Test
    void instanceOfPatternYieldShortCircuits() {
        assertEquals(bool(false), run("instanceOfYield", check(5), large(9)));
        assertEquals(bool(true), run("instanceOfYield", check(5), large(2)));
        assertEquals(bool(true), run("instanceOfYield", check(5), small(9)));
    }

    @Test
    void takenYieldSkipsTrailingFailureAndFallThroughReachesIt() {
        var compiled = compile("yieldSkipsTrailingFailure", OptimizationLevel.PV11_SAFE);
        assertEquals(integerTerm(5), success(evaluate("Java", compiled, check(5))));
        assertInstanceOf(EvalResult.Failure.class, evaluate("Java", compiled, check(0)));
    }

    @Test
    void ifElseYieldAsLastStatementStillLowersCorrectly() {
        assertEquals(integerTerm(4), run("ifElseYieldLast", check(4)));
        assertEquals(integerTerm(-1), run("ifElseYieldLast", check(0)));
        assertEquals(integerTerm(0), run("ifElseYieldLast", stop()));
    }

    // ===== Diagnostics =====

    @Test
    void caseBlockWithoutYieldOnEveryPathIsDiagnosed() {
        var source = """
                import java.math.BigInteger;

                class MissingYield {
                    sealed interface Action permits Check, Stop {}
                    record Check(BigInteger n) implements Action {}
                    record Stop() implements Action {}

                    static boolean check(Action action) {
                        return switch (action) {
                            case Check c -> {
                                if (c.n().signum() > 0) { yield false; }
                            }
                            case Stop s -> false;
                        };
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "check"));
        assertTrue(ex.getMessage().contains("switch case block may not yield a value on all execution paths"),
                () -> "unexpected message: " + ex.getMessage());
    }

    // A trailing loop satisfies the method-level missing-return check (loop-accumulator
    // return pattern) but must never satisfy the case-block yield check: the loop's
    // accumulator is not a yield, so the block would silently produce it on the
    // fall-through path.
    @Test
    void caseBlockEndingInWhileLoopIsDiagnosed() {
        var source = """
                import java.math.BigInteger;

                class TrailingWhile {
                    sealed interface Action permits Check, Stop {}
                    record Check(BigInteger n) implements Action {}
                    record Stop() implements Action {}

                    static boolean check(Action action) {
                        return switch (action) {
                            case Check c -> {
                                if (c.n().signum() > 0) { yield false; }
                                boolean acc = true;
                                while (!acc) { acc = true; }
                            }
                            case Stop s -> false;
                        };
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "check"));
        assertTrue(ex.getMessage().contains("switch case block may not yield a value on all execution paths"),
                () -> "unexpected message: " + ex.getMessage());
    }

    @Test
    void caseBlockEndingInForEachLoopIsDiagnosed() {
        var source = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;

                class TrailingForEach {
                    sealed interface Action permits Check, Stop {}
                    record Check(BigInteger n) implements Action {}
                    record Stop() implements Action {}

                    static boolean anyPositive(Action action, JulcList<BigInteger> xs) {
                        return switch (action) {
                            case Check c -> {
                                boolean found = false;
                                for (BigInteger x : xs) {
                                    if (x.signum() > 0) { found = true; }
                                }
                            }
                            case Stop s -> false;
                        };
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "anyPositive"));
        assertTrue(ex.getMessage().contains("switch case block may not yield a value on all execution paths"),
                () -> "unexpected message: " + ex.getMessage());
    }

    @Test
    void caseBlockBranchEndingInLoopIsDiagnosed() {
        var source = """
                import java.math.BigInteger;

                class NestedTrailingWhile {
                    sealed interface Action permits Check, Stop {}
                    record Check(BigInteger n) implements Action {}
                    record Stop() implements Action {}

                    static boolean check(Action action) {
                        return switch (action) {
                            case Check c -> {
                                if (c.n().signum() > 0) {
                                    yield false;
                                } else {
                                    boolean acc = true;
                                    while (!acc) { acc = true; }
                                }
                            }
                            case Stop s -> false;
                        };
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "check"));
        assertTrue(ex.getMessage().contains("switch case block may not yield a value on all execution paths"),
                () -> "unexpected message: " + ex.getMessage());
    }

    @Test
    void yieldInsideForEachBodyIsRejected() {
        var source = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;

                class YieldInLoop {
                    sealed interface Action permits Check, Stop {}
                    record Check(BigInteger n) implements Action {}
                    record Stop() implements Action {}

                    static boolean anyPositive(Action action, JulcList<BigInteger> xs) {
                        return switch (action) {
                            case Check c -> {
                                for (BigInteger x : xs) {
                                    if (x.signum() > 0) { yield true; }
                                }
                                yield false;
                            }
                            case Stop s -> false;
                        };
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "anyPositive"));
        assertTrue(ex.getMessage().contains("'yield' is not supported inside for-each loop body"),
                () -> "unexpected message: " + ex.getMessage());
    }

    @Test
    void yieldInsideWhileBodyIsRejected() {
        var source = """
                import java.math.BigInteger;

                class YieldInWhile {
                    sealed interface Action permits Check, Stop {}
                    record Check(BigInteger n) implements Action {}
                    record Stop() implements Action {}

                    static boolean countdown(Action action) {
                        return switch (action) {
                            case Check c -> {
                                long counter = 3;
                                while (counter > 0) {
                                    if (counter == 1) { yield true; }
                                    counter = counter - 1;
                                }
                                yield false;
                            }
                            case Stop s -> false;
                        };
                    }
                }
                """;
        var ex = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "countdown"));
        assertTrue(ex.getMessage().contains("'yield' is not supported inside while loop body"),
                () -> "unexpected message: " + ex.getMessage());
    }
}
