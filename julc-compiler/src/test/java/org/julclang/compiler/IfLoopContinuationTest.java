package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Semantic regression coverage for loops whose state crosses an enclosing if (#161). */
class IfLoopContinuationTest {
    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.ContextsLib;
            import org.julclang.stdlib.annotation.Param;
            class BranchLoops {
                sealed interface Action permits Only, Other {}
                record Only(BigInteger value) implements Action {}
                record Other() implements Action {}
                static BigInteger helper(BigInteger n) { return n.add(BigInteger.TEN); }
                static boolean tracedCondition(BigInteger mode) {
                    ContextsLib.trace("condition");
                    return mode.signum() > 0;
                }
            """;

    private static String source(String body, boolean switchArm) {
        return HEADER + "static BigInteger m(JulcList<BigInteger> xs, BigInteger mode) { "
                + (switchArm ? "Action action = new Only(mode); return switch (action) { case Only o -> { " : "")
                + (switchArm ? body.replace("return ", "yield ") : body)
                + (switchArm ? " } case Other o -> BigInteger.ZERO; };" : "") + " } }";
    }

    private static CompileResult compile(String source, OptimizationLevel level) {
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "m");
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        return result;
    }

    private static EvalResult evaluate(String backend, CompileResult compiled, PlutusData xs, long mode) {
        return evaluate(backend, compiled, List.of(xs, PlutusData.integer(mode)));
    }

    private static EvalResult evaluate(String backend, CompileResult compiled, List<PlutusData> args) {
        var vm = CompilerTestVm.pv11(backend);
        return backend.equals("Scalus") ? vm.evaluateWithArgs(compiled.program(), args)
                : vm.evaluateWithArgs(compiled.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                        args, null, EvalOptions.DEFAULT);
    }

    private static void assertValue(String backend, CompileResult compiled, PlutusData xs,
                                    long mode, long expected, String label) {
        var result = evaluate(backend, compiled, xs, mode);
        var success = assertInstanceOf(EvalResult.Success.class, result, label + ": " + result);
        assertEquals(Term.const_(Constant.integer(expected)), success.resultTerm(), label);
    }

    @Test
    void reproducer() {
        var body = """
                BigInteger total = BigInteger.ZERO;
                if (xs.size().compareTo(BigInteger.ZERO) > 0) {
                    for (var x : xs) { total = total.add(x); }
                }
                return total;
                """;
        for (boolean arm : List.of(false, true)) {
            var compiled = compile(source(body, arm), OptimizationLevel.NONE);
            assertValue("Java", compiled, PlutusData.list(PlutusData.integer(1),
                    PlutusData.integer(2), PlutusData.integer(3)), 0, 6, "arm=" + arm);
        }
    }

    @Test
    void stateAcrossBranchesOnJava() {
        checkState("Java");
        checkComposition("Java");
        checkEffects("Java");
        checkShadowedJoinArgument("Java");
    }

    @Test
    @Tag("pair-case-backends")
    void stateAcrossBranchesOnOtherBackends() {
        checkState("Truffle");
        checkState("Scalus");
        checkComposition("Truffle");
        checkComposition("Scalus");
        checkEffects("Truffle");
        checkEffects("Scalus");
        checkShadowedJoinArgument("Truffle");
        checkShadowedJoinArgument("Scalus");
    }

    private record Fixture(String body, long taken, long untaken) {}

    @Test
    void switchArmGuardedLoopsInsideOuterLoopsOnJava() {
        checkSwitchArmInOuterLoop("Java");
    }

    @Test
    @Tag("pair-case-backends")
    void switchArmGuardedLoopsInsideOuterLoopsOnOtherBackends() {
        checkSwitchArmInOuterLoop("Truffle");
        checkSwitchArmInOuterLoop("Scalus");
    }

    private static void checkSwitchArmInOuterLoop(String backend) {
        for (boolean outerWhile : List.of(false, true)) {
            for (boolean innerWhile : List.of(false, true)) {
                for (boolean nestedSwitch : List.of(false, true)) {
                    String inner = innerWhile
                            ? "BigInteger j = BigInteger.ZERO; while (j.compareTo(xs.size()) < 0) { "
                                + "local = local.add(xs.get(j)); j = j.add(BigInteger.ONE); }"
                            : "for (var y : xs) { local = local.add(y); }";
                    String arm = "BigInteger local = BigInteger.ZERO; "
                            + "if (o.value().signum() > 0) { " + inner + " } "
                            + "ContextsLib.trace(\"arm\"); yield local;";
                    String value = "switch (action) { case Only o -> { " + arm
                            + " } case Other o -> BigInteger.ZERO; }";
                    if (nestedSwitch) {
                        value = "switch (action) { case Only p -> { yield " + value
                                + "; } case Other p -> BigInteger.ZERO; }";
                    }
                    String body = "Action action = mode.signum() < 0 ? new Other() : new Only(mode); "
                            + "BigInteger acc = BigInteger.ZERO; BigInteger i = BigInteger.ZERO; "
                            + (outerWhile ? "while (i.compareTo(xs.size()) < 0) { " : "for (var x : xs) { ")
                            + "BigInteger step = " + value + "; "
                            + "ContextsLib.trace(\"after\"); acc = acc.add(step); i = i.add(BigInteger.ONE); "
                            + "} return acc;";
                    for (var level : OptimizationLevel.values()) {
                        var src = source(body, false);
                        var compiled = compile(src, level);
                        assertArrayEquals(UplcFlatEncoder.encodeProgram(compiled.program()),
                                UplcFlatEncoder.encodeProgram(compile(src, level).program()));
                        var inputs = List.of(PlutusData.list(),
                                PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)),
                                PlutusData.list(PlutusData.integer(-1), PlutusData.integer(0), PlutusData.integer(2)));
                        for (int n = 0; n < inputs.size(); n++) {
                            for (long mode : List.of(-1L, 0L, 1L)) {
                                String label = backend + "/" + level + "/" + outerWhile + "/" + innerWhile
                                        + "/" + nestedSwitch + "/" + n + "/" + mode;
                                var result = evaluate(backend, compiled, inputs.get(n), mode);
                                var success = assertInstanceOf(EvalResult.Success.class, result, label);
                                long expected = mode <= 0 || n == 0 ? 0 : n == 1 ? 18 : 3;
                                assertEquals(Term.const_(Constant.integer(expected)), success.resultTerm(), label);
                                var traces = n == 0 ? List.of() : mode < 0
                                        ? List.of("after", "after", "after")
                                        : List.of("arm", "after", "arm", "after", "arm", "after");
                                assertEquals(traces, result.traces(), label);
                            }
                        }
                    }
                }
            }
        }
    }

    private static final List<Fixture> COMPOSED = List.of(
            new Fixture("""
                    BigInteger total = BigInteger.ZERO;
                    if (mode.signum() > 0) {
                        for (var x : xs) { ContextsLib.trace("side"); }
                        for (var x : xs) { total = total.add(x); }
                    }
                    return total;
                    """, 2, 0),
            // A local may legally share a method name; it must not capture the later call.
            new Fixture("""
                    if (mode.signum() > 0) {
                        BigInteger helper = BigInteger.ZERO;
                        for (var x : xs) { helper = helper.add(x); }
                    }
                    return helper(BigInteger.ONE);
                    """, 11, 11),
            new Fixture("""
                    Action action = mode.signum() > 0 ? new Only(BigInteger.TEN) : new Other();
                    BigInteger total = BigInteger.ZERO;
                    if (action instanceof Only helper) {
                        for (var x : xs) { total = total.add(helper.value()); }
                    }
                    return helper(total);
                    """, 40, 10),
            // Braceless branches and signed values; both branches perform different work.
            new Fixture("""
                    BigInteger total = BigInteger.TEN;
                    if (mode.signum() > 0) for (var x : xs) { total = total.add(x); }
                    else for (var x : xs) { total = total.subtract(x); }
                    return total;
                    """, 12, 8),
            // A continuation includes another loop and another conditional join.
            new Fixture("""
                    BigInteger total = BigInteger.ZERO;
                    if (mode.signum() > 0) {
                        for (var x : xs) { total = total.add(x); }
                        if (total.signum() > 0) {
                            for (var y : xs) { total = total.add(y); }
                        }
                    }
                    for (var z : xs) { total = total.add(z); }
                    return total;
                    """, 6, 2),
            // Distinct accumulator sets in each branch; retain untouched bindings.
            new Fixture("""
                    BigInteger left = BigInteger.ONE;
                    BigInteger right = BigInteger.TEN;
                    if (mode.signum() > 0) {
                        for (var x : xs) { left = left.add(x); }
                    } else {
                        for (var x : xs) { right = right.add(x); }
                    }
                    return left.multiply(BigInteger.TEN).add(right);
                    """, 40, 22),
            // Branch-local accumulator explicitly returned, with an unreachable failure.
            new Fixture("""
                    if (mode.signum() > 0) {
                        BigInteger total = BigInteger.ZERO;
                        for (var x : xs) { total = total.add(x); }
                        return total;
                    }
                    if (mode.signum() > 0) { Builtins.error(); }
                    return BigInteger.TEN;
                    """, 2, 10),
            // Earlier branch-local names must not affect a later declaration's type or value.
            new Fixture("""
                    BigInteger total = BigInteger.ZERO;
                    if (mode.signum() > 0) {
                        boolean later = true;
                        for (var x : xs) { total = total.add(x); }
                    }
                    BigInteger later = total.add(BigInteger.TEN);
                    return later;
                    """, 12, 10),
            // Pattern branch scope and accumulator propagation use the same CPS path.
            new Fixture("""
                    Action action = mode.signum() > 0 ? new Only(BigInteger.TEN) : new Other();
                    BigInteger total = BigInteger.ZERO;
                    if (action instanceof Only p) {
                        for (var x : xs) { total = total.add(p.value()).add(x); }
                    }
                    BigInteger p = total.add(BigInteger.ONE);
                    return p;
                    """, 33, 1),
            // Nested switch expressions own their loops and yield only their result.
            new Fixture("""
                    BigInteger total = BigInteger.ZERO;
                    if (mode.signum() > 0) {
                        Action inner = new Only(BigInteger.TEN);
                        BigInteger step = switch (inner) {
                            case Only p -> {
                                BigInteger local = BigInteger.ZERO;
                                if (mode.signum() > 0) {
                                    for (var x : xs) { local = local.add(x); }
                                }
                                yield local;
                            }
                            case Other p -> BigInteger.ZERO;
                        };
                        for (var x : xs) { total = total.add(step); }
                    }
                    return total;
                    """, 6, 0),
            // Single native accumulators must remain native, without a new Data join.
            new Fixture("""
                    var original = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, new byte[]{});
                    var point = original;
                    BigInteger total = BigInteger.ZERO;
                    if (mode.signum() > 0) {
                        for (var x : xs) { point = Builtins.bls12_381_G1_neg(point); }
                        for (var x : xs) { total = total.add(x); }
                    }
                    return Builtins.bls12_381_G1_equal(point, original) ? total : total.add(BigInteger.TEN);
                    """, 12, 0),
            new Fixture("""
                    var original = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, new byte[]{});
                    var point = original;
                    if (mode.signum() > 0) {
                        for (var x : xs) { point = Builtins.bls12_381_G1_neg(point); }
                    }
                    return Builtins.bls12_381_G1_equal(point, original) ? BigInteger.ZERO : BigInteger.ONE;
                    """, 1, 0));

    private static void checkComposition(String backend) {
        var xs = PlutusData.list(PlutusData.integer(1), PlutusData.integer(-2), PlutusData.integer(3));
        for (boolean arm : List.of(false, true)) {
            for (var fixture : COMPOSED) {
                for (var level : OptimizationLevel.values()) {
                    var compiled = compile(source(fixture.body(), arm), level);
                    String label = backend + "/" + level + "/arm=" + arm + "/" + fixture.body();
                    assertValue(backend, compiled, xs, 1, fixture.taken(), label);
                    assertValue(backend, compiled, xs, 0, fixture.untaken(), label);
                }
            }
        }
    }

    private static void checkEffects(String backend) {
        String body = """
                BigInteger total = BigInteger.ZERO;
                ContextsLib.trace("before");
                if (tracedCondition(mode)) {
                    ContextsLib.trace("branch");
                    for (var x : xs) {
                        ContextsLib.trace("iteration");
                        total = total.add(x);
                    }
                } else {
                    ContextsLib.trace("failure");
                    Builtins.error();
                }
                ContextsLib.trace("after");
                return total;
                """;
        for (boolean arm : List.of(false, true)) {
            for (var level : OptimizationLevel.values()) {
                // Empty parameter set: the last loop in the branch must still run the tail
                // afterward, and constructing the join must not execute that tail early.
                var sideEffectOnly = compile(source(body.replace("total = total.add(x);", ""), arm), level);
                var sideResult = assertInstanceOf(EvalResult.Success.class, evaluate(backend, sideEffectOnly,
                        PlutusData.list(PlutusData.integer(1), PlutusData.integer(2)), 1));
                assertEquals(Term.const_(Constant.integer(0)), sideResult.resultTerm());
                assertEquals(List.of("before", "condition", "branch", "iteration", "iteration", "after"), sideResult.traces());
                var compiled = compile(source(body, arm), level);
                var xs = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2));
                var success = assertInstanceOf(EvalResult.Success.class, evaluate(backend, compiled, xs, 1));
                assertEquals(Term.const_(Constant.integer(3)), success.resultTerm());
                assertEquals(List.of("before", "condition", "branch", "iteration", "iteration", "after"), success.traces());
                var failure = assertInstanceOf(EvalResult.Failure.class, evaluate(backend, compiled, xs, 0));
                assertEquals(List.of("before", "condition", "failure"), failure.traces());
                // Malformed list element fails during the loop, before the continuation runs.
                var invalid = PlutusData.list(PlutusData.constr(0));
                var decodeFailure = assertInstanceOf(EvalResult.Failure.class, evaluate(backend, compiled, invalid, 1));
                assertFalse(decodeFailure.traces().contains("after"));
            }
        }
    }

    private static void checkShadowedJoinArgument(String backend) {
        String source = HEADER + """
                @Param static BigInteger total;
                static BigInteger m(JulcList<BigInteger> xs, BigInteger mode) {
                    if (mode.signum() > 0) {
                        BigInteger total = BigInteger.ZERO;
                        for (var x : xs) { total = total.add(x); }
                    } else {
                        for (var x : xs) { total = total.add(x); }
                    }
                    return total;
                }
            }
            """;
        for (var level : OptimizationLevel.values()) {
            var compiled = compile(source, level);
            var xs = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2));
            for (int mode : List.of(0, 1)) {
                var result = evaluate(backend, compiled, List.of(PlutusData.integer(7), xs, PlutusData.integer(mode)));
                var success = assertInstanceOf(EvalResult.Success.class, result, backend + "/" + level + ": " + result);
                assertEquals(Term.const_(Constant.integer(mode == 1 ? 7 : 10)), success.resultTerm());
            }
        }
    }

    @Test
    void branchLocalsDoNotEscapeAndUnsupportedAssignmentsRemainRejected() {
        for (var level : OptimizationLevel.values()) {
            for (String body : List.of(
                    "if (mode.signum() > 0) { BigInteger local = BigInteger.ZERO; for (var x : xs) { local = local.add(x); } } return local;",
                    "BigInteger total = BigInteger.ZERO; if (mode.signum() > 0) { total = BigInteger.ONE; for (var x : xs) { total = total.add(x); } } return total;")) {
                assertThrows(CompilerException.class, () -> compile(source(body, false), level));
            }
        }
    }

    @Test
    void diagnosticsUseSourceNamesInContinuationBearingBranches() {
        for (var level : OptimizationLevel.values()) {
            for (String declaration : List.of("BigInteger t;", "BigInteger t = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, new byte[]{});")) {
                var body = "if (mode.signum() > 0) { " + declaration
                        + " for (var x : xs) {} } return BigInteger.ZERO;";
                var error = assertThrows(CompilerException.class, () -> compile(source(body, false), level));
                assertTrue(error.getMessage().contains("t"), error.getMessage());
                assertFalse(error.getMessage().matches("(?s).*t'[0-9]+.*"), error.getMessage());
                if (declaration.equals("BigInteger t;")) {
                    assertTrue(error.getMessage().contains("Variable must be initialized: t."), error.getMessage());
                    assertTrue(error.getMessage().contains("var t = BigInteger.ZERO;"), error.getMessage());
                } else {
                    assertTrue(error.getMessage().contains("Variable 't' initializer"), error.getMessage());
                }
            }
        }
    }

    @Test
    void sequentialGuardedLoopsHaveLinearFlatSizeAtEveryLevel() {
        for (var level : OptimizationLevel.values()) {
            int previous = 0;
            int firstIncrement = 0;
            for (int n = 1; n <= 12; n++) {
                String body = "BigInteger total = BigInteger.ZERO; "
                        + "if (mode.signum() > 0) { for (var x : xs) { total = total.add(x); } } ".repeat(n)
                        + "return total;";
                var compiled = compile(source(body, false), level);
                int size = UplcFlatEncoder.encodeProgram(compiled.program()).length;
                if (n == 2) firstIncrement = size - previous;
                if (n > 2) assertTrue(size - previous <= 2 * firstIncrement,
                        level + "/" + n + ": unexpected size growth " + previous + " -> " + size);
                System.out.println("guarded-loop FLAT bytes " + level + "/" + n + ": " + size);
                assertValue("Java", compiled, PlutusData.list(PlutusData.integer(2)), 1, 2L * n, level + "/" + n);
                assertValue("Java", compiled, PlutusData.list(PlutusData.integer(2)), 0, 0, level + "/" + n);
                previous = size;
            }
        }
    }

    private static void checkState(String backend) {
        for (boolean arm : List.of(false, true)) {
            for (boolean whileLoop : List.of(false, true)) {
                for (boolean multi : List.of(false, true)) {
                    for (boolean breaks : List.of(false, true)) {
                        String loop = (whileLoop ? "while (total.compareTo(xs.size()) < 0) { "
                                : "for (var x : xs) { ")
                                + "total = total.add(BigInteger.ONE); "
                                + (multi ? "count = count.add(BigInteger.TEN); " : "")
                                + (breaks ? "if (total.equals(BigInteger.TWO)) { break; } " : "") + "}";
                        for (int shape = 0; shape < 4; shape++) {
                            String branch = switch (shape) {
                                case 0 -> "if (mode.signum() > 0) { " + loop + " }";
                                case 1 -> "if (mode.signum() <= 0) {} else { " + loop + " }";
                                case 2 -> "if (mode.signum() > 0) { if (xs.size().signum() > 0) { " + loop + " } }";
                                default -> "if (mode.signum() > 0) { " + loop + " } else { " + loop + " }";
                            };
                            String body = "BigInteger total = BigInteger.ZERO; "
                                    + (multi ? "BigInteger count = BigInteger.ZERO; " : "")
                                    + branch + " return " + (multi ? "total.add(count)" : "total") + ";";
                            for (var level : OptimizationLevel.values()) {
                                String label = backend + "/" + level + "/" + arm + "/" + whileLoop
                                        + "/" + multi + "/" + breaks + "/" + shape;
                                var text = source(body, arm);
                                var compiled = compile(text, level);
                                assertArrayEquals(UplcFlatEncoder.encodeProgram(compiled.program()),
                                        UplcFlatEncoder.encodeProgram(compile(text, level).program()), label);
                                for (int size : List.of(0, 1, 3)) {
                                    var xs = PlutusData.list(Collections.nCopies(size, PlutusData.integer(7)).toArray(PlutusData[]::new));
                                    long expected = (breaks ? Math.min(size, 2) : size) * (multi ? 11 : 1);
                                    assertValue(backend, compiled, xs, 1, expected, label);
                                    assertValue(backend, compiled, xs, 0, shape == 3 ? expected : 0, label);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
