package org.julclang.compiler.uplc;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #327: {@link UplcGenerator} and {@link BooleanProof} lower a term of any depth on a small stack. Each spine is
 * {@value #DEPTH} levels deep through one child position, lowered on a 256 KB stack (where the recursive lowering
 * overflowed after a few thousand levels), and its UPLC is checked against a term built by hand or by evaluation.
 */
class DeepUplcGenerationTest {

    static final int DEPTH = 50_000;
    static final PirType INT = new PirType.IntegerType();
    static final JulcVm VM = CompilerTestVm.pv11();

    static <T> T onStack(long bytes, Supplier<T> work) {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "depth-test", bytes);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        if (failure.get() instanceof RuntimeException e) throw e;
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    static <T> T onSmallStack(Supplier<T> work) {
        return onStack(256 * 1024, work);
    }

    static Term lower(PirTerm pir, CompilationContext context) {
        return onSmallStack(() -> new UplcGenerator(context, null).generate(pir));
    }

    static Term lower(PirTerm pir) {
        return lower(pir, CompilationContext.pv11Defaults());
    }

    static CompilationContext baseline() {
        return CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE));
    }

    /** Evaluates on a large stack: the VM is not what these tests measure. */
    static EvalResult.Success evaluate(Term term) {
        var result = onStack(512L * 1024 * 1024, () -> VM.evaluate(Program.plutusV3(term)));
        return assertInstanceOf(EvalResult.Success.class, result, () -> "evaluation failed: " + result);
    }

    static BigInteger integerResult(Term term) {
        var value = ((Term.Const) evaluate(term).resultTerm()).value();
        return ((Constant.IntegerConst) value).value();
    }

    static PirTerm integer(long value) {
        return new PirTerm.Const(Constant.integer(value));
    }

    static PirTerm add(PirTerm a, PirTerm b) {
        return PirHelpers.builtinApp2(DefaultFun.AddInteger, a, b);
    }

    static PirTerm var(String name) {
        return new PirTerm.Var(name, INT);
    }

    @Test
    void aLetChainLowersToNestedLambdasWithTheRightIndices() {
        PirTerm pir = add(var("x0"), var("x" + (DEPTH - 1)));
        for (int i = DEPTH - 1; i >= 0; i--) pir = new PirTerm.Let("x" + i, integer(i), pir);

        Term expected = Term.apply(Term.apply(Term.builtin(DefaultFun.AddInteger), Term.var(DEPTH)), Term.var(1));
        for (int i = DEPTH - 1; i >= 0; i--) expected = Term.apply(Term.lam("x" + i, expected), Term.const_(Constant.integer(i)));

        var lowered = lower(pir);
        assertEquals(expected, lowered);
        assertEquals(BigInteger.valueOf(DEPTH - 1), integerResult(lowered));
    }

    /** {@code let k = DEPTH - 3 in if k == 0 then 0 else if k == 1 then 10 else ... else error}. */
    static PirTerm elseChain() {
        PirTerm chain = new PirTerm.Error(INT);
        for (int i = DEPTH - 1; i >= 0; i--) {
            var test = PirHelpers.builtinApp2(DefaultFun.EqualsInteger, var("k"), integer(i));
            chain = new PirTerm.IfThenElse(test, integer(i * 10L), chain);
        }
        return new PirTerm.Let("k", integer(DEPTH - 3), chain);
    }

    @Test
    void anElseChainLowersToCaseWhenTheConditionIsProvenAndToTheBuiltinOtherwise() {
        var proven = lower(elseChain());
        var inner = ((Term.Apply) proven).function();
        assertInstanceOf(Term.Case.class, ((Term.Lam) inner).body(), "PV11 safe: a proven condition is a Case");
        assertEquals(BigInteger.valueOf((DEPTH - 3) * 10L), integerResult(proven));

        var builtin = lower(elseChain(), baseline());
        var innerBuiltin = ((Term.Apply) builtin).function();
        assertInstanceOf(Term.Force.class, ((Term.Lam) innerBuiltin).body(), "BASELINE: the builtin IfThenElse");
        assertEquals(BigInteger.valueOf((DEPTH - 3) * 10L), integerResult(builtin));
    }

    @Test
    void anArgumentSpineLowers() {
        PirTerm pir = integer(0);
        for (int i = 0; i < DEPTH; i++) pir = add(integer(1), pir);
        assertEquals(BigInteger.valueOf(DEPTH), integerResult(lower(pir)));
    }

    @Test
    void aLambdaSpineAppliedThroughAFunctionSpineLowers() {
        PirTerm body = add(var("x0"), var("x" + (DEPTH - 1)));
        for (int i = DEPTH - 1; i >= 0; i--) body = new PirTerm.Lam("x" + i, INT, body);
        PirTerm call = body;
        for (int i = 0; i < DEPTH; i++) call = new PirTerm.App(call, integer(i + 1000));
        assertEquals(BigInteger.valueOf(1000 + 1000 + DEPTH - 1), integerResult(lower(call)));
    }

    @Test
    void aTraceSpineLowersAndTracesEveryMessage() {
        int depth = DEPTH / 10;
        PirTerm pir = integer(7);
        for (int i = depth - 1; i >= 0; i--) pir = new PirTerm.Trace(new PirTerm.Const(Constant.string("t" + i)), pir);
        var result = evaluate(lower(pir));
        assertEquals(Constant.integer(7), ((Term.Const) result.resultTerm()).value());
        assertEquals(depth, result.traces().size());
        // Trace is a strict builtin: its body runs, and traces, before it does, so the innermost message is first.
        assertEquals("t" + (depth - 1), result.traces().getFirst());
        assertEquals("t0", result.traces().getLast());
    }

    @Test
    void aChainOfRecursiveBindingsLowers() {
        PirTerm pir = add(new PirTerm.App(var("f0"), integer(5)), new PirTerm.App(var("f" + (DEPTH - 1)), integer(6)));
        for (int i = DEPTH - 1; i >= 0; i--) {
            var function = new PirTerm.Lam("n" + i, INT, var("n" + i));
            pir = new PirTerm.LetRec(List.of(new PirTerm.Binding("f" + i, function)), pir);
        }
        assertEquals(BigInteger.valueOf(11), integerResult(lower(pir)));
    }

    @Test
    void aLongDependencyChainInOneRecursiveGroupLowers() {
        int size = 5_000;
        var bindings = new ArrayList<PirTerm.Binding>();
        for (int i = 0; i < size; i++) {
            PirTerm body = i == size - 1 ? var("n" + i) : new PirTerm.App(var("b" + (i + 1)), var("n" + i));
            bindings.add(new PirTerm.Binding("b" + i, new PirTerm.Lam("n" + i, INT, body)));
        }
        var pir = new PirTerm.LetRec(bindings, new PirTerm.App(var("b0"), integer(42)));
        assertEquals(BigInteger.valueOf(42), integerResult(lower(pir)));
    }

    @Test
    void aWideConstructorLowersItsFieldsInOrder() {
        int width = 20_000;
        var fields = new ArrayList<PirTerm>();
        var expected = new ArrayList<PlutusData>();
        for (int i = 0; i < width; i++) {
            fields.add(new PirTerm.Const(Constant.data(PlutusData.integer(i))));
            expected.add(PlutusData.integer(i));
        }
        var pir = new PirTerm.DataConstr(3, new PirType.DataType(), fields);
        var value = ((Term.Const) evaluate(lower(pir)).resultTerm()).value();
        assertEquals(Constant.data(PlutusData.constr(3, expected.toArray(PlutusData[]::new))), value);
    }

    @Test
    void aPairMatchSpineLowersOnPv11() {
        var pairType = new PirType.PairType(INT, new PirType.ListType(new PirType.DataType()));
        var data = new PirTerm.Const(Constant.data(PlutusData.constr(9)));
        PirTerm pir = var("t0");
        for (int i = DEPTH - 1; i >= 0; i--) {
            pir = new PirTerm.PairMatch(new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), data), pairType,
                    "t" + i, "f" + i, pir);
        }
        assertEquals(BigInteger.valueOf(9), integerResult(lower(pir)));
    }

    @Test
    void aListMatchSpineLowersOnPv11() {
        var list = new PirTerm.Const(new Constant.ListConst(DefaultUni.DATA, List.of(Constant.data(PlutusData.integer(4)))));
        PirTerm pir = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), new PirTerm.Var("h0", new PirType.DataType()));
        for (int i = DEPTH - 1; i >= 0; i--) {
            pir = new PirTerm.ListMatch(list, "h" + i, "r" + i, integer(-1), pir);
        }
        assertEquals(BigInteger.valueOf(4), integerResult(lower(pir)));
    }

    @Test
    void aConditionBehindALongLetChainIsStillProvenBoolean() {
        PirTerm condition = PirHelpers.builtinApp2(DefaultFun.LessThanInteger, var("y0"), var("y" + (DEPTH - 1)));
        for (int i = DEPTH - 1; i >= 0; i--) condition = new PirTerm.Let("y" + i, integer(i), condition);
        var pir = new PirTerm.IfThenElse(condition, integer(1), integer(2));
        var lowered = lower(pir);
        assertInstanceOf(Term.Case.class, lowered);
        assertEquals(BigInteger.ONE, integerResult(lowered));
    }

    @Test
    void booleanProofReadsADeepLambdaSpine() {
        PirTerm term = PirHelpers.builtinApp2(DefaultFun.EqualsInteger, var("z0"), integer(0));
        for (int i = DEPTH - 1; i >= 0; i--) term = new PirTerm.Lam("z" + i, INT, term);
        var finalTerm = term;
        var fact = onSmallStack(() -> BooleanProof.of(finalTerm, BooleanProof.Scope.EMPTY));
        assertEquals(new BooleanProof.Yields(DEPTH), fact);

        var recursive = List.of(new PirTerm.Binding("g", finalTerm));
        var scope = onSmallStack(() -> BooleanProof.bindRecursive(recursive, BooleanProof.Scope.EMPTY));
        assertEquals(new BooleanProof.Yields(DEPTH), scope.lookup("g"));
    }

    @Test
    void sourcePositionsAreRecordedAndInheritedAtDepth() {
        var positions = new IdentityHashMap<PirTerm, SourceLocation>();
        var values = new ArrayList<PirTerm>();
        PirTerm pir = var("x0");
        var lets = new ArrayList<PirTerm>();
        for (int i = DEPTH - 1; i >= 0; i--) {
            var value = integer(i);
            values.addFirst(value);
            pir = new PirTerm.Let("x" + i, value, pir);
            lets.addFirst(pir);
            positions.put(pir, new SourceLocation("Deep.java", i + 1, 1, null));
        }
        var root = pir;
        var generator = new UplcGenerator(CompilationContext.pv11Defaults(), positions);
        var lowered = onSmallStack(() -> generator.generate(root));
        var uplcPositions = generator.getUplcPositions();
        var exact = generator.getExactUplcPositions();
        assertEquals(1, exact.get(lowered).line());
        // Each let's value has no position of its own, and inherits the let's.
        Term term = lowered;
        for (int i = 0; i < DEPTH; i++) {
            var apply = (Term.Apply) term;
            assertEquals(i + 1, exact.get(apply).line());
            assertEquals(i + 1, uplcPositions.get(apply.argument()).line());
            term = ((Term.Lam) apply.function()).body();
        }
        assertEquals(DEPTH, uplcPositions.get(term).line(), "the body inherits the innermost let's position");
    }

    @Test
    void aFailedLoweringLeavesTheGeneratorReady() {
        PirTerm broken = var("unbound");
        for (int i = DEPTH - 1; i >= 0; i--) broken = new PirTerm.Lam("x" + i, INT, broken);
        var generator = new UplcGenerator();
        var brokenTerm = broken;
        var failure = assertThrows(CompilerException.class, () -> onSmallStack(() -> generator.generate(brokenTerm)));
        assertTrue(failure.getMessage().contains("Unbound variable: unbound"), failure.getMessage());

        var closed = new PirTerm.Lam("a", INT, var("a"));
        assertEquals(Term.lam("a", Term.var(1)), generator.generate(closed));
    }
}
