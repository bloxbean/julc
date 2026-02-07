package com.bloxbean.cardano.plutus.compiler.pir;

import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PirTermTest {

    @Test void varTerm() {
        var t = new PirTerm.Var("x", new PirType.IntegerType());
        assertEquals("x", t.name());
    }

    @Test void constTerm() {
        var t = new PirTerm.Const(Constant.integer(BigInteger.valueOf(42)));
        assertInstanceOf(Constant.IntegerConst.class, t.value());
    }

    @Test void builtinTerm() {
        var t = new PirTerm.Builtin(DefaultFun.AddInteger);
        assertEquals(DefaultFun.AddInteger, t.fun());
    }

    @Test void lamTerm() {
        var body = new PirTerm.Var("x", new PirType.IntegerType());
        var t = new PirTerm.Lam("x", new PirType.IntegerType(), body);
        assertEquals("x", t.param());
    }

    @Test void appTerm() {
        var fn = new PirTerm.Builtin(DefaultFun.AddInteger);
        var arg = new PirTerm.Const(Constant.integer(BigInteger.ONE));
        var t = new PirTerm.App(fn, arg);
        assertNotNull(t.function());
        assertNotNull(t.argument());
    }

    @Test void letTerm() {
        var t = new PirTerm.Let("x",
                new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                new PirTerm.Var("x", new PirType.IntegerType()));
        assertEquals("x", t.name());
    }

    @Test void ifThenElseTerm() {
        var t = new PirTerm.IfThenElse(
                new PirTerm.Const(Constant.bool(true)),
                new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
        assertNotNull(t.cond());
    }

    @Test void dataConstrTerm() {
        var t = new PirTerm.DataConstr(0, new PirType.RecordType("R", List.of()),
                List.of(new PirTerm.Const(Constant.integer(BigInteger.ONE))));
        assertEquals(0, t.tag());
        assertEquals(1, t.fields().size());
    }

    @Test void errorTerm() {
        var t = new PirTerm.Error(new PirType.UnitType());
        assertNotNull(t.type());
    }

    @Test void traceTerm() {
        var t = new PirTerm.Trace(
                new PirTerm.Const(Constant.string("debug")),
                new PirTerm.Const(Constant.unit()));
        assertNotNull(t.message());
    }

    @Test void matchBranch() {
        var b = new PirTerm.MatchBranch("Left", List.of("x"),
                new PirTerm.Var("x", new PirType.IntegerType()));
        assertEquals("Left", b.constructorName());
        assertEquals(1, b.bindings().size());
    }

    @Test void letRecTerm() {
        var t = new PirTerm.LetRec(
                List.of(new PirTerm.Binding("f", new PirTerm.Const(Constant.integer(BigInteger.ONE)))),
                new PirTerm.Var("f", new PirType.IntegerType()));
        assertEquals(1, t.bindings().size());
    }
}
