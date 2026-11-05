package org.julclang.difftest;

import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;
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

    private static PirTerm constant(Constant constant) {
        return new PirTerm.Const(constant);
    }

    private static PlutusData.IntData i(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    @Test
    void dataIsComparedByValue() {
        different(constant(new Constant.DataConst(i(1))), constant(new Constant.DataConst(i(2))));
        different(constant(new Constant.DataConst(i(1))), constant(new Constant.DataConst(new PlutusData.BytesData(new byte[]{1}))));
        different(constant(new Constant.DataConst(new PlutusData.ConstrData(0, List.of(i(1))))),
                constant(new Constant.DataConst(new PlutusData.ConstrData(1, List.of(i(1))))));
        different(constant(new Constant.DataConst(new PlutusData.ListData(List.of(i(1))))),
                constant(new Constant.DataConst(new PlutusData.ListData(List.of(i(2))))));
        different(constant(new Constant.DataConst(new PlutusData.MapData(List.of(new PlutusData.Pair(i(1), i(2)))))),
                constant(new Constant.DataConst(new PlutusData.MapData(List.of(new PlutusData.Pair(i(2), i(1)))))));
        same(constant(new Constant.DataConst(new PlutusData.ConstrData(3, List.of(i(1), new PlutusData.BytesData(new byte[]{9}))))),
                constant(new Constant.DataConst(new PlutusData.ConstrData(3, List.of(i(1), new PlutusData.BytesData(new byte[]{9}))))));
    }

    @Test
    void aListIsComparedByItsUniverseAndItsValues() {
        var integer = DefaultUni.INTEGER;
        different(constant(new Constant.ListConst(integer, List.of(Constant.integer(BigInteger.ONE)))),
                constant(new Constant.ListConst(integer, List.of(Constant.integer(BigInteger.TWO)))));
        different(constant(new Constant.ListConst(integer, List.of())), constant(new Constant.ListConst(DefaultUni.DATA, List.of())));
        different(constant(new Constant.ListConst(DefaultUni.DATA, List.of())),
                constant(new Constant.ListConst(DefaultUni.pairOf(DefaultUni.DATA, DefaultUni.DATA), List.of())));
        same(constant(new Constant.ListConst(integer, List.of())), constant(new Constant.ListConst(integer, List.of())));
    }

    @Test
    void anArrayIsComparedByItsUniverseAndItsValues() {
        var integer = DefaultUni.INTEGER;
        different(constant(new Constant.ArrayConst(integer, List.of(Constant.integer(BigInteger.ONE)))),
                constant(new Constant.ArrayConst(integer, List.of(Constant.integer(BigInteger.TWO)))));
        different(constant(new Constant.ArrayConst(integer, List.of())), constant(new Constant.ArrayConst(DefaultUni.DATA, List.of())));
        different(constant(new Constant.ArrayConst(integer, List.of())), constant(new Constant.ListConst(integer, List.of())));
    }

    @Test
    void aPairIsComparedOnBothSides() {
        var a = Constant.integer(BigInteger.ONE);
        var b = Constant.integer(BigInteger.TWO);
        different(constant(new Constant.PairConst(a, b)), constant(new Constant.PairConst(b, a)));
        different(constant(new Constant.PairConst(a, a)), constant(new Constant.PairConst(a, b)));
    }

    @Test
    void aValueIsComparedByItsEntries() {
        var token = new Constant.ValueConst.TokenEntry(new byte[]{2}, BigInteger.valueOf(1_000_000));
        var entry = new Constant.ValueConst.ValueEntry(new byte[]{1}, List.of(token));
        different(constant(new Constant.ValueConst(List.of())), constant(new Constant.ValueConst(List.of(entry))));
        different(constant(new Constant.ValueConst(List.of(entry))), constant(new Constant.ValueConst(List.of(
                new Constant.ValueConst.ValueEntry(new byte[]{1}, List.of(new Constant.ValueConst.TokenEntry(new byte[]{2}, BigInteger.ONE)))))));
        different(constant(new Constant.ValueConst(List.of(entry))), constant(new Constant.ValueConst(List.of(
                new Constant.ValueConst.ValueEntry(new byte[]{3}, List.of(token))))));
    }

    @Test
    void aBlsValueIsComparedByItsBytes() {
        different(constant(new Constant.Bls12_381_G1Element(new byte[]{1})), constant(new Constant.Bls12_381_G1Element(new byte[]{2})));
        different(constant(new Constant.Bls12_381_G2Element(new byte[]{1})), constant(new Constant.Bls12_381_G2Element(new byte[]{2})));
        different(constant(new Constant.Bls12_381_MlResult(new byte[]{1})), constant(new Constant.Bls12_381_MlResult(new byte[]{2})));
        different(constant(new Constant.Bls12_381_G1Element(new byte[]{1})), constant(new Constant.Bls12_381_G2Element(new byte[]{1})));
    }

    @Test
    void scalarsKeepTheirValueAndTheirType() {
        different(constant(new Constant.ByteStringConst(new byte[]{1})), constant(new Constant.ByteStringConst(new byte[]{2})));
        different(constant(new Constant.StringConst("a")), constant(new Constant.StringConst("b")));
        different(constant(new Constant.StringConst("1")), constant(Constant.integer(BigInteger.ONE)));
        different(constant(new Constant.BoolConst(true)), constant(new Constant.BoolConst(false)));
        different(constant(new Constant.UnitConst()), constant(new Constant.BoolConst(true)));
    }

    /** A name bound twice at once goes back to what it shadowed, so a free name stays free after it. */
    @Test
    void aNameBoundTwiceAtOnceLeavesNothingBehind() {
        var scrutinee = one();
        var left = new PirTerm.Lam("p", INT, new PirTerm.App(
                new PirTerm.DataMatch(scrutinee, List.of(new PirTerm.MatchBranch("A", List.of("x"), List.of(INT), one(), "x"))),
                new PirTerm.Lam("q", INT, v("x"))));
        var right = new PirTerm.Lam("p", INT, new PirTerm.App(
                new PirTerm.DataMatch(scrutinee, List.of(new PirTerm.MatchBranch("A", List.of("a"), List.of(INT), one(), "b"))),
                new PirTerm.Lam("q", INT, v("q"))));
        different(left, right);

        var rec = new PirTerm.LetRec(List.of(new PirTerm.Binding("x", one()), new PirTerm.Binding("x", two())), one());
        var free = new PirTerm.App(rec, new PirTerm.Lam("q", INT, v("x")));
        var bound = new PirTerm.App(new PirTerm.LetRec(List.of(new PirTerm.Binding("a", one()), new PirTerm.Binding("b", two())), one()),
                new PirTerm.Lam("q", INT, v("q")));
        different(free, bound);
    }

    /** And an outer binder of the name is what is back in scope. */
    @Test
    void aNameBoundTwiceAtOnceRestoresItsOuterBinder() {
        var inner = new PirTerm.LetRec(List.of(new PirTerm.Binding("x", one()), new PirTerm.Binding("x", two())), one());
        same(new PirTerm.Lam("x", INT, new PirTerm.App(inner, v("x"))),
                new PirTerm.Lam("y", INT, new PirTerm.App(new PirTerm.LetRec(
                        List.of(new PirTerm.Binding("a", one()), new PirTerm.Binding("b", two())), one()), v("y"))));
    }
}
