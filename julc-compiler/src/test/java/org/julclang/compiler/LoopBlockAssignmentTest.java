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
