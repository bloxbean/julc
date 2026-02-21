package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UplcLifter: UPLC to HIR conversion.
 */
class UplcLifterTest {

    @Test
    void testLiftConstant() {
        var term = Term.const_(Constant.integer(42));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.IntLiteral.class, hir);
        assertEquals(42, ((HirTerm.IntLiteral) hir).value().intValueExact());
    }

    @Test
    void testLiftByteString() {
        var term = Term.const_(Constant.byteString(new byte[]{(byte) 0xDE, (byte) 0xAD}));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.ByteStringLiteral.class, hir);
    }

    @Test
    void testLiftString() {
        var term = Term.const_(Constant.string("hello"));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.StringLiteral.class, hir);
        assertEquals("hello", ((HirTerm.StringLiteral) hir).value());
    }

    @Test
    void testLiftBool() {
        var term = Term.const_(Constant.bool(true));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.BoolLiteral.class, hir);
        assertTrue(((HirTerm.BoolLiteral) hir).value());
    }

    @Test
    void testLiftUnit() {
        var term = Term.const_(Constant.unit());
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.UnitLiteral.class, hir);
    }

    @Test
    void testLiftVar() {
        var term = Term.var(new NamedDeBruijn("x", 1));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.Var.class, hir);
        assertEquals("x", ((HirTerm.Var) hir).name());
    }

    @Test
    void testLiftLambda() {
        // Lam("x", Lam("y", Var(y)))
        var term = Term.lam("x", Term.lam("y", Term.var(new NamedDeBruijn("y", 1))));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.Lambda.class, hir);
        var lambda = (HirTerm.Lambda) hir;
        assertEquals(2, lambda.params().size());
        assertEquals("x", lambda.params().get(0));
        assertEquals("y", lambda.params().get(1));
    }

    @Test
    void testLiftLetBinding() {
        // Apply(Lam("x", Var(x)), Const(42)) -> Let(x, 42, Var(x))
        var term = Term.apply(
                Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                Term.const_(Constant.integer(42)));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.Let.class, hir);
        var let = (HirTerm.Let) hir;
        assertEquals("x", let.name());
        assertInstanceOf(HirTerm.IntLiteral.class, let.value());
    }

    @Test
    void testLiftIfThenElse() {
        // Build IfThenElse pattern
        var ifBuiltin = Term.force(Term.force(Term.builtin(DefaultFun.IfThenElse)));
        var term = Term.force(
                Term.apply(
                        Term.apply(
                                Term.apply(ifBuiltin, Term.const_(Constant.bool(true))),
                                Term.delay(Term.const_(Constant.integer(1)))),
                        Term.delay(Term.const_(Constant.integer(0)))));

        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.If.class, hir);
        var iff = (HirTerm.If) hir;
        assertInstanceOf(HirTerm.BoolLiteral.class, iff.condition());
        assertInstanceOf(HirTerm.IntLiteral.class, iff.thenBranch());
        assertInstanceOf(HirTerm.IntLiteral.class, iff.elseBranch());
    }

    @Test
    void testLiftSimpleBuiltinCall() {
        // Apply(Apply(Builtin(AddInteger), Const(1)), Const(2))
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger),
                        Term.const_(Constant.integer(1))),
                Term.const_(Constant.integer(2)));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.BuiltinCall.class, hir);
        var bc = (HirTerm.BuiltinCall) hir;
        assertEquals(DefaultFun.AddInteger, bc.fun());
        assertEquals(2, bc.args().size());
    }

    @Test
    void testLiftDataEncode() {
        // Apply(Builtin(IData), Const(42))
        var term = Term.apply(Term.builtin(DefaultFun.IData),
                Term.const_(Constant.integer(42)));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.DataEncode.class, hir);
        assertEquals(DefaultFun.IData, ((HirTerm.DataEncode) hir).encoder());
    }

    @Test
    void testLiftDataDecode() {
        // Apply(Builtin(UnIData), arg)
        var term = Term.apply(Term.builtin(DefaultFun.UnIData),
                Term.var(new NamedDeBruijn("d", 1)));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.DataDecode.class, hir);
        assertEquals(DefaultFun.UnIData, ((HirTerm.DataDecode) hir).decoder());
    }

    @Test
    void testLiftError() {
        var term = Term.error();
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.Error.class, hir);
    }

    @Test
    void testLiftSopConstr() {
        // Constr(0, [Const(42)])
        var term = Term.constr(0, Term.const_(Constant.integer(42)));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.Constructor.class, hir);
        var c = (HirTerm.Constructor) hir;
        assertEquals(0, c.tag());
        assertEquals(1, c.fields().size());
    }

    @Test
    void testLiftSopCase() {
        // Case(Var(x), [Lam("f", body1), Lam("f", body2)])
        var term = Term.case_(
                Term.var(new NamedDeBruijn("x", 1)),
                Term.lam("f1", Term.const_(Constant.integer(1))),
                Term.lam("f2", Term.const_(Constant.integer(2))));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.Switch.class, hir);
        var sw = (HirTerm.Switch) hir;
        assertEquals(2, sw.branches().size());
        assertEquals(0, sw.branches().get(0).tag());
        assertEquals(1, sw.branches().get(1).tag());
    }

    @Test
    void testLiftNestedLet() {
        // let x = 1 in let y = 2 in x + y
        var inner = Term.apply(
                Term.lam("y",
                        Term.apply(
                                Term.apply(Term.builtin(DefaultFun.AddInteger),
                                        Term.var(new NamedDeBruijn("x", 2))),
                                Term.var(new NamedDeBruijn("y", 1)))),
                Term.const_(Constant.integer(2)));
        var outer = Term.apply(
                Term.lam("x", inner),
                Term.const_(Constant.integer(1)));

        var hir = UplcLifter.lift(outer);
        assertInstanceOf(HirTerm.Let.class, hir);
        var let = (HirTerm.Let) hir;
        assertEquals("x", let.name());
        assertInstanceOf(HirTerm.Let.class, let.body());
        var innerLet = (HirTerm.Let) let.body();
        assertEquals("y", innerLet.name());
    }

    @Test
    void testLiftTrace() {
        // Apply(Apply(Force(Force(Builtin(Trace))), "hello"), body)
        // Trace requires 2 forces (polymorphic in 2 type vars)
        var traceBuiltin = Term.force(Term.force(Term.builtin(DefaultFun.Trace)));
        var term = Term.apply(
                Term.apply(traceBuiltin, Term.const_(Constant.string("hello"))),
                Term.const_(Constant.integer(42)));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.Trace.class, hir);
        var tr = (HirTerm.Trace) hir;
        assertInstanceOf(HirTerm.StringLiteral.class, tr.message());
        assertInstanceOf(HirTerm.IntLiteral.class, tr.body());
    }

    @Test
    void testLiftDataLiteral() {
        var term = Term.const_(Constant.data(PlutusData.integer(42)));
        var hir = UplcLifter.lift(term);
        assertInstanceOf(HirTerm.DataLiteral.class, hir);
    }
}
