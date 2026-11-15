package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value-sharing, list-promotion and delayed-method passes on terms far deeper than a recursive walk could take on a
 * small stack (issue #327, ADR-074): each runs on a 256 KB thread, through each kind of spine a long program builds, and
 * rewrites (or keeps) the site at the bottom of it exactly as it would at the top.
 */
class DeepTermPassesTest {

    private static final int DEEP = 50_000;
    /**
     * For walks that do work per level proportional to the rest of the term (list promotion counts uses to place its
     * binding; the delayed-method reading copies its scope at each let; a rewrite at the bottom of a spine makes each
     * level above it compare its old and new term, down to the rewrite): quadratic, so shorter, but still far deeper
     * than a recursive walk reaches on a 256 KB stack.
     */
    private static final int PLACED = 4_000;
    private static final PirType DATA = new PirType.DataType();
    private static final PirType INTEGER = new PirType.IntegerType();
    private static final PirType LIST = new PirType.ListType(DATA);

    /** The ways a long program nests: each level wraps the rest of the program in one child of one node. */
    enum Spine {
        LET_BODY, IF_ELSE, APP_ARGUMENT, LAM_BODY;

        PirTerm wrap(PirTerm inner, int level) {
            return switch (this) {
                case LET_BODY -> new PirTerm.Let("v" + level, integer(level), inner);
                case IF_ELSE -> new PirTerm.IfThenElse(new PirTerm.Var("c" + level, new PirType.BoolType()),
                        integer(level), inner);
                case APP_ARGUMENT -> new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                        new PirTerm.Const(Constant.integer(BigInteger.valueOf(level)))), inner);
                case LAM_BODY -> new PirTerm.Lam("p" + level, INTEGER, inner);
            };
        }

        /** The child of {@code term} that continues this spine. */
        PirTerm inner(PirTerm term) {
            return switch (this) {
                case LET_BODY -> ((PirTerm.Let) term).body();
                case IF_ELSE -> ((PirTerm.IfThenElse) term).elseBranch();
                case APP_ARGUMENT -> ((PirTerm.App) term).argument();
                case LAM_BODY -> ((PirTerm.Lam) term).body();
            };
        }
    }

    private static PirTerm integer(long value) {
        return new PirTerm.Const(Constant.integer(BigInteger.valueOf(value)));
    }

    private static PirTerm apply(DefaultFun fun, PirTerm... arguments) {
        PirTerm term = new PirTerm.Builtin(fun);
        for (var argument : arguments) term = new PirTerm.App(term, argument);
        return term;
    }

    /** {@code bottom} under {@code depth} levels of {@code spine}, built bottom-up without recursion. */
    private static PirTerm deep(Spine spine, int depth, PirTerm bottom) {
        PirTerm term = bottom;
        for (int level = depth - 1; level >= 0; level--) term = spine.wrap(term, level);
        return term;
    }

    /** The term {@code depth} levels down {@code spine}. */
    private static PirTerm bottom(Spine spine, int depth, PirTerm term) {
        for (int level = 0; level < depth; level++) term = spine.inner(term);
        return term;
    }

    private static <T> T onSmallStack(Supplier<T> work) throws InterruptedException {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        thread.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private static CompilationContext context(OptimizationLevel level) {
        return CompilationContext.resolve(new CompilerOptions().setOptimizationLevel(level));
    }

    /** {@code headList(sndPair(unConstrData(x)))}: the first field of the record {@code x}. */
    private static PirTerm firstField(String record) {
        return apply(DefaultFun.HeadList, apply(DefaultFun.SndPair, apply(DefaultFun.UnConstrData, new PirTerm.Var(record, DATA))));
    }

    @ParameterizedTest
    @EnumSource(Spine.class)
    void projectionSharingSharesAFieldReadTwiceAtTheBottomOfASpine(Spine spine) throws InterruptedException {
        // A field read leads a let spine of trivial values and a cons spine of trivial heads, so it is shared above the
        // whole spine; elsewhere it is shared where it is. Either way some work per level spans the rest of the spine
        // (looking down it for another candidate, or comparing the rewritten term), so the spine is shorter.
        boolean leads = spine == Spine.LET_BODY || spine == Spine.APP_ARGUMENT;
        int depth = 10_000;
        var reads = apply(DefaultFun.EqualsData, firstField("x"), firstField("x"));
        var term = deep(spine, depth, reads);
        var lowered = onSmallStack(() -> new ValueConversionSharingPass(context(OptimizationLevel.PV11_SAFE), Map.of())
                .lower(term).term());
        var shared = assertInstanceOf(PirTerm.Let.class, leads ? lowered : bottom(spine, depth, lowered));
        assertEquals("#field-0", shared.name());
        assertEquals(firstField("x"), shared.value());
        var compared = leads ? bottom(spine, depth, shared.body()) : shared.body();
        var field = new PirTerm.Var("#field-0", DATA);
        assertEquals(apply(DefaultFun.EqualsData, field, field), compared);
    }

    @ParameterizedTest
    @EnumSource(Spine.class)
    void projectionSharingKeepsADeepTermWithNothingToShare(Spine spine) throws InterruptedException {
        var term = deep(spine, DEEP, apply(DefaultFun.EqualsData, firstField("x"), firstField("y")));
        assertSame(term, onSmallStack(() -> new ValueConversionSharingPass(context(OptimizationLevel.PV11_SAFE), Map.of())
                .lower(term).term()));
    }

    /** {@code xs.get(index)} as the lowering spells it. */
    private static PirTerm get(PirTerm.Var list, long index) {
        return PirHelpers.recursiveListGet(list, integer(index));
    }

    private static int countIndexArray(PirTerm term) {
        int[] count = {0};
        PirHelpers.anyTerm(term, inner -> {
            if (inner instanceof PirTerm.Builtin builtin && builtin.fun() == DefaultFun.IndexArray) count[0]++;
            return false;
        });
        return count[0];
    }

    @ParameterizedTest
    @EnumSource(value = Spine.class, names = {"LET_BODY", "IF_ELSE"})
    void listPromotionPlacesItsArrayAtTheBottomOfASpine(Spine spine) throws InterruptedException {
        var xs = new PirTerm.Var("xs", LIST);
        var sites = apply(DefaultFun.EqualsData, get(xs, 0), get(xs, 1));
        var term = new PirTerm.Let("xs", apply(DefaultFun.UnListData, new PirTerm.Var("d", DATA)), deep(spine, PLACED, sites));
        var lowered = assertInstanceOf(PirTerm.Let.class, onSmallStack(() ->
                new ListIndexPromotionPass(context(OptimizationLevel.PV11_COSTED), Map.of()).lower(term).term()));
        var array = assertInstanceOf(PirTerm.Let.class, bottom(spine, PLACED, lowered.body()));
        assertEquals("#array-0", array.name());
        assertEquals(new PirTerm.App(new PirTerm.Builtin(DefaultFun.ListToArray), xs), array.value());
        assertEquals(2, countIndexArray(array.body()));
    }

    @ParameterizedTest
    @EnumSource(Spine.class)
    void listPromotionKeepsADeepTermWithoutAProvenList(Spine spine) throws InterruptedException {
        var xs = new PirTerm.Var("xs", LIST);
        var term = deep(spine, DEEP, apply(DefaultFun.EqualsData, get(xs, 0), get(xs, 1)));
        assertSame(term, onSmallStack(() ->
                new ListIndexPromotionPass(context(OptimizationLevel.PV11_COSTED), Map.of()).lower(term).term()));
    }

    @ParameterizedTest
    @EnumSource(value = Spine.class, names = {"LET_BODY", "IF_ELSE"})
    void listPromotionProvesAListThroughADeepValue(Spine spine) throws InterruptedException {
        var xs = new PirTerm.Var("xs", LIST);
        // The value produces a list at the bottom of its spine (each IF_ELSE level's then-branch is an integer, so only
        // the LET_BODY spine proves it).
        var value = deep(spine, DEEP, apply(DefaultFun.UnListData, new PirTerm.Var("d", DATA)));
        var term = new PirTerm.Let("xs", value, apply(DefaultFun.EqualsData, get(xs, 0), get(xs, 1)));
        var lowered = assertInstanceOf(PirTerm.Let.class, onSmallStack(() ->
                new ListIndexPromotionPass(context(OptimizationLevel.PV11_COSTED), Map.of()).lower(term).term()));
        assertEquals(spine == Spine.LET_BODY ? 2 : 0, countIndexArray(lowered.body()));
    }

    @ParameterizedTest
    @EnumSource(value = Spine.class, names = {"LET_BODY", "IF_ELSE", "APP_ARGUMENT"})
    void delayedMethodsAreDelayedAndCalledAtTheBottomOfASpine(Spine spine) throws InterruptedException {
        var method = new PirTerm.Var("C.m", INTEGER);
        // A method whose value may fail is delayed, and its read becomes a call, however deep both are. (A lambda is
        // total, so a LAM_BODY value is not delayed.)
        var value = deep(spine, PLACED, apply(DefaultFun.DivideInteger, integer(1), new PirTerm.Var("z", INTEGER)));
        var term = new PirTerm.Let("C.m", value, deep(spine, PLACED, method));
        var lowered = assertInstanceOf(PirTerm.Let.class, onSmallStack(() ->
                new DelayedMethodPass(Set.of("C.m"), Set.of(), null, null).lower(term).term()));
        var delayed = assertInstanceOf(PirTerm.Lam.class, lowered.value());
        assertEquals("#_", delayed.param());
        assertSame(value, delayed.body());
        var call = assertInstanceOf(PirTerm.App.class, bottom(spine, PLACED, lowered.body()));
        assertEquals("C.m", assertInstanceOf(PirTerm.Var.class, call.function()).name());
        assertEquals(new PirTerm.Const(Constant.unit()), call.argument());
    }

    @ParameterizedTest
    @EnumSource(value = Spine.class, names = {"LET_BODY", "IF_ELSE"})
    void mayFailReadsADeepTerm(Spine spine) throws InterruptedException {
        // Each level of either spine is total; the bottom decides. Past the work limit the reading gives up, as it may fail.
        var total = deep(spine, PLACED, integer(7));
        var failing = deep(spine, PLACED, apply(DefaultFun.DivideInteger, integer(1), integer(0)));
        var shallow = deep(spine, 10, integer(7));
        assertTrue(onSmallStack(() -> DelayedMethodPass.mayFail(total)), "deep total term exceeds the work limit");
        assertTrue(onSmallStack(() -> DelayedMethodPass.mayFail(failing)));
        assertEquals(spine == Spine.IF_ELSE, onSmallStack(() -> DelayedMethodPass.mayFail(shallow)),
                "a free condition variable is not known, so only the let spine is total");
    }

    @ParameterizedTest
    @EnumSource(Spine.class)
    void delayedMethodPassRebuildsNothingWhenNoMethodIsRead(Spine spine) throws InterruptedException {
        var term = deep(spine, DEEP, integer(1));
        var lowered = onSmallStack(() -> new DelayedMethodPass(Set.of("C.m"), Set.of(), null, null).lower(term).term());
        assertSame(term, lowered);
    }
}
