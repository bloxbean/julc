package com.bloxbean.cardano.plutus.core.text;

import com.bloxbean.cardano.plutus.core.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UplcPrinter}.
 */
class UplcPrinterTest {

    // ---- Program ----

    @Test
    void printProgramV3() {
        var program = Program.plutusV3(Term.lam("x", Term.var(new NamedDeBruijn("x", 1))));
        assertEquals("(program 1.1.0 (lam x x))", UplcPrinter.print(program));
    }

    @Test
    void printProgramV1() {
        var program = Program.plutusV1(Term.error());
        assertEquals("(program 1.0.0 (error))", UplcPrinter.print(program));
    }

    // ---- Var ----

    @Test
    void printVar() {
        assertEquals("x", UplcPrinter.print(Term.var(new NamedDeBruijn("x", 1))));
    }

    @Test
    void printVarDefaultName() {
        assertEquals("i3", UplcPrinter.print(Term.var(3)));
    }

    // ---- Lam ----

    @Test
    void printLam() {
        var identity = Term.lam("x", Term.var(new NamedDeBruijn("x", 1)));
        assertEquals("(lam x x)", UplcPrinter.print(identity));
    }

    @Test
    void printNestedLam() {
        var term = Term.lam("x", Term.lam("y", Term.var(new NamedDeBruijn("x", 2))));
        assertEquals("(lam x (lam y x))", UplcPrinter.print(term));
    }

    // ---- Apply ----

    @Test
    void printApply() {
        var term = Term.apply(
                Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                Term.const_(Constant.integer(42)));
        assertEquals("[(lam x x) (con integer 42)]", UplcPrinter.print(term));
    }

    @Test
    void printNestedApply() {
        var term = Term.apply(
                Term.apply(Term.builtin(DefaultFun.AddInteger), Term.const_(Constant.integer(1))),
                Term.const_(Constant.integer(2)));
        assertEquals("[[(builtin addInteger) (con integer 1)] (con integer 2)]",
                UplcPrinter.print(term));
    }

    // ---- Force / Delay ----

    @Test
    void printForce() {
        assertEquals("(force (delay x))",
                UplcPrinter.print(Term.force(Term.delay(Term.var(new NamedDeBruijn("x", 1))))));
    }

    @Test
    void printDelay() {
        assertEquals("(delay (error))", UplcPrinter.print(Term.delay(Term.error())));
    }

    // ---- Const ----

    @Test
    void printConstInteger() {
        assertEquals("(con integer 42)", UplcPrinter.print(Term.const_(Constant.integer(42))));
    }

    @Test
    void printConstNegativeInteger() {
        assertEquals("(con integer -100)", UplcPrinter.print(Term.const_(Constant.integer(-100))));
    }

    @Test
    void printConstBigInteger() {
        assertEquals("(con integer 99999999999999999999)",
                UplcPrinter.print(Term.const_(Constant.integer(new BigInteger("99999999999999999999")))));
    }

    @Test
    void printConstByteString() {
        assertEquals("(con bytestring #aabbcc)",
                UplcPrinter.print(Term.const_(Constant.byteString(new byte[]{(byte)0xaa, (byte)0xbb, (byte)0xcc}))));
    }

    @Test
    void printConstEmptyByteString() {
        assertEquals("(con bytestring #)",
                UplcPrinter.print(Term.const_(Constant.byteString(new byte[]{}))));
    }

    @Test
    void printConstString() {
        assertEquals("(con string \"hello\")",
                UplcPrinter.print(Term.const_(Constant.string("hello"))));
    }

    @Test
    void printConstStringWithEscapes() {
        assertEquals("(con string \"line1\\nline2\\t\\\"quoted\\\"\")",
                UplcPrinter.print(Term.const_(Constant.string("line1\nline2\t\"quoted\""))));
    }

    @Test
    void printConstUnit() {
        assertEquals("(con unit ())", UplcPrinter.print(Term.const_(Constant.unit())));
    }

    @Test
    void printConstBoolTrue() {
        assertEquals("(con bool True)", UplcPrinter.print(Term.const_(Constant.bool(true))));
    }

    @Test
    void printConstBoolFalse() {
        assertEquals("(con bool False)", UplcPrinter.print(Term.const_(Constant.bool(false))));
    }

    @Test
    void printConstData() {
        assertEquals("(con data I 42)",
                UplcPrinter.print(Term.const_(Constant.data(PlutusData.integer(42)))));
    }

    @Test
    void printConstDataConstr() {
        var data = PlutusData.constr(0, PlutusData.integer(1), PlutusData.bytes(new byte[]{0x0a}));
        assertEquals("(con data Constr 0 [I 1, B #0a])",
                UplcPrinter.print(Term.const_(Constant.data(data))));
    }

    @Test
    void printConstDataList() {
        var data = PlutusData.list(PlutusData.integer(1), PlutusData.integer(2));
        assertEquals("(con data List [I 1, I 2])",
                UplcPrinter.print(Term.const_(Constant.data(data))));
    }

    @Test
    void printConstDataMap() {
        var data = PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.bytes(new byte[]{(byte)0xff})));
        assertEquals("(con data Map [(I 1, B #ff)])",
                UplcPrinter.print(Term.const_(Constant.data(data))));
    }

    @Test
    void printConstListOfIntegers() {
        var list = new Constant.ListConst(DefaultUni.INTEGER,
                List.of(Constant.integer(1), Constant.integer(2), Constant.integer(3)));
        assertEquals("(con (list integer) [1, 2, 3])",
                UplcPrinter.print(Term.const_(list)));
    }

    @Test
    void printConstEmptyList() {
        var list = new Constant.ListConst(DefaultUni.INTEGER, List.of());
        assertEquals("(con (list integer) [])", UplcPrinter.print(Term.const_(list)));
    }

    @Test
    void printConstPair() {
        var pair = new Constant.PairConst(Constant.integer(42), Constant.bool(true));
        assertEquals("(con (pair integer bool) (42, True))",
                UplcPrinter.print(Term.const_(pair)));
    }

    @Test
    void printConstNestedList() {
        var inner1 = new Constant.ListConst(DefaultUni.INTEGER,
                List.of(Constant.integer(1), Constant.integer(2)));
        var inner2 = new Constant.ListConst(DefaultUni.INTEGER,
                List.of(Constant.integer(3)));
        var outer = new Constant.ListConst(DefaultUni.listOf(DefaultUni.INTEGER),
                List.of(inner1, inner2));
        assertEquals("(con (list (list integer)) [[1, 2], [3]])",
                UplcPrinter.print(Term.const_(outer)));
    }

    // ---- Builtin ----

    @Test
    void printBuiltin() {
        assertEquals("(builtin addInteger)",
                UplcPrinter.print(Term.builtin(DefaultFun.AddInteger)));
    }

    @Test
    void printBuiltinBls() {
        assertEquals("(builtin bls12_381_G1_add)",
                UplcPrinter.print(Term.builtin(DefaultFun.Bls12_381_G1_add)));
    }

    // ---- Error ----

    @Test
    void printError() {
        assertEquals("(error)", UplcPrinter.print(Term.error()));
    }

    // ---- Constr (V3) ----

    @Test
    void printConstr() {
        assertEquals("(constr 0)", UplcPrinter.print(Term.constr(0)));
    }

    @Test
    void printConstrWithFields() {
        assertEquals("(constr 1 (con integer 42) (con bool True))",
                UplcPrinter.print(Term.constr(1, Term.const_(Constant.integer(42)), Term.const_(Constant.bool(true)))));
    }

    // ---- Case (V3) ----

    @Test
    void printCase() {
        var term = Term.case_(
                Term.constr(0, Term.const_(Constant.integer(42))),
                Term.lam("x", Term.var(new NamedDeBruijn("x", 1))),
                Term.error());
        assertEquals("(case (constr 0 (con integer 42)) (lam x x) (error))",
                UplcPrinter.print(term));
    }

    // ---- Name sanitization ----

    @Test
    void sanitizeNameWithDots() {
        assertEquals("foo_bar", UplcPrinter.sanitizeName("foo.bar"));
    }

    @Test
    void sanitizeNameWithDollar() {
        assertEquals("anonfun_1", UplcPrinter.sanitizeName("anonfun$1"));
    }

    @Test
    void sanitizeNameStartingWithDigit() {
        assertEquals("_1foo", UplcPrinter.sanitizeName("1foo"));
    }

    @Test
    void sanitizeNormalName() {
        assertEquals("foo_bar", UplcPrinter.sanitizeName("foo_bar"));
    }

    // ---- Builtin name conversion ----

    @Test
    void builtinNameConversion() {
        assertEquals("addInteger", UplcPrinter.builtinName(DefaultFun.AddInteger));
        assertEquals("ifThenElse", UplcPrinter.builtinName(DefaultFun.IfThenElse));
        assertEquals("sha2_256", UplcPrinter.builtinName(DefaultFun.Sha2_256));
    }

    // ---- Complex programs ----

    @Test
    void printComplexProgram() {
        // (program 1.1.0 [(force (builtin ifThenElse)) (con bool True) (con integer 1) (con integer 0)])
        var term = Term.apply(
                Term.apply(
                        Term.apply(
                                Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                Term.const_(Constant.bool(true))),
                        Term.const_(Constant.integer(1))),
                Term.const_(Constant.integer(0)));
        var program = Program.plutusV3(term);
        assertEquals("(program 1.1.0 [[[(force (builtin ifThenElse)) (con bool True)] (con integer 1)] (con integer 0)])",
                UplcPrinter.print(program));
    }
}
