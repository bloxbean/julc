package org.julclang.compiler;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.julclang.compiler.O9ListIndexFixtures.i;
import static org.julclang.compiler.O9ListIndexFixtures.tens;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-043 "Alternatives rejected": the measurements behind the {@code DropList}-based
 * {@code get} lowering that was recorded but not adopted. This is the reproducible harness for
 * those numbers: under the pinned PV11 profile at the safe level, {@code xs.drop(i).head()}
 * costs a small constant per index step where the recursive {@code get} costs a traversal
 * step, and the unguarded form is not a valid replacement because {@code DropList} treats a
 * negative count as zero.
 */
class O9DropListAlternativeTest {

    private static final String SOURCE = """
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            import java.math.BigInteger;
            class DropListForms {
                static BigInteger dropHead(JulcList<BigInteger> xs, BigInteger i) {
                    return xs.drop(i).head();
                }
                static BigInteger guardedDropHead(JulcList<BigInteger> xs, BigInteger i) {
                    if (i.compareTo(BigInteger.ZERO) < 0) {
                        return Builtins.unIData(Builtins.error());
                    }
                    return xs.drop(i).head();
                }
                static BigInteger recursiveGet(JulcList<BigInteger> xs, BigInteger i) {
                    return xs.get(i);
                }
            }
            """;

    @Test
    void dropListIndexingIsAffineWithASmallStepAndDiffersOnlyOnNegativeAndOverLengthIndexes() {
        var dropHead = compile("dropHead");
        var guarded = compile("guardedDropHead");
        var recursive = compile("recursiveGet");
        long dropStep = cpu(dropHead, 1) - cpu(dropHead, 0);
        long recursiveStep = cpu(recursive, 1) - cpu(recursive, 0);
        for (int index : List.of(2, 4, 7)) {
            assertEquals(cpu(dropHead, 0) + index * dropStep, cpu(dropHead, index), "drop is affine in the index");
            assertEquals(cpu(guarded, 0) + index * dropStep, cpu(guarded, index), "guarded drop is affine in the index");
            assertEquals(cpu(recursive, 0) + index * recursiveStep, cpu(recursive, index), "recursive get is affine in the index");
        }
        System.out.println("O9_DROPLIST dropHead=" + cpu(dropHead, 0) + "+" + dropStep + "*i guarded=" + cpu(guarded, 0)
                + "+" + dropStep + "*i recursiveGet=" + cpu(recursive, 0) + "+" + recursiveStep + "*i bytes="
                + bytes(dropHead) + "/" + bytes(guarded) + "/" + bytes(recursive));
        assertTrue(recursiveStep > 100 * dropStep, recursiveStep + " vs " + dropStep);
        assertTrue(cpu(dropHead, 0) < cpu(recursive, 0), "the drop form is cheaper even at index 0");
        assertTrue(cpu(guarded, 1) < cpu(recursive, 1), "the guarded drop form is cheaper from index 1");
        assertTrue(bytes(dropHead) < bytes(recursive) && bytes(guarded) < bytes(recursive));

        // Same elements for every valid index.
        for (int index = 0; index < 8; index++) {
            assertEquals(result(recursive, index), result(dropHead, index));
            assertEquals(result(recursive, index), result(guarded, index));
        }
        // Index equal to the length: identical text in all three forms.
        assertEquals("HeadList: empty list", failure(recursive, 8));
        assertEquals("HeadList: empty list", failure(dropHead, 8));
        assertEquals("HeadList: empty list", failure(guarded, 8));
        // Over the length: the traversal exhausts the tail, the drop forms exhaust the head.
        assertEquals("TailList: empty list", failure(recursive, 9));
        assertEquals("HeadList: empty list", failure(dropHead, 9));
        assertEquals("HeadList: empty list", failure(guarded, 9));
        // Negative: the unguarded form silently returns element 0, which is why it is not a
        // valid replacement; the guard restores a failure with its own text.
        assertEquals("TailList: empty list", failure(recursive, -1));
        assertEquals(result(recursive, 0), result(dropHead, -1));
        assertEquals("Error term encountered", failure(guarded, -1));
    }

    private static Program compile(String method) {
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()
                .setOptimizationLevel(OptimizationLevel.PV11_SAFE)
                .setOptimizationCostProfile(OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11))
                .compileMethod(SOURCE, method);
        assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
        return compiled.program();
    }

    private static EvalResult evaluate(Program program, long index) {
        return CompilerTestVm.pv11("Java").evaluateWithArgs(program, CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(),
                List.of(tens(8), i(index)), null, EvalOptions.DEFAULT);
    }

    private static long cpu(Program program, long index) {
        return evaluate(program, index).budgetConsumed().cpuSteps();
    }

    private static String result(Program program, long index) {
        return assertInstanceOf(EvalResult.Success.class, evaluate(program, index)).resultTerm().toString();
    }

    private static String failure(Program program, long index) {
        return assertInstanceOf(EvalResult.Failure.class, evaluate(program, index)).error();
    }

    private static int bytes(Program program) {
        return UplcFlatEncoder.encodeProgram(program).length;
    }
}
