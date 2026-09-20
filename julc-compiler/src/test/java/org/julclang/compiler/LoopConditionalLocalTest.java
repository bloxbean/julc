package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for #155, adjacent to the bare-block cases in LoopBlockAssignmentTest. */
class LoopConditionalLocalTest {
    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            class Loops {
                sealed interface Action permits Only {}
                record Only() implements Action {}
                sealed interface PatternAction permits WithValue {}
                record WithValue(BigInteger value) implements PatternAction {}
            """;

    private static String method(String body) {
        return HEADER + "static BigInteger m(JulcList<BigInteger> xs) { " + body + " } }";
    }

    private static CompileResult compile(String source, OptimizationLevel level) {
        return compile(source, level, false);
    }

    private static CompileResult compile(String source, OptimizationLevel level, boolean sourceMaps) {
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(sourceMaps)).compileMethod(source, "m");
        if (result.hasErrors()) throw new CompilerException(result.diagnostics().toString());
        return result;
    }

    @Test
    void conditionalUpdatesAreRejectedAtEveryLevel() {
        // Single/multi accumulator, both loop forms, with/without break: all four body generators.
        for (boolean multi : List.of(false, true)) {
            for (boolean whileLoop : List.of(false, true)) {
                for (boolean breaks : List.of(false, true)) {
                    for (String branch : List.of(
                            "if (x.compareTo(BigInteger.ZERO) > 0) { step = step.add(BigInteger.ONE); }",
                            "if (x.compareTo(BigInteger.ZERO) > 0) step = x; else step = BigInteger.ONE;",
                            "if (x.compareTo(BigInteger.ZERO) > 0) {} else { step = BigInteger.ONE; }",
                            "if (x.compareTo(BigInteger.ZERO) > 0) { if (x.equals(BigInteger.TWO)) { { step = x; } } }",
                            "if (true) { step = x; }")) {
                        var body = "BigInteger acc = BigInteger.ZERO; "
                                + (multi ? "BigInteger n = BigInteger.ZERO; " : "")
                                + (whileLoop ? "while (acc.compareTo(BigInteger.TEN) < 0) { BigInteger x = BigInteger.ONE; "
                                : "for (var x : xs) { ")
                                + "BigInteger step = BigInteger.ZERO; " + branch + " acc = acc.add(step); "
                                + (multi ? "n = n.add(BigInteger.ONE); " : "")
                                + (breaks ? "if (acc.compareTo(BigInteger.TEN) > 0) { break; } " : "")
                                + "} return " + (multi ? "acc.multiply(BigInteger.TEN).add(n)" : "acc") + ";";
                        assertRejected(body, "step");
                    }
                }
            }
        }
    }

    @Test
    void lexicalScopeAndNestedLoopsCannotHideAConditionalUpdate() {
        for (String body : List.of(
                "{ BigInteger step = BigInteger.ZERO; if (true) { { step = x; } } acc = acc.add(step); }",
                "if (true) { BigInteger step = BigInteger.ZERO; if (true) { step = x; } acc = acc.add(step); }",
                "BigInteger step = BigInteger.ZERO; if (true) { for (var y : xs) { step = step.add(y); } } acc = acc.add(step);",
                "BigInteger step = BigInteger.ZERO; if (true) { while (step.compareTo(x) < 0) { step = step.add(BigInteger.ONE); } } acc = acc.add(step);",
                "for (var y : xs) { BigInteger step = BigInteger.ZERO; if (true) { step = y; } acc = acc.add(step); }")) {
            assertRejected("BigInteger acc = BigInteger.ZERO; for (var x : xs) { " + body + " } return acc;", "step");
        }
        assertRejected("BigInteger acc = BigInteger.ZERO; for (var x : xs) { if (true) { x = BigInteger.ONE; } acc = acc.add(x); } return acc;", "x");
        // The check also runs for a loop with no accumulator; no dead-update special case.
        assertRejected("for (var x : xs) { BigInteger step = BigInteger.ZERO; if (false) { step = x; } } return BigInteger.ZERO;", "step");
        assertRejected("BigInteger acc = BigInteger.ZERO; for (var x : xs) { var step = Builtins.bls12_381_G1_hashToGroup(new byte[]{1}, new byte[]{}); if (true) { step = step; } acc = acc.add(x); } return acc;", "step");
    }

    private static void assertRejected(String body, String name) {
        assertSourceRejected(method(body), name);
    }

    @Test
    void enclosingIfRestrictionSurvivesSwitchExpressionTraversal() {
        var update = "switch (action) { case Only o -> { for (var y : xs) { step = step.add(y); } yield BigInteger.ZERO; } }";
        for (String expressionUse : List.of(
                "BigInteger ignored = " + update + ";",
                "if ((" + update + ").equals(BigInteger.ZERO)) { acc = acc.add(x); }",
                "while ((" + update + ").compareTo(BigInteger.ZERO) > 0) { acc = acc.add(x); break; }",
                "for (var y : ((" + update + ").equals(BigInteger.ZERO) ? xs : xs)) { acc = acc.add(y); }")) {
            assertSourceRejected(HEADER + "static BigInteger m(JulcList<BigInteger> xs, Action action) { "
                    + "BigInteger acc = BigInteger.ZERO; for (var x : xs) { BigInteger step = BigInteger.ZERO; "
                    + "if (true) { " + expressionUse + " } acc = acc.add(step); } return acc; } }", "step");
        }
    }

    @Test
    void switchArmsCannotMutateEnclosingLocalsOrAccumulatorsWithoutAnIf() {
        for (String target : List.of("step", "acc")) {
            for (String loop : List.of(
                    "for (var y : xs) { " + target + " = " + target + ".add(y); }",
                    "while (" + target + ".compareTo(BigInteger.TEN) < 0) { " + target + " = " + target + ".add(BigInteger.ONE); }",
                    "for (var y : xs) { " + target + " = " + target + ".add(y); if (y.equals(BigInteger.TWO)) { break; } }")) {
                for (boolean enclosingLoop : List.of(false, true)) {
                    String body = "BigInteger acc = BigInteger.ZERO; " + (enclosingLoop ? "for (var x : xs) { " : "")
                            + "BigInteger step = BigInteger.ZERO; BigInteger ignored = switch (action) { case Only o -> { "
                            + loop + " yield BigInteger.ZERO; } }; acc = acc.add(step); "
                            + (enclosingLoop ? "} " : "") + "return acc;";
                    assertSwitchRejected(body, target);
                }
            }
        }
        // An inner switch also cannot update an accumulator declared in an outer arm.
        assertSwitchRejected("""
                return switch (action) { case Only o -> {
                    BigInteger step = BigInteger.ZERO;
                    BigInteger ignored = switch (action) { case Only p -> {
                        for (var y : xs) { step = step.add(y); }
                        yield BigInteger.ZERO;
                    } };
                    yield step;
                } };
                """, "step");
        for (String invalidScope : List.of(
                "if (action instanceof Only p) {} else { for (var y : xs) { p = new Only(); } }",
                "if (action instanceof Only p) {} for (var y : xs) { p = new Only(); }")) {
            assertSwitchRejected("return switch (action) { case Only o -> { " + invalidScope
                    + " yield BigInteger.ZERO; } };", "p");
        }
    }

    private static void assertSwitchRejected(String body, String name) {
        String source = HEADER + "static BigInteger m(JulcList<BigInteger> xs, Action action) { " + body + " } }";
        for (var level : OptimizationLevel.values()) {
            for (boolean sourceMaps : List.of(false, true)) {
                var error = assertThrows(CompilerException.class, () -> compile(source, level, sourceMaps));
                assertTrue(error.getMessage().contains("Switch-expression arm cannot update enclosing variable '" + name + "'"), error.getMessage());
                assertTrue(error.getMessage().contains("yield its result"), error.getMessage());
                assertTrue(error.getMessage().matches("(?s).*Loops\\.java:\\d+:\\d+:.*"), error.getMessage());
            }
        }
    }

    @Test
    void casePatternReassignmentFailsClosedEvenWhenOnlyTheRecordIsYielded() {
        for (String update : List.of(
                "p = new WithValue(BigInteger.ONE);",
                "for (var y : xs) { p = new WithValue(y); }",
                "while (p.value().compareTo(BigInteger.TEN) < 0) { p = new WithValue(BigInteger.TEN); }",
                "for (var y : xs) { { p = new WithValue(y); } if (y.equals(BigInteger.TWO)) { break; } }",
                "for (var y : xs) { if (y.compareTo(BigInteger.ZERO) > 0) { p = new WithValue(y); } }",
                "for (var y : xs) { for (var z : xs) { p = new WithValue(z); } }")) {
            for (boolean yieldRecord : List.of(false, true)) {
                var source = method("PatternAction action = new WithValue(BigInteger.valueOf(7)); "
                        + (yieldRecord ? "WithValue result = " : "return ")
                        + "switch (action) { case WithValue p -> { " + update
                        + (yieldRecord ? " yield p; } }; return result.value();" : " yield p.value(); } };"));
                for (var level : OptimizationLevel.values()) {
                    for (boolean maps : List.of(false, true)) {
                        var error = assertThrows(CompilerException.class, () -> compile(source, level, maps));
                        assertTrue(error.getMessage().contains("Reassignment of switch case-pattern variable 'p' is not supported"), error.getMessage());
                        assertTrue(error.getMessage().contains("fresh local accumulator"), error.getMessage());
                        assertTrue(error.getMessage().matches("(?s).*Loops\\.java:\\d+:\\d+:.*"), error.getMessage());
                    }
                }
            }
        }
    }

    private static void assertSourceRejected(String source, String name) {
        for (var level : OptimizationLevel.values()) {
            var error = assertThrows(CompilerException.class, () -> compile(source, level), level + ": " + source);
            assertTrue(error.getMessage().contains("Conditional update to loop-body local '" + name + "'"), error.getMessage());
            assertTrue(error.getMessage().contains("conditional initializer"), error.getMessage());
            assertTrue(error.getMessage().contains("before the loop"), error.getMessage());
            assertTrue(error.getMessage().contains("declare it inside the branch"), error.getMessage());
            assertTrue(error.getMessage().matches("(?s).*Loops\\.java:\\d+:\\d+:.*"), error.getMessage());
            var mapped = assertThrows(CompilerException.class, () -> compile(source, level, true));
            assertEquals(error.getMessage(), mapped.getMessage());
        }
    }

    private record Supported(String name, String body, long positive, long mixed) {}

    private static final List<Supported> SUPPORTED = List.of(
            new Supported("branch local in non-breaking branch of break-aware loop", """
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        if (x.compareTo(BigInteger.ZERO) > 0) {
                            BigInteger step = BigInteger.ZERO;
                            step = step.add(x);
                            acc = acc.add(step);
                        }
                        if (x.equals(BigInteger.TWO)) { break; }
                    } return acc;
                    """, 3, 2),
            new Supported("switch arm owns instanceof pattern binding", """
                    PatternAction action = new WithValue(BigInteger.ZERO);
                    return switch (action) { case WithValue v -> {
                        if (action instanceof WithValue p) {
                            for (var y : xs) { p = new WithValue(y); }
                            yield p.value();
                        } else { yield BigInteger.ZERO; }
                    } };
                    """, 3, 2),
            new Supported("switch arm copies case pattern into a local accumulator", """
                    PatternAction action = new WithValue(BigInteger.ZERO);
                    return switch (action) { case WithValue p -> {
                        WithValue local = p;
                        for (var y : xs) { local = new WithValue(y); }
                        yield local.value();
                    } };
                    """, 3, 2),
            new Supported("switch arm owns and yields its accumulator", """
                    Action action = new Only();
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        BigInteger step = switch (action) { case Only o -> {
                            BigInteger local = BigInteger.ZERO;
                            for (var y : xs) { local = local.add(y); }
                            yield local;
                        } };
                        acc = acc.add(step);
                    } return acc;
                    """, 18, 3),
            new Supported("conditional initializer", """
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        BigInteger step = x.compareTo(BigInteger.ZERO) > 0 ? x : BigInteger.ONE;
                        acc = acc.add(step);
                    } return acc;
                    """, 6, 4),
            new Supported("outer accumulator reset each iteration", """
                    BigInteger acc = BigInteger.ZERO;
                    BigInteger step = BigInteger.ZERO;
                    for (var x : xs) {
                        step = BigInteger.ZERO;
                        if (x.compareTo(BigInteger.ZERO) > 0) { step = BigInteger.ONE; }
                        acc = acc.add(step);
                    } return acc;
                    """, 3, 1),
            new Supported("real accumulator conditional", """
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        if (x.compareTo(BigInteger.ONE) > 0) { acc = acc.add(x); }
                    } return acc;
                    """, 5, 2),
            new Supported("branch local and sibling declaration scopes", """
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        if (x.compareTo(BigInteger.ZERO) > 0) {
                            BigInteger step = BigInteger.ZERO;
                            { step = step.add(x); }
                            acc = acc.add(step);
                        } else {
                            BigInteger step = BigInteger.ONE;
                            step = step.add(BigInteger.ONE);
                            acc = acc.add(step);
                        }
                    } return acc;
                    """, 6, 6),
            new Supported("bare block local update", """
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        BigInteger step = BigInteger.ZERO;
                        { step = step.add(x); }
                        acc = acc.add(step);
                    } return acc;
                    """, 6, 1),
            new Supported("reuse name after lexical block", """
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        { BigInteger step = BigInteger.ONE; acc = acc.add(step); }
                        if (x.compareTo(BigInteger.ZERO) > 0) {
                            BigInteger step = BigInteger.ZERO;
                            step = x;
                            acc = acc.add(step);
                        }
                    } return acc;
                    """, 9, 5),
            new Supported("inner loop accumulator", """
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        BigInteger step = BigInteger.ZERO;
                        for (var y : xs) {
                            if (y.compareTo(BigInteger.ZERO) > 0) { step = step.add(y); }
                        }
                        acc = acc.add(step);
                    } return acc;
                    """, 18, 6),
            new Supported("while conditional initializer", """
                    BigInteger acc = BigInteger.ZERO;
                    BigInteger remaining = xs.size();
                    while (remaining.compareTo(BigInteger.ZERO) > 0) {
                        BigInteger step = remaining.compareTo(BigInteger.ONE) > 0 ? BigInteger.TWO : BigInteger.ONE;
                        acc = acc.add(step);
                        remaining = remaining.subtract(BigInteger.ONE);
                    } return acc;
                    """, 5, 5),
            new Supported("while inner accumulator", """
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        BigInteger step = BigInteger.ZERO;
                        while (step.compareTo(BigInteger.TWO) < 0) {
                            if (step.compareTo(BigInteger.TWO) < 0) { step = step.add(BigInteger.ONE); }
                        }
                        acc = acc.add(step);
                    } return acc;
                    """, 6, 6),
            new Supported("break retains accumulator", """
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        BigInteger step = x.compareTo(BigInteger.ZERO) > 0 ? x : BigInteger.ONE;
                        acc = acc.add(step);
                        if (x.equals(BigInteger.TWO)) { break; }
                    } return acc;
                    """, 3, 4));

    @Test
    void supportedCasesOnJava() {
        checkSupported("Java");
    }

    @Test
    @Tag("pair-case-backends")
    void supportedCasesOnOtherBackends() {
        checkSupported("Truffle");
        checkSupported("Scalus");
    }

    private static void checkSupported(String backend) {
        for (var fixture : SUPPORTED) {
            for (var level : OptimizationLevel.values()) {
                var source = method(fixture.body());
                var compiled = compile(source, level);
                assertArrayEquals(UplcFlatEncoder.encodeProgram(compiled.program()),
                        UplcFlatEncoder.encodeProgram(compile(source, level).program()));
                var inputs = List.of(PlutusData.list(PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)),
                        PlutusData.list(PlutusData.integer(-1), PlutusData.integer(0), PlutusData.integer(2)),
                        PlutusData.list());
                var expected = List.of(fixture.positive(), fixture.mixed(), 0L);
                for (int i = 0; i < inputs.size(); i++) {
                    var label = fixture.name() + "/" + level + "/" + backend + "/" + i;
                    var vm = CompilerTestVm.pv11(backend);
                    // Scalus' protocol-aware path is not certified; match the existing differential suites.
                    var evaluated = backend.equals("Scalus")
                            ? vm.evaluateWithArgs(compiled.program(), List.of(inputs.get(i)))
                            : vm.evaluateWithArgs(compiled.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                                    List.of(inputs.get(i)), null, EvalOptions.DEFAULT);
                    var success = assertInstanceOf(EvalResult.Success.class, evaluated, label + ": " + evaluated);
                    assertEquals(Term.const_(Constant.integer(expected.get(i))), success.resultTerm(), label);
                }
            }
        }
    }
}
