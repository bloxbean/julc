package com.bloxbean.cardano.julc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TermTest {

    // --- Var ---

    @Test
    void var() {
        var t = Term.var(new NamedDeBruijn("x", 1));
        assertInstanceOf(Term.Var.class, t);
        assertEquals("x", ((Term.Var) t).name().name());
        assertEquals(1, ((Term.Var) t).name().index());
    }

    @Test
    void varByIndex() {
        var t = Term.var(0);
        assertEquals(0, ((Term.Var) t).name().index());
        assertEquals("i0", ((Term.Var) t).name().name());
    }

    @Test
    void varNullRejected() {
        assertThrows(NullPointerException.class, () -> new Term.Var(null));
    }

    // --- Lam ---

    @Test
    void lam() {
        var body = Term.var(0);
        var t = Term.lam("x", body);
        assertInstanceOf(Term.Lam.class, t);
        assertEquals("x", ((Term.Lam) t).paramName());
        assertEquals(body, ((Term.Lam) t).body());
    }

    @Test
    void lamNullRejected() {
        assertThrows(NullPointerException.class, () -> new Term.Lam(null, Term.error()));
        assertThrows(NullPointerException.class, () -> new Term.Lam("x", null));
    }

    // --- Apply ---

    @Test
    void apply() {
        var fun = Term.lam("x", Term.var(0));
        var arg = Term.const_(Constant.integer(42));
        var t = Term.apply(fun, arg);
        assertInstanceOf(Term.Apply.class, t);
        assertEquals(fun, ((Term.Apply) t).function());
        assertEquals(arg, ((Term.Apply) t).argument());
    }

    @Test
    void applyNullRejected() {
        assertThrows(NullPointerException.class, () -> new Term.Apply(null, Term.error()));
        assertThrows(NullPointerException.class, () -> new Term.Apply(Term.error(), null));
    }

    // --- Force ---

    @Test
    void force() {
        var inner = Term.delay(Term.var(0));
        var t = Term.force(inner);
        assertInstanceOf(Term.Force.class, t);
        assertEquals(inner, ((Term.Force) t).term());
    }

    @Test
    void forceNullRejected() {
        assertThrows(NullPointerException.class, () -> new Term.Force(null));
    }

    // --- Delay ---

    @Test
    void delay() {
        var inner = Term.var(0);
        var t = Term.delay(inner);
        assertInstanceOf(Term.Delay.class, t);
        assertEquals(inner, ((Term.Delay) t).term());
    }

    @Test
    void delayNullRejected() {
        assertThrows(NullPointerException.class, () -> new Term.Delay(null));
    }

    // --- Const ---

    @Test
    void const_integer() {
        var t = Term.const_(Constant.integer(42));
        assertInstanceOf(Term.Const.class, t);
        assertInstanceOf(Constant.IntegerConst.class, ((Term.Const) t).value());
    }

    @Test
    void const_string() {
        var t = Term.const_(Constant.string("hello"));
        assertEquals("hello", ((Constant.StringConst) ((Term.Const) t).value()).value());
    }

    @Test
    void constNullRejected() {
        assertThrows(NullPointerException.class, () -> new Term.Const(null));
    }

    // --- Builtin ---

    @Test
    void builtin() {
        var t = Term.builtin(DefaultFun.AddInteger);
        assertInstanceOf(Term.Builtin.class, t);
        assertEquals(DefaultFun.AddInteger, ((Term.Builtin) t).fun());
    }

    @Test
    void builtinNullRejected() {
        assertThrows(NullPointerException.class, () -> new Term.Builtin(null));
    }

    // --- Error ---

    @Test
    void error() {
        var t = Term.error();
        assertInstanceOf(Term.Error.class, t);
    }

    @Test
    void errorEquality() {
        assertEquals(new Term.Error(), new Term.Error());
    }

    // --- Constr (V3) ---

    @Test
    void constr() {
        var t = Term.constr(0, Term.const_(Constant.integer(1)), Term.const_(Constant.integer(2)));
        assertInstanceOf(Term.Constr.class, t);
        assertEquals(0, ((Term.Constr) t).tag());
        assertEquals(2, ((Term.Constr) t).fields().size());
    }

    @Test
    void constrEmptyFields() {
        var t = Term.constr(5);
        assertEquals(5, ((Term.Constr) t).tag());
        assertTrue(((Term.Constr) t).fields().isEmpty());
    }

    @Test
    void constrFieldsImmutable() {
        var t = new Term.Constr(0, List.of(Term.error()));
        assertThrows(UnsupportedOperationException.class, () -> t.fields().add(Term.error()));
    }

    @Test
    void constrUnsigned64Tag() {
        // Tags are unsigned 64-bit: -1L in Java represents 2^64 - 1 (18446744073709551615)
        var t = new Term.Constr(-1, List.of());
        assertEquals(-1L, t.tag()); // Java signed representation
        // When printed, should show unsigned value
        assertEquals("18446744073709551615", Long.toUnsignedString(t.tag()));
    }

    @Test
    void constrLargeTag() {
        // Tags can be large (V3 SOPs)
        var t = new Term.Constr(Long.MAX_VALUE, List.of());
        assertEquals(Long.MAX_VALUE, t.tag());
    }

    // --- Case (V3) ---

    @Test
    void case_() {
        var scrutinee = Term.var(0);
        var branch1 = Term.const_(Constant.bool(true));
        var branch2 = Term.const_(Constant.bool(false));
        var t = Term.case_(scrutinee, branch1, branch2);
        assertInstanceOf(Term.Case.class, t);
        assertEquals(scrutinee, ((Term.Case) t).scrutinee());
        assertEquals(2, ((Term.Case) t).branches().size());
    }

    @Test
    void caseNullScrutineeRejected() {
        assertThrows(NullPointerException.class, () -> new Term.Case(null, List.of()));
    }

    @Test
    void caseBranchesImmutable() {
        var t = new Term.Case(Term.var(0), List.of(Term.error()));
        assertThrows(UnsupportedOperationException.class, () -> t.branches().add(Term.error()));
    }

    // --- Sealed interface pattern matching ---

    @Test
    void sealedInterfacePatternMatch() {
        Term t = Term.const_(Constant.integer(42));
        String result = switch (t) {
            case Term.Var v -> "var";
            case Term.Lam l -> "lam";
            case Term.Apply a -> "apply";
            case Term.Force f -> "force";
            case Term.Delay d -> "delay";
            case Term.Const c -> "const";
            case Term.Builtin b -> "builtin";
            case Term.Error e -> "error";
            case Term.Constr c -> "constr";
            case Term.Case c -> "case";
        };
        assertEquals("const", result);
    }

    // --- Composition: build a realistic UPLC program ---

    @Test
    void identityFunction() {
        // \x -> x  (identity function in UPLC)
        var identity = Term.lam("x", Term.var(new NamedDeBruijn("x", 0)));
        assertInstanceOf(Term.Lam.class, identity);
    }

    @Test
    void addTwoIntegers() {
        // (\x y -> addInteger x y) applied to 3 and 4
        // In UPLC with De Bruijn: [[\x.\y. [[AddInteger (Var 1)] (Var 0)]] (Con 3)] (Con 4)
        var addBuiltin = Term.builtin(DefaultFun.AddInteger);
        var addApplied = Term.apply(Term.apply(addBuiltin, Term.var(1)), Term.var(0));
        var innerLam = Term.lam("y", addApplied);
        var outerLam = Term.lam("x", innerLam);
        var applied = Term.apply(Term.apply(outerLam, Term.const_(Constant.integer(3))),
                Term.const_(Constant.integer(4)));

        assertInstanceOf(Term.Apply.class, applied);
    }

    @Test
    void forceDelayCombination() {
        // (force (delay (con integer 42)))
        var inner = Term.const_(Constant.integer(42));
        var delayed = Term.delay(inner);
        var forced = Term.force(delayed);

        assertInstanceOf(Term.Force.class, forced);
        var forceT = (Term.Force) forced;
        assertInstanceOf(Term.Delay.class, forceT.term());
        var delayT = (Term.Delay) forceT.term();
        assertInstanceOf(Term.Const.class, delayT.term());
    }

    @Test
    void constrAndCase() {
        // Case on a Constr: case (Constr 1 [42]) [branch0, branch1]
        var constr = Term.constr(1, Term.const_(Constant.integer(42)));
        var branch0 = Term.const_(Constant.string("zero"));
        var branch1 = Term.lam("x", Term.var(0)); // identity
        var caseExpr = Term.case_(constr, branch0, branch1);

        var c = (Term.Case) caseExpr;
        assertInstanceOf(Term.Constr.class, c.scrutinee());
        assertEquals(2, c.branches().size());
    }

    @Test
    void polymorphicBuiltin() {
        // Force is needed for polymorphic builtins like IfThenElse:
        // (force ifThenElse) true (con string "yes") (con string "no")
        var ite = Term.force(Term.builtin(DefaultFun.IfThenElse));
        var result = Term.apply(
                Term.apply(
                        Term.apply(ite, Term.const_(Constant.bool(true))),
                        Term.const_(Constant.string("yes"))),
                Term.const_(Constant.string("no")));

        assertInstanceOf(Term.Apply.class, result);
    }
}
