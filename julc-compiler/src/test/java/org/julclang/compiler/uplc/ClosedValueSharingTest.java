package org.julclang.compiler.uplc;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-076 D2 (issue #325): sharing a repeated closed value is a beta-expansion. Substituting each root binding back into
 * the shared term gives the original term exactly, on random terms and on compiled programs.
 */
class ClosedValueSharingTest {

    private static final DefaultFun[] POLYMORPHIC = {DefaultFun.HeadList, DefaultFun.TailList, DefaultFun.NullList,
            DefaultFun.MkCons, DefaultFun.FstPair, DefaultFun.SndPair, DefaultFun.IfThenElse, DefaultFun.ChooseList};

    private static Term forced(DefaultFun fun) {
        Term term = Term.builtin(fun);
        int forces = switch (fun) {
            case FstPair, SndPair, ChooseList -> 2;
            default -> 1;
        };
        for (int i = 0; i < forces; i++) term = Term.force(term);
        return term;
    }

    /**
     * A random closed term that uses forced builtins and Z combinators at varying lambda depths, with byte-aligned
     * constants (strings and byte strings, which FLAT pads to a byte boundary) among its integers.
     */
    private static Term random(Random random, int budget, int depth) {
        int choice = random.nextInt(budget <= 1 ? 4 : 10);
        return switch (choice) {
            case 0 -> forced(POLYMORPHIC[random.nextInt(POLYMORPHIC.length)]);
            case 1 -> depth > 0 ? Term.var(1 + random.nextInt(depth)) : Term.const_(Constant.integer(BigInteger.ONE));
            case 2 -> Term.const_(switch (random.nextInt(3)) {
                case 0 -> Constant.integer(BigInteger.valueOf(random.nextInt(9)));
                case 1 -> Constant.string("s".repeat(random.nextInt(4)));
                default -> {
                    var bytes = new byte[random.nextInt(5)];
                    random.nextBytes(bytes);
                    yield Constant.byteString(bytes);
                }
            });
            case 3 -> random.nextInt(6) == 0 ? ClosedValueSharing.Z : Term.builtin(DefaultFun.AddInteger);
            case 4, 5 -> Term.lam("x" + depth, random(random, budget - 1, depth + 1));
            case 6, 7 -> Term.apply(random(random, budget / 2, depth), random(random, budget / 2, depth));
            case 8 -> Term.delay(random(random, budget - 1, depth));
            default -> new Term.Case(random(random, budget / 3, depth),
                    List.of(random(random, budget / 3, depth), random(random, budget / 3, depth)));
        };
    }

    @Test
    void sharingIsABetaExpansionOnRandomTerms() {
        var random = new Random(325);
        int shared = 0;
        for (int trial = 0; trial < 3_000; trial++) {
            var term = random(random, 10 + random.nextInt(120), 0);
            var result = ClosedValueSharing.share(term);
            if (result != term) shared++;
            assertEquals(term, SharedClosedValues.unshare(result), "trial " + trial);
            assertEquals(result, ClosedValueSharing.share(term), "deterministic, trial " + trial);
            // Well formed, and kept only where its encoding is strictly smaller.
            int before = UplcFlatEncoder.encodeProgram(Program.plutusV3(term)).length;
            int after = UplcFlatEncoder.encodeProgram(Program.plutusV3(result)).length;
            if (result != term) assertTrue(after < before, "trial " + trial + ": " + before + " -> " + after);
        }
        assertTrue(shared > 30, "sharing fired on " + shared + " terms");
    }

    @Test
    void aUseUnderTooManyLambdasIsNotWorthAVariable() {
        // 20,000 lambdas deep, a variable needs three 7-bit groups: 28 bits against a forced builtin's 15.
        Term term = Term.apply(forced(DefaultFun.HeadList), Term.apply(forced(DefaultFun.HeadList), Term.var(1)));
        for (int i = 0; i < 20_000; i++) term = Term.lam("x", term);
        assertSame(term, ClosedValueSharing.share(term));
    }

    @Test
    void aValueIsSharedOnlyWhereItSavesBytes() {
        // One use: nothing to share.
        var once = Term.lam("x", Term.apply(forced(DefaultFun.HeadList), Term.var(1)));
        assertSame(once, ClosedValueSharing.share(once));
        // Many uses: the forced builtin is bound once and the program shrinks.
        Term many = Term.var(1);
        for (int i = 0; i < 40; i++) many = Term.apply(forced(DefaultFun.TailList), many);
        many = Term.lam("x", many);
        var shared = ClosedValueSharing.share(many);
        assertTrue(UplcFlatEncoder.encodeProgram(Program.plutusV3(shared)).length
                < UplcFlatEncoder.encodeProgram(Program.plutusV3(many)).length);
        assertEquals(many, SharedClosedValues.unshare(shared));
    }

    @Test
    void aBuiltinForcedMoreThanItsArityIsNotShared() {
        // force (force headList) fails when it runs; binding it at the root would fail every run. Only its inner
        // force headList, a value, may be shared: the outer force still runs at each use, where it always failed.
        Term overForced = Term.force(Term.force(Term.builtin(DefaultFun.HeadList)));
        Term term = Term.var(1);
        for (int i = 0; i < 40; i++) term = Term.apply(overForced, term);
        term = Term.lam("x", term);
        var result = ClosedValueSharing.share(term);
        assertEquals(term, SharedClosedValues.unshare(result));
        for (Term root = result; root instanceof Term.Apply(Term.Lam(var name, var body), var value); root = body) {
            assertTrue(!value.equals(overForced), "the over-forced builtin is never a root binding");
        }
    }

    @Test
    void aDeepTermIsSharedOnASmallStack() throws Exception {
        // A 50,000-deep application spine: deep, but every use is under one lambda, so a variable is short.
        Term term = Term.var(1);
        for (int i = 0; i < 50_000; i++) term = Term.apply(forced(DefaultFun.TailList), term);
        term = Term.lam("x", term);
        final Term deep = term;
        var result = new AtomicReference<Term>();
        var thread = new Thread(null, () -> result.set(ClosedValueSharing.share(deep)), "small", 256 * 1024);
        thread.start();
        thread.join();
        assertTrue(result.get() != null && result.get() != deep);
        assertEquals(deep, SharedClosedValues.unshare(result.get()));
    }

    @Test
    void aCompiledProgramWithTheRuleIsTheProgramWithoutItAfterSubstitution() throws Exception {
        String source = new String(ClosedValueSharingTest.class.getResourceAsStream("/size/PlainCounter.java.txt")
                .readAllBytes(), StandardCharsets.UTF_8);
        for (var level : List.of(OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED)) {
            var on = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level))
                    .compile(source);
            var off = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level)
                    .disableOptimizationRule(UplcOptimizer.SHARED_CLOSED_VALUES_RULE)).compile(source);
            assertTrue(on.optimizationReport().appliedRules().contains(UplcOptimizer.SHARED_CLOSED_VALUES_RULE));
            assertEquals(off.program().term(), SharedClosedValues.unshare(on.program().term()), level.name());
            assertTrue(UplcFlatEncoder.encodeProgram(on.program()).length < UplcFlatEncoder.encodeProgram(off.program()).length);
        }
        // Below PV11_SAFE the rule does not run.
        var baseline = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(OptimizationLevel.BASELINE)).compile(source);
        assertTrue(!baseline.optimizationReport().appliedRules().contains(UplcOptimizer.SHARED_CLOSED_VALUES_RULE));
    }

    @Test
    void anOpenTermIsNotShared() {
        // The optimizer's public API takes open terms; a root binder would capture the free Var(1).
        Term open = Term.var(1);
        for (int i = 0; i < 8; i++) open = Term.apply(forced(DefaultFun.TailList), open);
        assertSame(open, ClosedValueSharing.share(open));
    }

    @Test
    void aLongForceChainIsRejectedOnASmallStack() throws Exception {
        Term chain = Term.builtin(DefaultFun.HeadList);
        for (int i = 0; i < 50_000; i++) chain = Term.force(chain);
        final Term term = Term.lam("x", Term.delay(chain));
        var result = new AtomicReference<Term>();
        var thread = new Thread(null, () -> result.set(ClosedValueSharing.share(term)), "small", 256 * 1024);
        thread.start();
        thread.join();
        assertSame(term, result.get());
    }

    @Test
    void sharingThatWouldGrowTheEncodingIsNotKept() {
        // Each trace's string is byte-aligned, so bits saved before it can become padding.
        Term term = Term.var(1);
        for (int i = 0; i < 8; i++) {
            term = Term.apply(Term.apply(forced(DefaultFun.Trace), Term.const_(Constant.string("a"))), Term.delay(term));
        }
        term = Term.lam("x", term);
        var result = ClosedValueSharing.share(term);
        assertTrue(UplcFlatEncoder.encodeProgram(Program.plutusV3(result)).length
                <= UplcFlatEncoder.encodeProgram(Program.plutusV3(term)).length);
    }
}
