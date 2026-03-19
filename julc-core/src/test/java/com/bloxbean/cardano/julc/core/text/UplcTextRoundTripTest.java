package com.bloxbean.cardano.julc.core.text;

import com.bloxbean.cardano.julc.core.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests: print → parse → print and parse → print → parse.
 * Verifies that the printer and parser are consistent with each other.
 */
class UplcTextRoundTripTest {

    // ---- Term round-trips (construct → print → parse → print again) ----

    @Test
    void roundTripVar() {
        assertTermRoundTrip(Term.var(new NamedDeBruijn("x", 0)));
    }

    @Test
    void roundTripLam() {
        assertTermRoundTrip(Term.lam("x", Term.var(new NamedDeBruijn("x", 0))));
    }

    @Test
    void roundTripNestedLam() {
        assertTermRoundTrip(Term.lam("x", Term.lam("y", Term.var(new NamedDeBruijn("x", 0)))));
    }

    @Test
    void roundTripApply() {
        assertTermRoundTrip(Term.apply(
                Term.lam("x", Term.var(new NamedDeBruijn("x", 0))),
                Term.const_(Constant.integer(42))));
    }

    @Test
    void roundTripForce() {
        assertTermRoundTrip(Term.force(Term.builtin(DefaultFun.IfThenElse)));
    }

    @Test
    void roundTripDelay() {
        assertTermRoundTrip(Term.delay(Term.const_(Constant.integer(1))));
    }

    @Test
    void roundTripError() {
        assertTermRoundTrip(Term.error());
    }

    @Test
    void roundTripConstr() {
        assertTermRoundTrip(Term.constr(0));
        assertTermRoundTrip(Term.constr(3, Term.const_(Constant.integer(42))));
    }

    @Test
    void roundTripCase() {
        assertTermRoundTrip(Term.case_(
                Term.constr(0, Term.const_(Constant.integer(1))),
                Term.lam("x", Term.var(new NamedDeBruijn("x", 0))),
                Term.error()));
    }

    // ---- Constant round-trips ----

    @Test
    void roundTripConstInteger() {
        assertConstRoundTrip(Constant.integer(0));
        assertConstRoundTrip(Constant.integer(42));
        assertConstRoundTrip(Constant.integer(-999));
        assertConstRoundTrip(Constant.integer(new BigInteger("99999999999999999999")));
    }

    @Test
    void roundTripConstByteString() {
        assertConstRoundTrip(Constant.byteString(new byte[]{}));
        assertConstRoundTrip(Constant.byteString(new byte[]{0x01, 0x02}));
        assertConstRoundTrip(Constant.byteString(new byte[]{(byte) 0xff, (byte) 0xab}));
    }

    @Test
    void roundTripConstString() {
        assertConstRoundTrip(Constant.string(""));
        assertConstRoundTrip(Constant.string("hello world"));
        assertConstRoundTrip(Constant.string("with\nnewlines\ttabs"));
        assertConstRoundTrip(Constant.string("quotes \"inside\""));
        assertConstRoundTrip(Constant.string("backslash \\"));
    }

    @Test
    void roundTripConstUnit() {
        assertConstRoundTrip(Constant.unit());
    }

    @Test
    void roundTripConstBool() {
        assertConstRoundTrip(Constant.bool(true));
        assertConstRoundTrip(Constant.bool(false));
    }

    @Test
    void roundTripConstData() {
        assertConstRoundTrip(Constant.data(PlutusData.integer(42)));
        assertConstRoundTrip(Constant.data(PlutusData.integer(-1)));
        assertConstRoundTrip(Constant.data(PlutusData.bytes(new byte[]{0x0a})));
        assertConstRoundTrip(Constant.data(PlutusData.bytes(new byte[]{})));
        assertConstRoundTrip(Constant.data(PlutusData.list()));
        assertConstRoundTrip(Constant.data(PlutusData.list(PlutusData.integer(1), PlutusData.integer(2))));
        assertConstRoundTrip(Constant.data(PlutusData.map()));
        assertConstRoundTrip(Constant.data(PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.bytes(new byte[]{(byte) 0xff})))));
        assertConstRoundTrip(Constant.data(PlutusData.constr(0)));
        assertConstRoundTrip(Constant.data(PlutusData.constr(1, PlutusData.integer(42))));
        assertConstRoundTrip(Constant.data(PlutusData.constr(0,
                PlutusData.constr(1, PlutusData.integer(99)))));
    }

    @Test
    void roundTripConstList() {
        assertConstRoundTrip(new Constant.ListConst(DefaultUni.INTEGER, List.of()));
        assertConstRoundTrip(new Constant.ListConst(DefaultUni.INTEGER,
                List.of(Constant.integer(1), Constant.integer(2))));
        assertConstRoundTrip(new Constant.ListConst(DefaultUni.BYTESTRING,
                List.of(Constant.byteString(new byte[]{0x01}), Constant.byteString(new byte[]{0x02}))));
    }

    @Test
    void roundTripConstPair() {
        assertConstRoundTrip(new Constant.PairConst(Constant.integer(42), Constant.bool(true)));
        assertConstRoundTrip(new Constant.PairConst(Constant.string("key"), Constant.integer(1)));
    }

    @Test
    void roundTripConstNestedList() {
        var inner = new Constant.ListConst(DefaultUni.INTEGER,
                List.of(Constant.integer(1), Constant.integer(2)));
        assertConstRoundTrip(new Constant.ListConst(DefaultUni.listOf(DefaultUni.INTEGER),
                List.of(inner)));
    }

    @Test
    void roundTripConstNestedPair() {
        var inner = new Constant.PairConst(Constant.bool(true), Constant.integer(0));
        assertConstRoundTrip(new Constant.PairConst(Constant.integer(1), inner));
    }

    // ---- Program round-trips ----

    @Test
    void roundTripProgramV1() {
        assertProgramRoundTrip(Program.plutusV1(Term.error()));
    }

    @Test
    void roundTripProgramV3() {
        assertProgramRoundTrip(Program.plutusV3(Term.lam("x", Term.var(new NamedDeBruijn("x", 0)))));
    }

    @Test
    void roundTripComplexProgram() {
        var term = Term.apply(
                Term.apply(
                        Term.apply(
                                Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                Term.const_(Constant.bool(true))),
                        Term.const_(Constant.integer(1))),
                Term.const_(Constant.integer(0)));
        assertProgramRoundTrip(Program.plutusV3(term));
    }

    @Test
    void roundTripConstrCase() {
        var term = Term.case_(
                Term.constr(0, Term.const_(Constant.integer(42))),
                Term.lam("a", Term.var(new NamedDeBruijn("a", 0))),
                Term.lam("b", Term.var(new NamedDeBruijn("b", 0))));
        assertProgramRoundTrip(Program.plutusV3(term));
    }

    // ---- All builtins round-trip ----

    @Test
    void roundTripAllBuiltins() {
        for (var fun : DefaultFun.values()) {
            var term = Term.builtin(fun);
            String printed = UplcPrinter.print(term);
            var parsed = UplcParser.parseTerm(printed);
            assertEquals(term, parsed,
                    "Builtin round-trip failed for " + fun + ": printed as " + printed);
        }
    }

    // ---- Text round-trips (parse text → print → verify text matches) ----

    @Test
    void textRoundTripProgram() {
        assertTextRoundTrip("(program 1.1.0 (lam x x))");
        assertTextRoundTrip("(program 1.0.0 (error))");
    }

    @Test
    void textRoundTripTerms() {
        assertTextRoundTrip("(error)");
        assertTextRoundTrip("(lam x x)");
        assertTextRoundTrip("(force (delay x))");
        assertTextRoundTrip("(con integer 42)");
        assertTextRoundTrip("(con bytestring #aabb)");
        assertTextRoundTrip("(con string \"hello\")");
        assertTextRoundTrip("(con unit ())");
        assertTextRoundTrip("(con bool True)");
        assertTextRoundTrip("(builtin addInteger)");
        assertTextRoundTrip("(constr 0)");
        assertTextRoundTrip("(constr 1 (con integer 42))");
    }

    @Test
    void textRoundTripData() {
        assertTextRoundTrip("(con data I 42)");
        assertTextRoundTrip("(con data B #ff)");
        assertTextRoundTrip("(con data Constr 0 [])");
        assertTextRoundTrip("(con data Constr 1 [I 42, B #ff])");
        assertTextRoundTrip("(con data List [])");
        assertTextRoundTrip("(con data List [I 1, I 2])");
        assertTextRoundTrip("(con data Map [])");
        assertTextRoundTrip("(con data Map [(I 1, B #ff)])");
    }

    @Test
    void textRoundTripCompoundConstants() {
        assertTextRoundTrip("(con (list integer) [1, 2, 3])");
        assertTextRoundTrip("(con (list integer) [])");
        assertTextRoundTrip("(con (pair integer bool) (42, True))");
        assertTextRoundTrip("(con (list (list integer)) [[1, 2], [3]])");
    }

    // ---- Array and Value round-trips ----

    @Test
    void roundTripConstArray() {
        assertConstRoundTrip(new Constant.ArrayConst(DefaultUni.BOOL,
                List.of(Constant.bool(true), Constant.bool(false))));
        assertConstRoundTrip(new Constant.ArrayConst(DefaultUni.INTEGER, List.of()));
        assertConstRoundTrip(new Constant.ArrayConst(DefaultUni.INTEGER,
                List.of(Constant.integer(1), Constant.integer(2), Constant.integer(3))));
    }

    @Test
    void roundTripConstValue() {
        assertConstRoundTrip(new Constant.ValueConst(List.of()));
        assertConstRoundTrip(new Constant.ValueConst(List.of(
                new Constant.ValueConst.ValueEntry(new byte[]{0x01, 0x02},
                        List.of(new Constant.ValueConst.TokenEntry(new byte[]{0x03}, BigInteger.valueOf(100)))))));
    }

    @Test
    void textRoundTripArray() {
        assertTextRoundTrip("(con (array bool) [True,False])");
        assertTextRoundTrip("(con (array integer) [])");
        assertTextRoundTrip("(con (array integer) [1,2,3])");
    }

    @Test
    void textRoundTripValue() {
        assertTextRoundTrip("(con value [(#0102, [(#03, 100)])])");
        assertTextRoundTrip("(con value [])");
    }

    // ---- Helpers ----

    private void assertTermRoundTrip(Term original) {
        String printed = UplcPrinter.print(original);
        var parsed = UplcParser.parseTerm(printed);
        String reprinted = UplcPrinter.print(parsed);
        assertEquals(printed, reprinted,
                "Term round-trip text mismatch.\n  Original: " + original + "\n  Printed: " + printed + "\n  Reprinted: " + reprinted);
    }

    private void assertConstRoundTrip(Constant original) {
        var term = Term.const_(original);
        String printed = UplcPrinter.print(term);
        var parsed = UplcParser.parseTerm(printed);
        String reprinted = UplcPrinter.print(parsed);
        assertEquals(printed, reprinted,
                "Constant round-trip text mismatch.\n  Printed: " + printed + "\n  Reprinted: " + reprinted);
    }

    private void assertProgramRoundTrip(Program original) {
        String printed = UplcPrinter.print(original);
        var parsed = UplcParser.parseProgram(printed);
        String reprinted = UplcPrinter.print(parsed);
        assertEquals(printed, reprinted,
                "Program round-trip text mismatch.\n  Printed: " + printed + "\n  Reprinted: " + reprinted);
    }

    private void assertTextRoundTrip(String text) {
        // Determine if it's a program or term
        if (text.trim().startsWith("(program")) {
            var parsed = UplcParser.parseProgram(text);
            String reprinted = UplcPrinter.print(parsed);
            assertEquals(text, reprinted, "Text round-trip failed");
        } else {
            var parsed = UplcParser.parseTerm(text);
            String reprinted = UplcPrinter.print(parsed);
            assertEquals(text, reprinted, "Text round-trip failed");
        }
    }
}
