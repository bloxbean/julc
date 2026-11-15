package org.julclang.compiler.uplc;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.Term;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UPLC optimizer on terms far deeper than a recursive walk survives, each run on a thread with a 256 KB stack
 * (issue #327, ADR-074). A rewrite fires at the bottom of every term, so the walk reaches it, and the result is compared
 * with the term expected. Comparisons run off the small stack.
 */
class UplcOptimizerDepthTest {

    static final int DEPTH = 50_000;
    static final long STACK = 256 * 1024;

    static <T> T onSmallStack(Supplier<T> work) throws InterruptedException {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", STACK);
        thread.start();
        thread.join();
        if (failure.get() instanceof StackOverflowError overflow) throw new AssertionError("stack overflow", overflow);
        if (failure.get() instanceof RuntimeException e) throw e;
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    static Term integer(long value) {
        return Term.const_(Constant.integer(BigInteger.valueOf(value)));
    }

    static Term lams(int depth, Term body) {
        for (int i = 0; i < depth; i++) body = Term.lam("x" + i, body);
        return body;
    }

    // ---- UPLC optimizer ----

    @Test
    void forceOfDelayUnderDeepLambdasIsCancelled() throws InterruptedException {
        var term = lams(DEPTH, Term.force(Term.delay(integer(1))));
        var optimizer = new UplcOptimizer();
        assertEquals(lams(DEPTH, integer(1)), onSmallStack(() -> optimizer.optimize(term)));
        assertEquals(lams(DEPTH, integer(1)), onSmallStack(() -> optimizer.forceDelayCancel(term)));
    }

    @Test
    void aDeepChainOfUnusedBindingsIsRemoved() throws InterruptedException {
        // (\x. (\x. ... 7) 1) 0: each binding is unused and its argument pure, so dead-code elimination removes them all.
        Term term = integer(7);
        for (int i = 0; i < DEPTH; i++) term = Term.apply(Term.lam("x" + i, term), integer(i));
        Term chain = term;
        assertEquals(integer(7), onSmallStack(() -> new UplcOptimizer().optimize(chain)));
    }

    @Test
    void aDeepArgumentSpineIsRewrittenAtItsBottom() throws InterruptedException {
        Term term = Term.force(Term.delay(integer(0)));
        Term expected = integer(0);
        for (int i = 0; i < DEPTH; i++) {
            term = Term.apply(Term.builtin(DefaultFun.IData), term);
            expected = Term.apply(Term.builtin(DefaultFun.IData), expected);
        }
        Term spine = term;
        assertEquals(expected, onSmallStack(() -> new UplcOptimizer().optimize(spine)));
    }

    @Test
    void aCaseOfADeepConstrValueIsReduced() throws InterruptedException {
        Term fields = Term.force(Term.delay(integer(5)));
        Term expectedFields = integer(5);
        for (int i = 0; i < DEPTH; i++) {
            fields = Term.constr(0, fields);
            expectedFields = Term.constr(0, expectedFields);
        }
        // case (constr 0 [deep]) [\x. x]: the field is a value (a check over every nested field), so the case reduces to
        // the branch applied to it; x is used, and the field is not simple enough to substitute.
        Term term = Term.case_(Term.constr(0, fields), Term.lam("x", Term.var(1)));
        Term expected = Term.apply(Term.lam("x", Term.var(1)), expectedFields);
        assertEquals(expected, onSmallStack(() -> new UplcOptimizer().optimize(term)));
    }

    @Test
    void betaReductionSubstitutesUnderDeepLambdas() throws InterruptedException {
        // (\y. \x0 ... \x(n-1). y) 9 -> \x0 ... \x(n-1). 9, substituting through every lambda.
        Term term = Term.apply(Term.lam("y", lams(DEPTH, Term.var(DEPTH + 1))), integer(9));
        assertEquals(lams(DEPTH, integer(9)), onSmallStack(() -> new UplcOptimizer().optimize(term)));
    }

    @Test
    void aDeepPureConditionalIsDiscardedWhenUnused() throws InterruptedException {
        // An else-if chain of lazy conditionals is pure when every condition is a boolean and every branch pure.
        Term chain = integer(0);
        int depth = DEPTH / 5;
        for (int i = 0; i < depth; i++) chain = lazyIf(Term.const_(Constant.bool(i % 2 == 0)), integer(i), chain);
        Term conditional = chain;
        Term unused = Term.apply(Term.lam("x", integer(1)), conditional);
        assertEquals(integer(1), onSmallStack(() -> new UplcOptimizer().optimize(unused)));
        assertTrue(onSmallStack(() -> pure(conditional)));
    }

    /** {@code force ((force ifThenElse) cond (delay a) (delay b))}. */
    static Term lazyIf(Term cond, Term a, Term b) {
        return Term.force(Term.apply(Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.IfThenElse)), cond),
                Term.delay(a)), Term.delay(b)));
    }

    static boolean pure(Term term) {
        return UplcOptimizer.isPure(term);
    }

    @Test
    void deepAnalysesAnswerAsTheyDidOnShallowTerms() throws InterruptedException {
        Term deep = lams(DEPTH, Term.apply(Term.var(DEPTH + 1), Term.var(DEPTH + 1)));
        Term twin = lams(DEPTH, Term.apply(Term.var(DEPTH + 1), Term.var(DEPTH + 1)));
        assertTrue(onSmallStack(() -> UplcOptimizer.isFree(1, deep)));
        assertFalse(onSmallStack(() -> UplcOptimizer.isFree(2, deep)));
        assertEquals(2, onSmallStack(() -> UplcOptimizer.countUses(1, deep)));
        assertTrue(onSmallStack(() -> UplcOptimizer.alphaEquals(deep, twin)));
        assertTrue(onSmallStack(() -> deep.equals(twin)));
        Term other = lams(DEPTH, Term.apply(Term.var(DEPTH + 1), Term.var(DEPTH)));
        assertFalse(onSmallStack(() -> UplcOptimizer.alphaEquals(deep, other)));
        Term deepConstr = constrs(DEPTH);
        assertTrue(onSmallStack(() -> UplcOptimizer.isValue(deepConstr)));
    }

    static Term constrs(int depth) {
        Term term = integer(0);
        for (int i = 0; i < depth; i++) term = Term.constr(0, term);
        return term;
    }

}
