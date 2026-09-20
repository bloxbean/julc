package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Assignments and bare nested blocks inside loop bodies (PR #150 review, rounds three to
 * five). The only thing a bare block does is end the scope of its own declarations: its
 * statements run in sequence with the rest of the body, every update it makes to an enclosing
 * variable (an accumulator, a loop-body local) survives it, a {@code break} inside it leaves
 * the loop, and a name it shadows means the outer binding again after it. The block is
 * lowered as its statements spliced into the body with its own declarations renamed apart,
 * which is the lowering of the braceless body byte for byte; that equality is asserted here
 * at every level. The three earlier lowerings each miscompiled a valid program: delegating
 * the block dropped its accumulator updates ({@code { acc = acc.add(x); }} summed to zero),
 * splicing without renaming let a block local shadow a class constant after the block, and
 * lowering the block as a value carried only the accumulator out of it. An assignment the
 * loop body generators do not bind (in expression position, to an undeclared variable, or
 * inside a statement delegated to the generic generator) is rejected rather than dropped.
 */
class LoopBlockAssignmentTest {

    private static final String HEADER = """
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.lib.ContextsLib;
            import java.math.BigInteger;
            class Loops {
                static final BigInteger amount = BigInteger.TEN;
            """;

    private static final List<PlutusData> ONE_TWO_THREE = List.of(PlutusData.list(
            PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3)));

    @Test
    void nestedBlockUpdatesASingleAccumulator() {
        assertEquals(6, evaluate("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        { acc = acc.add(x); }
                    }
                    return acc;
                }
                """, ONE_TWO_THREE));
        assertEquals(12, evaluate("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        {
                            acc = acc.add(x);
                            acc = acc.add(x);
                        }
                    }
                    return acc;
                }
                """, ONE_TWO_THREE));
        assertEquals(18, evaluate("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        {
                            BigInteger twice = x.add(x);
                            acc = acc.add(twice);
                        }
                        acc = acc.add(x);
                    }
                    return acc;
                }
                """, ONE_TWO_THREE));
    }

    @Test
    void nestedBlockUpdatesSeveralAccumulatorsAndWorksWithBreakAndIf() {
        assertEquals(6 * 10 + 3, evaluate("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger sum = BigInteger.ZERO;
                    BigInteger count = BigInteger.ZERO;
                    for (var x : xs) {
                        { sum = sum.add(x); count = count.add(BigInteger.ONE); }
                    }
                    return sum.multiply(BigInteger.TEN).add(count);
                }
                """, ONE_TWO_THREE));
        assertEquals(3, evaluate("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        if (x.equals(BigInteger.TWO)) {
                            { acc = acc.add(x); break; }
                        }
                        { acc = acc.add(x); }
                    }
                    return acc;
                }
                """, ONE_TWO_THREE));
        assertEquals(3 * 10 + 2, evaluate("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger sum = BigInteger.ZERO;
                    BigInteger count = BigInteger.ZERO;
                    for (var x : xs) {
                        if (x.equals(BigInteger.TWO)) {
                            { sum = sum.add(x); count = count.add(BigInteger.ONE); break; }
                        }
                        { sum = sum.add(x); count = count.add(BigInteger.ONE); }
                    }
                    return sum.multiply(BigInteger.TEN).add(count);
                }
                """, ONE_TWO_THREE));
    }

    /**
     * A block's declarations end with the block (PR #150 review round four: splicing the
     * statements let a block-local {@code amount} shadow the class constant {@code amount} in
     * the statements after the block, so a valid program summed 1 instead of 10 per element).
     */
    @Test
    void blockLocalsEndWithTheBlock() {
        // The reviewer's reproducer: the class constant, not the block local, after the block.
        assertEquals(30, evaluate("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        {
                            BigInteger amount = BigInteger.ONE;
                            ContextsLib.trace("inner");
                        }
                        acc = acc.add(amount);
                    }
                    return acc;
                }
                """, ONE_TWO_THREE));
        // The same with several accumulators, and in a break-aware loop (the block itself does not break).
        assertEquals(30 * 10 + 3, evaluate("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger sum = BigInteger.ZERO;
                    BigInteger count = BigInteger.ZERO;
                    for (var x : xs) {
                        {
                            BigInteger amount = BigInteger.ONE;
                            sum = sum.add(amount);
                            count = count.add(amount);
                        }
                        sum = sum.add(amount).subtract(BigInteger.ONE);
                    }
                    return sum.multiply(BigInteger.TEN).add(count);
                }
                """, ONE_TWO_THREE));
        assertEquals(20, evaluate("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        {
                            BigInteger amount = BigInteger.ONE;
                            ContextsLib.trace("inner");
                        }
                        acc = acc.add(amount);
                        if (x.equals(BigInteger.TWO)) {
                            break;
                        }
                    }
                    return acc;
                }
                """, ONE_TWO_THREE));
        // A block-local's value still reaches the accumulator, and the name is free again after the block.
        assertEquals(18, evaluate("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        {
                            BigInteger t = x;
                            acc = acc.add(t);
                        }
                        BigInteger t = x.add(x);
                        acc = acc.add(t);
                    }
                    return acc;
                }
                """, ONE_TWO_THREE));
        // A block-local referenced after its block is undefined, as javac says.
        var escaped = assertThrows(CompilerException.class, () -> compile("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        {
                            BigInteger t = x;
                        }
                        acc = acc.add(t);
                    }
                    return acc;
                }
                """));
        assertTrue(escaped.getMessage().contains("Undefined variable: t"), escaped.getMessage());
        // A block local that shadows the class constant and is itself reassigned inside the block.
        assertEquals(36, evaluate(loop("BigInteger acc = BigInteger.ZERO;", """
                {
                    BigInteger amount = BigInteger.ONE;
                    amount = amount.add(BigInteger.ONE);
                    acc = acc.add(amount);
                }
                acc = acc.add(amount);
                """, "acc"), ONE_TWO_THREE));
    }

    /**
     * Updates a block makes to an enclosing loop-body local survive the block (round five: the
     * value-yielding lowering carried only the accumulator out, so {@code step} stayed zero).
     */
    @Test
    void enclosingLocalsUpdatedInABlockKeepTheirUpdates() {
        assertEquals(3, evaluate(loop("BigInteger acc = BigInteger.ZERO;", """
                BigInteger step = BigInteger.ZERO;
                {
                    BigInteger delta = BigInteger.ONE;
                    step = step.add(delta);
                }
                acc = acc.add(step);
                """, "acc"), ONE_TWO_THREE));
        // Two enclosing locals, a nested declaring block, and a use of each after the blocks.
        assertEquals(6 + 12 + 18, evaluate(loop("BigInteger acc = BigInteger.ZERO;", """
                BigInteger once = BigInteger.ZERO;
                BigInteger twice = BigInteger.ZERO;
                {
                    BigInteger d = x;
                    once = once.add(d);
                    {
                        BigInteger e = d.add(d);
                        twice = twice.add(e);
                        once = once.add(e);
                    }
                }
                acc = acc.add(once).add(twice).add(x);
                """, "acc"), ONE_TWO_THREE));
        // A while loop over block locals inside the block, and a lambda that reads one.
        assertEquals(12, evaluate(loop("BigInteger acc = BigInteger.ZERO;", """
                {
                    BigInteger k = BigInteger.ZERO;
                    BigInteger i = BigInteger.ZERO;
                    while (i.compareTo(BigInteger.TWO) < 0) {
                        k = k.add(x);
                        i = i.add(BigInteger.ONE);
                    }
                    acc = acc.add(k);
                }
                """, "acc"), ONE_TWO_THREE));
        assertEquals(6, evaluate(loop("BigInteger acc = BigInteger.ZERO;", """
                {
                    BigInteger t = x;
                    boolean found = xs.any(y -> y.equals(t));
                    acc = found ? acc.add(t) : acc;
                }
                """, "acc"), ONE_TWO_THREE));
    }

    /**
     * A declaring block compiles to the bytes of its hand-flattened body (the block local
     * given a name of its own) at every level, and to the value that body computes.
     */
    @Test
    void aBlockLowersAsItsBracelessBody() {
        record Pair(String name, String prelude, String blockBody, String flatBody, String result, long expected) {}
        var pairs = List.of(
                new Pair("enclosing local updated", "BigInteger acc = BigInteger.ZERO;",
                        "BigInteger step = BigInteger.ZERO; { BigInteger delta = BigInteger.ONE; step = step.add(delta); } acc = acc.add(step);",
                        "BigInteger step = BigInteger.ZERO; BigInteger delta = BigInteger.ONE; step = step.add(delta); acc = acc.add(step);",
                        "acc", 3),
                new Pair("class constant shadowed", "BigInteger acc = BigInteger.ZERO;",
                        "{ BigInteger amount = BigInteger.ONE; acc = acc.add(amount); } acc = acc.add(amount);",
                        "BigInteger inner = BigInteger.ONE; acc = acc.add(inner); acc = acc.add(amount);",
                        "acc", 33),
                new Pair("several accumulators", "BigInteger sum = BigInteger.ZERO; BigInteger count = BigInteger.ZERO;",
                        "{ BigInteger d = x.add(x); sum = sum.add(d); } count = count.add(BigInteger.ONE);",
                        "BigInteger d = x.add(x); sum = sum.add(d); count = count.add(BigInteger.ONE);",
                        "sum.multiply(BigInteger.TEN).add(count)", 123),
                new Pair("break inside a declaring block", "BigInteger acc = BigInteger.ZERO;",
                        "if (x.equals(BigInteger.TWO)) { { BigInteger t = x.add(x); acc = acc.add(t); break; } } acc = acc.add(x);",
                        "if (x.equals(BigInteger.TWO)) { BigInteger t = x.add(x); acc = acc.add(t); break; } acc = acc.add(x);",
                        "acc", 5),
                new Pair("nested declaring blocks", "BigInteger acc = BigInteger.ZERO;",
                        "{ BigInteger a = x.add(x); { BigInteger b = a.add(a); acc = acc.add(b); } acc = acc.add(a); }",
                        "BigInteger a = x.add(x); BigInteger b = a.add(a); acc = acc.add(b); acc = acc.add(a);",
                        "acc", 36),
                new Pair("several accumulators and a break", "BigInteger sum = BigInteger.ZERO; BigInteger count = BigInteger.ZERO;",
                        "{ BigInteger d = x.add(x); sum = sum.add(d); } count = count.add(BigInteger.ONE); if (x.equals(BigInteger.TWO)) { break; }",
                        "BigInteger d = x.add(x); sum = sum.add(d); count = count.add(BigInteger.ONE); if (x.equals(BigInteger.TWO)) { break; }",
                        "sum.multiply(BigInteger.TEN).add(count)", 62));
        for (var pair : pairs) {
            var blockForm = loop(pair.prelude(), pair.blockBody(), pair.result());
            var flatForm = loop(pair.prelude(), pair.flatBody(), pair.result());
            assertEquals(pair.expected(), evaluate(blockForm, ONE_TWO_THREE), pair.name());
            assertEquals(pair.expected(), evaluate(flatForm, ONE_TWO_THREE), pair.name() + " (flattened)");
            for (var level : OptimizationLevel.values()) {
                assertArrayEquals(flat(flatForm, level, false), flat(blockForm, level, false), pair.name() + " at " + level);
            }
            flat(blockForm, OptimizationLevel.PV11_SAFE, true); // the renamed copy keeps its source positions
        }
    }

    @Test
    void unboundAssignmentsAreRejectedNotDropped() {
        var inExpression = assertThrows(CompilerException.class, () -> compile("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        acc = acc.add(x);
                        ContextsLib.trace(BigInteger.ZERO.equals(acc = acc.add(x)) ? "zero" : "other");
                    }
                    return acc;
                }
                """));
        assertTrue(inExpression.getMessage().contains("Assignment to 'acc' is not supported at this position"), inExpression.getMessage());
        // With no statement-level assignment the loop has no accumulator, and the generic diagnostic rejects it.
        var noAccumulator = assertThrows(CompilerException.class, () -> compile("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        ContextsLib.trace(BigInteger.ZERO.equals(acc = acc.add(x)) ? "zero" : "other");
                    }
                    return acc;
                }
                """));
        assertTrue(noAccumulator.getMessage().contains("Unsupported expression: AssignExpr"), noAccumulator.getMessage());
        var undeclared = assertThrows(CompilerException.class, () -> compile("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        other = x;
                        acc = acc.add(x);
                    }
                    return acc;
                }
                """));
        assertTrue(undeclared.getMessage().contains("Assignment to undeclared variable 'other'"), undeclared.getMessage());
        var undeclaredInBlock = assertThrows(CompilerException.class, () -> compile("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        { other = x; }
                        acc = acc.add(x);
                    }
                    return acc;
                }
                """));
        assertTrue(undeclaredInBlock.getMessage().contains("Assignment to undeclared variable 'other'"), undeclaredInBlock.getMessage());
        // Outside a loop the existing diagnostic stands.
        var outside = assertThrows(CompilerException.class, () -> compile("""
                static BigInteger m(BigInteger a) {
                    BigInteger b = a;
                    b = b.add(a);
                    return b;
                }
                """));
        assertTrue(outside.getMessage().contains("Unsupported expression: AssignExpr"), outside.getMessage());
    }

    private static String loop(String prelude, String loopBody, String result) {
        return "static BigInteger m(JulcList<BigInteger> xs) {\n" + prelude + "\nfor (var x : xs) {\n" + loopBody + "\n}\nreturn " + result + ";\n}\n";
    }

    private static byte[] flat(String body, OptimizationLevel level, boolean sourceMaps) {
        var options = new CompilerOptions().setOptimizationLevel(level).setSourceMapEnabled(sourceMaps)
                .setOptimizationCostProfile(OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1);
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry(), options).compileMethod(HEADER + body + "}\n", "m");
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        return UplcFlatEncoder.encodeProgram(result.program());
    }

    private static CompileResult compile(String body) {
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(HEADER + body + "}\n", "m");
        if (result.hasErrors()) throw new CompilerException(result.diagnostics().toString());
        return result;
    }

    private static long evaluate(String body, List<PlutusData> args) {
        var result = compile(body);
        var evaluated = CompilerTestVm.pv11("Java").evaluateWithArgs(result.program(), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), args, null, EvalOptions.DEFAULT);
        var success = assertInstanceOf(EvalResult.Success.class, evaluated, evaluated.toString());
        return ((Constant.IntegerConst) ((Term.Const) success.resultTerm()).value()).value().longValueExact();
    }
}
