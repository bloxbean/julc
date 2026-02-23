package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for individual pattern recognizers.
 */
class RecognizerTest {

    // --- ForceCollapser tests ---

    @Test
    void testForceCollapserAddInteger() {
        // Apply(Apply(Builtin(AddInteger), Const(1)), Const(2))
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger),
                        Term.const_(Constant.integer(1))),
                Term.const_(Constant.integer(2)));

        var fb = ForceCollapser.matchForcedBuiltin(term);
        assertNotNull(fb);
        assertEquals(DefaultFun.AddInteger, fb.fun());
        assertEquals(2, fb.args().size());
    }

    @Test
    void testForceCollapserIfThenElse() {
        // Force(Force(Builtin(IfThenElse))) with 3 args
        var ifBuiltin = Term.force(Term.force(Term.builtin(DefaultFun.IfThenElse)));
        var applied = Term.apply(
                Term.apply(
                        Term.apply(ifBuiltin, Term.const_(Constant.bool(true))),
                        Term.delay(Term.const_(Constant.integer(1)))),
                Term.delay(Term.const_(Constant.integer(0))));

        var fb = ForceCollapser.matchForcedBuiltin(applied);
        assertNotNull(fb);
        assertEquals(DefaultFun.IfThenElse, fb.fun());
        assertEquals(3, fb.args().size());
    }

    @Test
    void testForceCollapserHeadList() {
        // Force(Apply(Force(Builtin(HeadList)), arg))
        // HeadList requires 1 force
        var headList = Term.force(Term.builtin(DefaultFun.HeadList));
        var applied = Term.apply(headList, Term.var(new NamedDeBruijn("list", 1)));

        var fb = ForceCollapser.matchForcedBuiltin(applied);
        assertNotNull(fb);
        assertEquals(DefaultFun.HeadList, fb.fun());
        assertEquals(1, fb.args().size());
    }

    @Test
    void testForceCollapserNullForNonBuiltin() {
        var term = Term.apply(
                Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                Term.const_(Constant.integer(42)));

        var fb = ForceCollapser.matchForcedBuiltin(term);
        assertNull(fb);
    }

    @Test
    void testForceCountKnownBuiltins() {
        assertEquals(2, ForceCollapser.forceCount(DefaultFun.IfThenElse));
        assertEquals(2, ForceCollapser.forceCount(DefaultFun.FstPair));
        assertEquals(2, ForceCollapser.forceCount(DefaultFun.SndPair));
        assertEquals(2, ForceCollapser.forceCount(DefaultFun.MkCons));
        assertEquals(1, ForceCollapser.forceCount(DefaultFun.HeadList));
        assertEquals(1, ForceCollapser.forceCount(DefaultFun.TailList));
        assertEquals(1, ForceCollapser.forceCount(DefaultFun.NullList));
        assertEquals(0, ForceCollapser.forceCount(DefaultFun.AddInteger));
    }

    // --- LetRecognizer tests ---

    @Test
    void testLetRecognizerMatch() {
        // Apply(Lam("x", Var(x)), Const(42)) -> Let(x, 42, Var(x))
        var term = Term.apply(
                Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                Term.const_(Constant.integer(42)));

        assertTrue(LetRecognizer.isLet(term));
        var match = LetRecognizer.match(term);
        assertNotNull(match);
        assertEquals("x", match.name());
        assertInstanceOf(Term.Const.class, match.value());
        assertInstanceOf(Term.Var.class, match.body());
    }

    @Test
    void testLetRecognizerNoMatch() {
        var term = Term.apply(
                Term.var(new NamedDeBruijn("f", 1)),
                Term.const_(Constant.integer(42)));

        assertFalse(LetRecognizer.isLet(term));
        assertNull(LetRecognizer.match(term));
    }

    // --- IfThenElseRecognizer tests ---

    @Test
    void testIfThenElseRecognizer() {
        // Force(Apply(Apply(Apply(Force(Force(Builtin(IfThenElse))), cond), Delay(then)), Delay(else)))
        var ifBuiltin = Term.force(Term.force(Term.builtin(DefaultFun.IfThenElse)));
        var term = Term.force(
                Term.apply(
                        Term.apply(
                                Term.apply(ifBuiltin, Term.const_(Constant.bool(true))),
                                Term.delay(Term.const_(Constant.integer(1)))),
                        Term.delay(Term.const_(Constant.integer(0)))));

        var match = IfThenElseRecognizer.match(term);
        assertNotNull(match);
        assertInstanceOf(Term.Const.class, match.condition());
        assertInstanceOf(Term.Const.class, match.thenBranch());
        assertInstanceOf(Term.Const.class, match.elseBranch());
    }

    @Test
    void testIfThenElseNotMatched() {
        var term = Term.const_(Constant.integer(42));
        var match = IfThenElseRecognizer.match(term);
        assertNull(match);
    }

    // --- LoopRecognizer tests ---

    @Test
    void testZCombinatorRecognition() {
        // Build Z-combinator pattern as generated by UplcGenerator
        var innerBody = Term.lam("v",
                Term.apply(Term.apply(Term.var(2), Term.var(2)), Term.var(1)));
        var branch = Term.lam("x", Term.apply(Term.var(2), innerBody));
        var fix = Term.lam("f", Term.apply(branch, branch));

        var recBody = Term.lam("self",
                Term.const_(Constant.integer(42))); // simplified

        var fixApp = Term.apply(fix, recBody);

        // Let(loop, fixApp, body)
        var term = Term.apply(
                Term.lam("loop", Term.var(new NamedDeBruijn("loop", 1))),
                fixApp);

        var match = LoopRecognizer.match(term);
        assertNotNull(match);
        assertEquals("loop", match.name());
    }

    @Test
    void testLoopClassificationForEach() {
        // Body with NullList, HeadList, TailList -> FOR_EACH
        var body = Term.apply(
                Term.apply(
                        Term.apply(
                                Term.force(Term.force(Term.builtin(DefaultFun.IfThenElse))),
                                Term.apply(Term.force(Term.builtin(DefaultFun.NullList)),
                                        Term.var(new NamedDeBruijn("list", 1)))),
                        Term.delay(Term.const_(Constant.unit()))),
                Term.delay(
                        Term.apply(
                                Term.force(Term.builtin(DefaultFun.HeadList)),
                                Term.apply(
                                        Term.force(Term.builtin(DefaultFun.TailList)),
                                        Term.var(new NamedDeBruijn("list", 1))))));

        assertEquals(LoopRecognizer.LoopKind.FOR_EACH, LoopRecognizer.classifyBody(body));
    }

    @Test
    void testLoopClassificationWhile() {
        // Body with IfThenElse but no list ops -> WHILE
        var body = Term.force(
                Term.apply(
                        Term.apply(
                                Term.apply(
                                        Term.force(Term.force(Term.builtin(DefaultFun.IfThenElse))),
                                        Term.const_(Constant.bool(true))),
                                Term.delay(Term.const_(Constant.integer(1)))),
                        Term.delay(Term.const_(Constant.integer(0)))));

        assertEquals(LoopRecognizer.LoopKind.WHILE, LoopRecognizer.classifyBody(body));
    }

    // --- SopRecognizer tests ---

    @Test
    void testConstrRecognition() {
        var term = Term.constr(1, Term.const_(Constant.integer(42)),
                Term.const_(Constant.byteString(new byte[]{1, 2, 3})));

        var match = SopRecognizer.matchConstr(term);
        assertNotNull(match);
        assertEquals(1, match.tag());
        assertEquals(2, match.fields().size());
    }

    @Test
    void testCaseRecognition() {
        var scrutinee = Term.var(new NamedDeBruijn("x", 1));
        var term = Term.case_(scrutinee,
                Term.lam("f1", Term.var(new NamedDeBruijn("f1", 1))),
                Term.lam("f2", Term.lam("f3", Term.var(new NamedDeBruijn("f2", 2)))));

        var match = SopRecognizer.matchCase(term);
        assertNotNull(match);
        assertEquals(2, match.branches().size());

        // Branch 0: 1 field
        assertEquals(1, match.branches().get(0).fieldNames().size());
        // Branch 1: 2 fields
        assertEquals(2, match.branches().get(1).fieldNames().size());
    }

    // --- FieldAccessRecognizer tests ---

    @Test
    void testFieldAccessIndex0() {
        // HeadList(source) -> field index 0
        var source = Term.var(new NamedDeBruijn("fields", 1));
        var term = Term.apply(Term.force(Term.builtin(DefaultFun.HeadList)), source);

        var match = FieldAccessRecognizer.match(term);
        assertNotNull(match);
        assertEquals(0, match.fieldIndex());
    }

    @Test
    void testFieldAccessIndex2() {
        // HeadList(TailList(TailList(source))) -> field index 2
        var source = Term.var(new NamedDeBruijn("fields", 1));
        var tail1 = Term.apply(Term.force(Term.builtin(DefaultFun.TailList)), source);
        var tail2 = Term.apply(Term.force(Term.builtin(DefaultFun.TailList)), tail1);
        var head = Term.apply(Term.force(Term.builtin(DefaultFun.HeadList)), tail2);

        var match = FieldAccessRecognizer.match(head);
        assertNotNull(match);
        assertEquals(2, match.fieldIndex());
    }

    @Test
    void testTailChainRecognition() {
        var source = Term.var(new NamedDeBruijn("fields", 1));
        var tail1 = Term.apply(Term.force(Term.builtin(DefaultFun.TailList)), source);
        var tail2 = Term.apply(Term.force(Term.builtin(DefaultFun.TailList)), tail1);
        var tail3 = Term.apply(Term.force(Term.builtin(DefaultFun.TailList)), tail2);

        var match = FieldAccessRecognizer.matchTailChain(tail3);
        assertNotNull(match);
        assertEquals(3, match.tailCount());
    }
}
