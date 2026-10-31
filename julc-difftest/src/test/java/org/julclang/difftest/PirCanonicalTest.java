package org.julclang.difftest;

import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The I1b comparison: equal exactly for alpha-equivalent terms, with the differences it must not lose. */
class PirCanonicalTest {

    private static final PirType INT = new PirType.IntegerType();
    private static final PirType BOOL = new PirType.BoolType();

    private static PirTerm.Var v(String name) {
        return new PirTerm.Var(name, INT);
    }

    private static PirTerm one() {
        return new PirTerm.Const(Constant.integer(BigInteger.ONE));
    }

    private static PirTerm two() {
        return new PirTerm.Const(Constant.integer(BigInteger.TWO));
    }

    private static void same(PirTerm a, PirTerm b) {
        assertEquals(PirCanonical.of(a), PirCanonical.of(b));
    }

    private static void different(PirTerm a, PirTerm b) {
        assertNotEquals(PirCanonical.of(a), PirCanonical.of(b));
    }

    @Test
    void renamingABoundVariableChangesNothing() {
        same(new PirTerm.Lam("a", INT, v("a")), new PirTerm.Lam("K'1", INT, v("K'1")));
        same(new PirTerm.Let("x", one(), v("x")), new PirTerm.Let("y", one(), v("y")));
    }

    @Test
    void whichBinderAVarNamesIsKept() {
        different(new PirTerm.Lam("a", INT, new PirTerm.Lam("b", INT, v("a"))),
                new PirTerm.Lam("a", INT, new PirTerm.Lam("b", INT, v("b"))));
    }

    @Test
    void shadowingIsResolvedByScope() {
        same(new PirTerm.Lam("a", INT, new PirTerm.Lam("a", INT, v("a"))),
                new PirTerm.Lam("x", INT, new PirTerm.Lam("y", INT, v("y"))));
        different(new PirTerm.Lam("a", INT, new PirTerm.Lam("a", INT, v("a"))),
                new PirTerm.Lam("x", INT, new PirTerm.Lam("y", INT, v("x"))));
    }

    @Test
    void aBinderStopsAtTheEndOfItsScope() {
        var left = new PirTerm.App(new PirTerm.Lam("a", INT, v("a")), v("a"));
        var right = new PirTerm.App(new PirTerm.Lam("b", INT, v("b")), v("a"));
        same(left, right);
        different(left, new PirTerm.App(new PirTerm.Lam("b", INT, v("b")), v("b")));
    }

    @Test
    void aFreeNameIsItsName() {
        different(v("a"), v("b"));
    }

    @Test
    void aLetValueDoesNotSeeItsBinderButARecursiveOneDoes() {
        different(new PirTerm.Let("f", v("f"), one()),
                new PirTerm.LetRec(List.of(new PirTerm.Binding("f", v("f"))), one()));
        same(new PirTerm.Let("f", v("g"), one()), new PirTerm.Let("h", v("g"), one()));
    }

    @Test
    void typesConstantsAndBuiltinsAreCompared() {
        different(new PirTerm.Lam("a", INT, one()), new PirTerm.Lam("a", BOOL, one()));
        different(new PirTerm.Var("a", INT), new PirTerm.Var("a", BOOL));
        different(one(), two());
        different(new PirTerm.Builtin(DefaultFun.AddInteger), new PirTerm.Builtin(DefaultFun.SubtractInteger));
        different(new PirTerm.Error(INT), new PirTerm.Error(BOOL));
    }

    /** A constant's text holds its value: a byte array must not print as an identity, equal arrays must be equal. */
    @Test
    void byteStringsCompareByContent() {
        same(new PirTerm.Const(Constant.byteString(new byte[]{1, 2})), new PirTerm.Const(Constant.byteString(new byte[]{1, 2})));
        different(new PirTerm.Const(Constant.byteString(new byte[]{1, 2})), new PirTerm.Const(Constant.byteString(new byte[]{1, 3})));
        different(new PirTerm.Const(Constant.string("1")), new PirTerm.Const(Constant.integer(BigInteger.ONE)));
    }

    @Test
    void aLetIsNotItsInlinedValue() {
        different(new PirTerm.Let("#_", one(), two()), two());
    }

    @Test
    void theOrderOfAnApplicationAndABranchIsKept() {
        different(new PirTerm.App(one(), two()), new PirTerm.App(two(), one()));
        different(new PirTerm.IfThenElse(one(), two(), one()), new PirTerm.IfThenElse(one(), one(), two()));
    }

    @Test
    void matchBindersAreBinders() {
        var branch = new PirTerm.MatchBranch("A", List.of("x", "y"), List.of(INT, INT), v("y"));
        var renamed = new PirTerm.MatchBranch("A", List.of("p", "q"), List.of(INT, INT), v("q"));
        var other = new PirTerm.MatchBranch("A", List.of("p", "q"), List.of(INT, INT), v("p"));
        same(new PirTerm.DataMatch(one(), List.of(branch)), new PirTerm.DataMatch(one(), List.of(renamed)));
        different(new PirTerm.DataMatch(one(), List.of(branch)), new PirTerm.DataMatch(one(), List.of(other)));
    }

    @Test
    void listAndPairMatchesBindInTheirBranchOnly() {
        var left = new PirTerm.ListMatch(one(), "h", "t", v("h"), v("h"));
        var right = new PirTerm.ListMatch(one(), "a", "b", v("h"), v("a"));
        same(left, right);
        different(left, new PirTerm.ListMatch(one(), "a", "b", v("a"), v("a")));
    }
}
