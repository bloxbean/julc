package com.bloxbean.cardano.julc.compiler.pir;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PIR pretty-printer.
 */
class PirFormatterTest {

    @Test
    void formatVar() {
        var term = new PirTerm.Var("x", new PirType.IntegerType());
        assertEquals("x", PirFormatter.format(term));
    }

    @Test
    void formatConst() {
        var term = new PirTerm.Const(new Constant.IntegerConst(BigInteger.valueOf(42)));
        assertEquals("(con integer 42)", PirFormatter.format(term));
    }

    @Test
    void formatBoolConst() {
        var term = new PirTerm.Const(new Constant.BoolConst(true));
        assertEquals("(con bool True)", PirFormatter.format(term));
    }

    @Test
    void formatByteStringConst() {
        var term = new PirTerm.Const(new Constant.ByteStringConst(new byte[]{0x0a, 0x0b}));
        assertEquals("(con bytestring #0a0b)", PirFormatter.format(term));
    }

    @Test
    void formatStringConst() {
        var term = new PirTerm.Const(new Constant.StringConst("hello"));
        assertEquals("(con string \"hello\")", PirFormatter.format(term));
    }

    @Test
    void formatUnitConst() {
        var term = new PirTerm.Const(new Constant.UnitConst());
        assertEquals("(con unit ())", PirFormatter.format(term));
    }

    @Test
    void formatBuiltin() {
        var term = new PirTerm.Builtin(DefaultFun.AddInteger);
        assertEquals("(builtin addInteger)", PirFormatter.format(term));
    }

    @Test
    void formatLam() {
        var body = new PirTerm.Var("x", new PirType.IntegerType());
        var term = new PirTerm.Lam("x", new PirType.IntegerType(), body);
        assertEquals("(lam x : Integer x)", PirFormatter.format(term));
    }

    @Test
    void formatApp() {
        var fun = new PirTerm.Builtin(DefaultFun.AddInteger);
        var arg = new PirTerm.Const(new Constant.IntegerConst(BigInteger.ONE));
        var term = new PirTerm.App(fun, arg);
        assertEquals("[(builtin addInteger) (con integer 1)]", PirFormatter.format(term));
    }

    @Test
    void formatLet() {
        var value = new PirTerm.Const(new Constant.IntegerConst(BigInteger.TEN));
        var body = new PirTerm.Var("y", new PirType.IntegerType());
        var term = new PirTerm.Let("y", value, body);
        assertEquals("(let y = (con integer 10) in y)", PirFormatter.format(term));
    }

    @Test
    void formatLetRec() {
        var binding = new PirTerm.Binding("f",
                new PirTerm.Lam("x", new PirType.IntegerType(), new PirTerm.Var("x", new PirType.IntegerType())));
        var body = new PirTerm.Var("f", new PirType.FunType(new PirType.IntegerType(), new PirType.IntegerType()));
        var term = new PirTerm.LetRec(List.of(binding), body);
        var result = PirFormatter.format(term);
        assertTrue(result.startsWith("(letrec"));
        assertTrue(result.contains("f ="));
        assertTrue(result.contains("in f)"));
    }

    @Test
    void formatIfThenElse() {
        var cond = new PirTerm.Const(new Constant.BoolConst(true));
        var thenBranch = new PirTerm.Const(new Constant.IntegerConst(BigInteger.ONE));
        var elseBranch = new PirTerm.Const(new Constant.IntegerConst(BigInteger.ZERO));
        var term = new PirTerm.IfThenElse(cond, thenBranch, elseBranch);
        assertEquals("(if (con bool True) then (con integer 1) else (con integer 0))",
                PirFormatter.format(term));
    }

    @Test
    void formatDataConstr() {
        var field = new PirTerm.Const(new Constant.IntegerConst(BigInteger.valueOf(42)));
        var term = new PirTerm.DataConstr(0, new PirType.DataType(), List.of(field));
        assertEquals("(constr 0 (con integer 42))", PirFormatter.format(term));
    }

    @Test
    void formatDataMatch() {
        var scrutinee = new PirTerm.Var("x", new PirType.DataType());
        var branch = new PirTerm.MatchBranch("Just", List.of("val"),
                List.of(new PirType.DataType()),
                new PirTerm.Var("val", new PirType.DataType()));
        var term = new PirTerm.DataMatch(scrutinee, List.of(branch));
        var result = PirFormatter.format(term);
        assertTrue(result.contains("match x"));
        assertTrue(result.contains("Just val"));
        assertTrue(result.contains("=> val"));
    }

    @Test
    void formatError() {
        var term = new PirTerm.Error(new PirType.DataType());
        assertEquals("(error)", PirFormatter.format(term));
    }

    @Test
    void formatTrace() {
        var msg = new PirTerm.Const(new Constant.StringConst("debug"));
        var body = new PirTerm.Const(new Constant.UnitConst());
        var term = new PirTerm.Trace(msg, body);
        assertEquals("(trace (con string \"debug\") (con unit ()))", PirFormatter.format(term));
    }

    @Test
    void formatNestedLetInLam() {
        var inner = new PirTerm.Let("y",
                new PirTerm.Const(new Constant.IntegerConst(BigInteger.ONE)),
                new PirTerm.Var("y", new PirType.IntegerType()));
        var term = new PirTerm.Lam("x", new PirType.DataType(), inner);
        var result = PirFormatter.format(term);
        assertTrue(result.startsWith("(lam"));
        assertTrue(result.contains("(let y ="));
    }

    @Test
    void formatTypeInteger() {
        assertEquals("Integer", PirFormatter.formatType(new PirType.IntegerType()));
    }

    @Test
    void formatTypeByteString() {
        assertEquals("ByteString", PirFormatter.formatType(new PirType.ByteStringType()));
    }

    @Test
    void formatTypeFunChain() {
        var ft = new PirType.FunType(new PirType.IntegerType(),
                new PirType.FunType(new PirType.ByteStringType(), new PirType.BoolType()));
        assertEquals("Integer -> ByteString -> Bool", PirFormatter.formatType(ft));
    }

    @Test
    void formatTypeList() {
        var lt = new PirType.ListType(new PirType.IntegerType());
        assertEquals("List[Integer]", PirFormatter.formatType(lt));
    }

    @Test
    void formatTypeMap() {
        var mt = new PirType.MapType(new PirType.ByteStringType(), new PirType.IntegerType());
        assertEquals("Map[ByteString,Integer]", PirFormatter.formatType(mt));
    }

    @Test
    void formatPrettyProducesIndentedOutput() {
        var inner = new PirTerm.Let("y",
                new PirTerm.Const(new Constant.IntegerConst(BigInteger.ONE)),
                new PirTerm.Var("y", new PirType.IntegerType()));
        var term = new PirTerm.Lam("x", new PirType.DataType(), inner);
        var result = PirFormatter.formatPretty(term);
        assertTrue(result.contains("\n"), "Pretty format should contain newlines");
        assertTrue(result.contains("  "), "Pretty format should contain indentation");
    }
}
