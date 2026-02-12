package com.bloxbean.cardano.julc.core.text;

import com.bloxbean.cardano.julc.core.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UplcParser}.
 */
class UplcParserTest {

    // ---- Program ----

    @Test
    void parseProgram() {
        var program = UplcParser.parseProgram("(program 1.1.0 (lam x x))");
        assertEquals(1, program.major());
        assertEquals(1, program.minor());
        assertEquals(0, program.patch());
        assertInstanceOf(Term.Lam.class, program.term());
    }

    @Test
    void parseProgramV1() {
        var program = UplcParser.parseProgram("(program 1.0.0 (error))");
        assertEquals(1, program.major());
        assertEquals(0, program.minor());
        assertEquals(0, program.patch());
        assertInstanceOf(Term.Error.class, program.term());
    }

    @Test
    void parseProgramWithWhitespace() {
        var program = UplcParser.parseProgram("  ( program  1.1.0  (error)  )  ");
        assertInstanceOf(Term.Error.class, program.term());
    }

    @Test
    void parseProgramWithComments() {
        var program = UplcParser.parseProgram("""
                -- This is a comment
                (program 1.1.0
                    -- Another comment
                    (lam x x))
                """);
        assertInstanceOf(Term.Lam.class, program.term());
    }

    // ---- Var ----

    @Test
    void parseVar() {
        var term = UplcParser.parseTerm("x");
        assertInstanceOf(Term.Var.class, term);
        assertEquals("x", ((Term.Var) term).name().name());
    }

    @Test
    void parseVarWithUnderscore() {
        var term = UplcParser.parseTerm("foo_bar");
        assertInstanceOf(Term.Var.class, term);
        assertEquals("foo_bar", ((Term.Var) term).name().name());
    }

    // ---- Lam ----

    @Test
    void parseLamIdentity() {
        var term = UplcParser.parseTerm("(lam x x)");
        assertInstanceOf(Term.Lam.class, term);
        var lam = (Term.Lam) term;
        assertEquals("x", lam.paramName());
        assertInstanceOf(Term.Var.class, lam.body());
    }

    @Test
    void parseNestedLam() {
        var term = UplcParser.parseTerm("(lam x (lam y x))");
        var lam = (Term.Lam) term;
        assertEquals("x", lam.paramName());
        var inner = (Term.Lam) lam.body();
        assertEquals("y", inner.paramName());
        assertInstanceOf(Term.Var.class, inner.body());
    }

    // ---- Apply ----

    @Test
    void parseApply() {
        var term = UplcParser.parseTerm("[(lam x x) (con integer 42)]");
        assertInstanceOf(Term.Apply.class, term);
        var app = (Term.Apply) term;
        assertInstanceOf(Term.Lam.class, app.function());
        assertInstanceOf(Term.Const.class, app.argument());
    }

    @Test
    void parseMultiApply() {
        // [f a b] should fold to [[f a] b]
        var term = UplcParser.parseTerm("[(builtin addInteger) (con integer 1) (con integer 2)]");
        assertInstanceOf(Term.Apply.class, term);
        var outer = (Term.Apply) term;
        assertInstanceOf(Term.Apply.class, outer.function());
        // argument of outer is (con integer 2)
        var innerArg = ((Term.Const) outer.argument()).value();
        assertEquals(new BigInteger("2"), ((Constant.IntegerConst) innerArg).value());
    }

    // ---- Force / Delay ----

    @Test
    void parseForce() {
        var term = UplcParser.parseTerm("(force (builtin ifThenElse))");
        assertInstanceOf(Term.Force.class, term);
        assertInstanceOf(Term.Builtin.class, ((Term.Force) term).term());
    }

    @Test
    void parseDelay() {
        var term = UplcParser.parseTerm("(delay (error))");
        assertInstanceOf(Term.Delay.class, term);
        assertInstanceOf(Term.Error.class, ((Term.Delay) term).term());
    }

    // ---- Const ----

    @Test
    void parseConstInteger() {
        var term = UplcParser.parseTerm("(con integer 42)");
        var c = (Term.Const) term;
        var i = (Constant.IntegerConst) c.value();
        assertEquals(new BigInteger("42"), i.value());
    }

    @Test
    void parseConstNegativeInteger() {
        var term = UplcParser.parseTerm("(con integer -100)");
        var c = (Term.Const) term;
        var i = (Constant.IntegerConst) c.value();
        assertEquals(new BigInteger("-100"), i.value());
    }

    @Test
    void parseConstZero() {
        var term = UplcParser.parseTerm("(con integer 0)");
        var c = (Term.Const) term;
        var i = (Constant.IntegerConst) c.value();
        assertEquals(BigInteger.ZERO, i.value());
    }

    @Test
    void parseConstBigInteger() {
        var term = UplcParser.parseTerm("(con integer 99999999999999999999)");
        var c = (Term.Const) term;
        var i = (Constant.IntegerConst) c.value();
        assertEquals(new BigInteger("99999999999999999999"), i.value());
    }

    @Test
    void parseConstByteString() {
        var term = UplcParser.parseTerm("(con bytestring #aabbcc)");
        var c = (Term.Const) term;
        var bs = (Constant.ByteStringConst) c.value();
        assertArrayEquals(new byte[]{(byte) 0xaa, (byte) 0xbb, (byte) 0xcc}, bs.value());
    }

    @Test
    void parseConstEmptyByteString() {
        var term = UplcParser.parseTerm("(con bytestring #)");
        var c = (Term.Const) term;
        var bs = (Constant.ByteStringConst) c.value();
        assertArrayEquals(new byte[]{}, bs.value());
    }

    @Test
    void parseConstString() {
        var term = UplcParser.parseTerm("(con string \"hello\")");
        var c = (Term.Const) term;
        var s = (Constant.StringConst) c.value();
        assertEquals("hello", s.value());
    }

    @Test
    void parseConstStringWithEscapes() {
        var term = UplcParser.parseTerm("(con string \"line1\\nline2\\t\\\"q\\\"\")");
        var c = (Term.Const) term;
        var s = (Constant.StringConst) c.value();
        assertEquals("line1\nline2\t\"q\"", s.value());
    }

    @Test
    void parseConstStringWithBell() {
        var term = UplcParser.parseTerm("(con string \"\\a\")");
        var c = (Term.Const) term;
        var s = (Constant.StringConst) c.value();
        assertEquals("\u0007", s.value());
    }

    @Test
    void parseConstUnit() {
        var term = UplcParser.parseTerm("(con unit ())");
        var c = (Term.Const) term;
        assertInstanceOf(Constant.UnitConst.class, c.value());
    }

    @Test
    void parseConstBoolTrue() {
        var term = UplcParser.parseTerm("(con bool True)");
        var c = (Term.Const) term;
        assertTrue(((Constant.BoolConst) c.value()).value());
    }

    @Test
    void parseConstBoolFalse() {
        var term = UplcParser.parseTerm("(con bool False)");
        var c = (Term.Const) term;
        assertFalse(((Constant.BoolConst) c.value()).value());
    }

    @Test
    void parseConstData() {
        var term = UplcParser.parseTerm("(con data I 42)");
        var c = (Term.Const) term;
        var d = (Constant.DataConst) c.value();
        assertEquals(PlutusData.integer(42), d.value());
    }

    @Test
    void parseConstDataByteString() {
        var term = UplcParser.parseTerm("(con data B #0a0b)");
        var c = (Term.Const) term;
        var d = (Constant.DataConst) c.value();
        assertEquals(PlutusData.bytes(new byte[]{0x0a, 0x0b}), d.value());
    }

    @Test
    void parseConstDataConstr() {
        var term = UplcParser.parseTerm("(con data Constr 0 [I 1, B #ff])");
        var c = (Term.Const) term;
        var d = (Constant.DataConst) c.value();
        var constr = (PlutusData.ConstrData) d.value();
        assertEquals(0, constr.tag());
        assertEquals(2, constr.fields().size());
    }

    @Test
    void parseConstDataList() {
        var term = UplcParser.parseTerm("(con data List [I 1, I 2, I 3])");
        var c = (Term.Const) term;
        var d = (Constant.DataConst) c.value();
        var list = (PlutusData.ListData) d.value();
        assertEquals(3, list.items().size());
    }

    @Test
    void parseConstDataEmptyList() {
        var term = UplcParser.parseTerm("(con data List [])");
        var c = (Term.Const) term;
        var d = (Constant.DataConst) c.value();
        var list = (PlutusData.ListData) d.value();
        assertEquals(0, list.items().size());
    }

    @Test
    void parseConstDataMap() {
        var term = UplcParser.parseTerm("(con data Map [(I 1, B #ff), (I 2, B #00)])");
        var c = (Term.Const) term;
        var d = (Constant.DataConst) c.value();
        var map = (PlutusData.MapData) d.value();
        assertEquals(2, map.entries().size());
    }

    @Test
    void parseConstDataEmptyMap() {
        var term = UplcParser.parseTerm("(con data Map [])");
        var c = (Term.Const) term;
        var d = (Constant.DataConst) c.value();
        var map = (PlutusData.MapData) d.value();
        assertEquals(0, map.entries().size());
    }

    @Test
    void parseConstDataNestedConstr() {
        var term = UplcParser.parseTerm("(con data Constr 0 [Constr 1 [I 42]])");
        var c = (Term.Const) term;
        var d = (Constant.DataConst) c.value();
        var outer = (PlutusData.ConstrData) d.value();
        assertEquals(0, outer.tag());
        var inner = (PlutusData.ConstrData) outer.fields().getFirst();
        assertEquals(1, inner.tag());
        assertEquals(PlutusData.integer(42), inner.fields().getFirst());
    }

    @Test
    void parseConstListOfIntegers() {
        var term = UplcParser.parseTerm("(con (list integer) [1, 2, 3])");
        var c = (Term.Const) term;
        var list = (Constant.ListConst) c.value();
        assertEquals(DefaultUni.INTEGER, list.elemType());
        assertEquals(3, list.values().size());
        assertEquals(new BigInteger("1"), ((Constant.IntegerConst) list.values().getFirst()).value());
    }

    @Test
    void parseConstEmptyList() {
        var term = UplcParser.parseTerm("(con (list integer) [])");
        var c = (Term.Const) term;
        var list = (Constant.ListConst) c.value();
        assertEquals(0, list.values().size());
    }

    @Test
    void parseConstListOfByteStrings() {
        var term = UplcParser.parseTerm("(con (list bytestring) [#aa, #bb])");
        var c = (Term.Const) term;
        var list = (Constant.ListConst) c.value();
        assertEquals(DefaultUni.BYTESTRING, list.elemType());
        assertEquals(2, list.values().size());
    }

    @Test
    void parseConstPair() {
        var term = UplcParser.parseTerm("(con (pair integer bool) (42, True))");
        var c = (Term.Const) term;
        var pair = (Constant.PairConst) c.value();
        assertEquals(new BigInteger("42"), ((Constant.IntegerConst) pair.first()).value());
        assertTrue(((Constant.BoolConst) pair.second()).value());
    }

    @Test
    void parseConstNestedPair() {
        var term = UplcParser.parseTerm("(con (pair integer (pair bool bytestring)) (42, (True, #ff)))");
        var c = (Term.Const) term;
        var outer = (Constant.PairConst) c.value();
        assertEquals(new BigInteger("42"), ((Constant.IntegerConst) outer.first()).value());
        var inner = (Constant.PairConst) outer.second();
        assertTrue(((Constant.BoolConst) inner.first()).value());
    }

    @Test
    void parseConstNestedListOfLists() {
        var term = UplcParser.parseTerm("(con (list (list integer)) [[1, 2], [3]])");
        var c = (Term.Const) term;
        var outer = (Constant.ListConst) c.value();
        assertEquals(2, outer.values().size());
        var inner1 = (Constant.ListConst) outer.values().getFirst();
        assertEquals(2, inner1.values().size());
    }

    // ---- Builtin ----

    @Test
    void parseBuiltinAddInteger() {
        var term = UplcParser.parseTerm("(builtin addInteger)");
        var b = (Term.Builtin) term;
        assertEquals(DefaultFun.AddInteger, b.fun());
    }

    @Test
    void parseBuiltinIfThenElse() {
        var term = UplcParser.parseTerm("(builtin ifThenElse)");
        var b = (Term.Builtin) term;
        assertEquals(DefaultFun.IfThenElse, b.fun());
    }

    @Test
    void parseBuiltinBls() {
        var term = UplcParser.parseTerm("(builtin bls12_381_G1_add)");
        var b = (Term.Builtin) term;
        assertEquals(DefaultFun.Bls12_381_G1_add, b.fun());
    }

    @Test
    void parseBuiltinSha2() {
        var term = UplcParser.parseTerm("(builtin sha2_256)");
        var b = (Term.Builtin) term;
        assertEquals(DefaultFun.Sha2_256, b.fun());
    }

    // ---- Error ----

    @Test
    void parseError() {
        var term = UplcParser.parseTerm("(error)");
        assertInstanceOf(Term.Error.class, term);
    }

    // ---- Constr (V3) ----

    @Test
    void parseConstr() {
        var term = UplcParser.parseTerm("(constr 0)");
        var c = (Term.Constr) term;
        assertEquals(0, c.tag());
        assertEquals(0, c.fields().size());
    }

    @Test
    void parseConstrWithFields() {
        var term = UplcParser.parseTerm("(constr 1 (con integer 42) (con bool True))");
        var c = (Term.Constr) term;
        assertEquals(1, c.tag());
        assertEquals(2, c.fields().size());
    }

    // ---- Case (V3) ----

    @Test
    void parseCase() {
        var term = UplcParser.parseTerm("(case (constr 0) (con integer 1) (con integer 2))");
        var cs = (Term.Case) term;
        assertInstanceOf(Term.Constr.class, cs.scrutinee());
        assertEquals(2, cs.branches().size());
    }

    // ---- Error cases ----

    @Test
    void parseEmptyInputThrows() {
        assertThrows(UplcParseException.class, () -> UplcParser.parseTerm(""));
    }

    @Test
    void parseUnknownKeywordThrows() {
        assertThrows(UplcParseException.class, () -> UplcParser.parseTerm("(foobar x)"));
    }

    @Test
    void parseUnterminatedParenThrows() {
        assertThrows(UplcParseException.class, () -> UplcParser.parseTerm("(lam x x"));
    }

    @Test
    void parseUnknownBuiltinThrows() {
        assertThrows(UplcParseException.class, () -> UplcParser.parseTerm("(builtin notABuiltin)"));
    }

    @Test
    void parseTrailingContentThrows() {
        assertThrows(UplcParseException.class, () -> UplcParser.parseTerm("(error) extra"));
    }

    @Test
    void parseOddHexStringThrows() {
        assertThrows(UplcParseException.class, () -> UplcParser.parseTerm("(con bytestring #abc)"));
    }

    @Test
    void parseInvalidBoolThrows() {
        assertThrows(UplcParseException.class, () -> UplcParser.parseTerm("(con bool true)")); // must be True
    }

    // ---- Security: depth limit ----

    @Test
    void deepNestingThrows() {
        // Build deeply nested (lam x (lam x (lam x ... x)))
        var sb = new StringBuilder();
        for (int i = 0; i < 1100; i++) {
            sb.append("(lam x ");
        }
        sb.append("x");
        for (int i = 0; i < 1100; i++) {
            sb.append(')');
        }
        assertThrows(UplcParseException.class, () -> UplcParser.parseTerm(sb.toString()));
    }

    @Test
    void deepDataNestingThrows() {
        // Nested data: Constr 0 [Constr 0 [Constr 0 [...]]]
        var sb = new StringBuilder("(con data ");
        for (int i = 0; i < 1100; i++) {
            sb.append("Constr 0 [");
        }
        sb.append("I 1");
        for (int i = 0; i < 1100; i++) {
            sb.append(']');
        }
        sb.append(')');
        assertThrows(UplcParseException.class, () -> UplcParser.parseTerm(sb.toString()));
    }

    // ---- Security: escape sequence validation ----

    @Test
    void invalidControlCharEscapeThrows() {
        // \^? is out of range (? = 63, needs >= 64 for @)
        assertThrows(UplcParseException.class,
                () -> UplcParser.parseTerm("(con string \"\\^?\")"));
    }

    @Test
    void validControlCharEscape() {
        // \^A = control-A (0x01)
        var term = UplcParser.parseTerm("(con string \"\\^A\")");
        var s = ((Constant.StringConst) ((Term.Const) term).value()).value();
        assertEquals("\u0001", s);
    }

    @Test
    void validControlCharEscapeAtSign() {
        // \^@ = NUL (0x00)
        var term = UplcParser.parseTerm("(con string \"\\^@\")");
        var s = ((Constant.StringConst) ((Term.Const) term).value()).value();
        assertEquals("\u0000", s);
    }

    // ---- Complex programs ----

    @Test
    void parseComplexProgram() {
        var program = UplcParser.parseProgram(
                "(program 1.1.0 [[(force (builtin ifThenElse)) (con bool True)] (con integer 1)])");
        assertEquals(1, program.major());
        assertEquals(1, program.minor());
        assertInstanceOf(Term.Apply.class, program.term());
    }

    @Test
    void parseAlwaysSucceeds() {
        // A simple always-succeeds validator
        var program = UplcParser.parseProgram("(program 1.1.0 (lam ctx (con unit ())))");
        var lam = (Term.Lam) program.term();
        assertEquals("ctx", lam.paramName());
        assertInstanceOf(Term.Const.class, lam.body());
    }
}
