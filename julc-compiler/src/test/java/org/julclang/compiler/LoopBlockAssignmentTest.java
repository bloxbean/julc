package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Assignments inside loop bodies (PR #150 review round three). A bare nested block is part
 * of the loop body: its accumulator updates are bound like those outside it, in single- and
 * multi-accumulator loops, in break-aware loops and inside an if branch. The block used to be
 * delegated to the generic statement generator, which lowered the assignment to its
 * right-hand side and dropped the update: {@code for (x : xs) { { acc = acc.add(x); } }}
 * summed to zero. An assignment the loop body generators do not bind (in expression position,
 * to an undeclared variable, or inside a statement delegated to the generic generator) is
 * rejected rather than dropped.
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
        // A block that both declares a variable and breaks is rejected; one that only breaks is fine (above).
        var declaresAndBreaks = assertThrows(CompilerException.class, () -> compile("""
                static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger acc = BigInteger.ZERO;
                    for (var x : xs) {
                        if (x.equals(BigInteger.TWO)) {
                            {
                                BigInteger t = x;
                                acc = acc.add(t);
                                break;
                            }
                        }
                        acc = acc.add(x);
                    }
                    return acc;
                }
                """));
        assertTrue(declaresAndBreaks.getMessage().contains("declares a variable and contains break"), declaresAndBreaks.getMessage());
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
