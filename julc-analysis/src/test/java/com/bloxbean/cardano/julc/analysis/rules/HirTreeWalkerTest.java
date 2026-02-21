package com.bloxbean.cardano.julc.analysis.rules;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.decompiler.hir.HirType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HirTreeWalkerTest {

    @Test
    void walk_visitsAllNodes() {
        var inner = new HirTerm.IntLiteral(BigInteger.TEN);
        var tree = new HirTerm.Let("x", inner,
                new HirTerm.Var("x", HirType.INTEGER));

        int[] count = {0};
        HirTreeWalker.walk(tree, _ -> count[0]++);
        assertEquals(3, count[0], "Let + IntLiteral + Var = 3 nodes");
    }

    @Test
    void walk_handlesNull() {
        int[] count = {0};
        HirTreeWalker.walk(null, _ -> count[0]++);
        assertEquals(0, count[0]);
    }

    @Test
    void collect_findsMatchingNodes() {
        var tree = new HirTerm.Let("x",
                new HirTerm.IntLiteral(BigInteger.ONE),
                new HirTerm.Let("y",
                        new HirTerm.IntLiteral(BigInteger.TWO),
                        new HirTerm.Var("y", HirType.INTEGER)));

        var ints = HirTreeWalker.collect(tree,
                node -> node instanceof HirTerm.IntLiteral);
        assertEquals(2, ints.size());
    }

    @Test
    void anyMatch_returnsTrueWhenFound() {
        var tree = new HirTerm.Let("x",
                new HirTerm.ByteStringLiteral(new byte[28]),
                new HirTerm.Error());

        assertTrue(HirTreeWalker.anyMatch(tree,
                node -> node instanceof HirTerm.Error));
    }

    @Test
    void anyMatch_returnsFalseWhenNotFound() {
        var tree = new HirTerm.IntLiteral(BigInteger.ONE);
        assertFalse(HirTreeWalker.anyMatch(tree,
                node -> node instanceof HirTerm.Error));
    }

    @Test
    void count_countsMatches() {
        var tree = new HirTerm.If(
                new HirTerm.BoolLiteral(true),
                new HirTerm.Error(),
                new HirTerm.Error());

        int errors = HirTreeWalker.count(tree,
                node -> node instanceof HirTerm.Error);
        assertEquals(2, errors);
    }

    @Test
    void walk_traversesBuiltinCallArgs() {
        var tree = new HirTerm.BuiltinCall(DefaultFun.AddInteger, List.of(
                new HirTerm.IntLiteral(BigInteger.ONE),
                new HirTerm.IntLiteral(BigInteger.TWO)));

        var ints = HirTreeWalker.collect(tree,
                node -> node instanceof HirTerm.IntLiteral);
        assertEquals(2, ints.size());
    }

    @Test
    void walk_traversesSwitch() {
        var branches = List.of(
                new HirTerm.SwitchBranch(0, "A", List.of(), new HirTerm.IntLiteral(BigInteger.ONE)),
                new HirTerm.SwitchBranch(1, "B", List.of(), new HirTerm.IntLiteral(BigInteger.TWO)));
        var tree = new HirTerm.Switch(
                new HirTerm.Var("x", HirType.DATA), "MyType", branches);

        var ints = HirTreeWalker.collect(tree,
                node -> node instanceof HirTerm.IntLiteral);
        assertEquals(2, ints.size());
    }

    @Test
    void walk_traversesLambda() {
        var tree = new HirTerm.Lambda(List.of("a", "b"),
                new HirTerm.BuiltinCall(DefaultFun.AddInteger, List.of(
                        new HirTerm.Var("a", HirType.INTEGER),
                        new HirTerm.Var("b", HirType.INTEGER))));

        int vars = HirTreeWalker.count(tree,
                node -> node instanceof HirTerm.Var);
        assertEquals(2, vars);
    }

    @Test
    void walk_traversesFieldAccess() {
        var tree = new HirTerm.FieldAccess(
                new HirTerm.Var("ctx", HirType.DATA),
                "signatories", 7, "TxInfo");

        assertTrue(HirTreeWalker.anyMatch(tree,
                node -> node instanceof HirTerm.FieldAccess fa
                        && "signatories".equals(fa.fieldName())));
    }

    @Test
    void walk_traversesForEach() {
        var tree = new HirTerm.ForEach(
                "item",
                new HirTerm.Var("list", new HirType.ListType(HirType.INTEGER)),
                "acc",
                new HirTerm.IntLiteral(BigInteger.ZERO),
                new HirTerm.BuiltinCall(DefaultFun.AddInteger, List.of(
                        new HirTerm.Var("acc", HirType.INTEGER),
                        new HirTerm.Var("item", HirType.INTEGER))));

        int vars = HirTreeWalker.count(tree,
                node -> node instanceof HirTerm.Var);
        assertEquals(3, vars);
    }

    @Test
    void walk_traversesDataEncodeDecode() {
        var tree = new HirTerm.DataEncode(DefaultFun.IData,
                new HirTerm.DataDecode(DefaultFun.UnIData,
                        new HirTerm.Var("x", HirType.DATA)));

        int count = HirTreeWalker.count(tree, _ -> true);
        assertEquals(3, count);
    }

    @Test
    void walk_traversesTrace() {
        var tree = new HirTerm.Trace(
                new HirTerm.StringLiteral("debug"),
                new HirTerm.Error());

        assertTrue(HirTreeWalker.anyMatch(tree,
                node -> node instanceof HirTerm.StringLiteral));
        assertTrue(HirTreeWalker.anyMatch(tree,
                node -> node instanceof HirTerm.Error));
    }

    @Test
    void walk_traversesListLiteral() {
        var tree = new HirTerm.ListLiteral(List.of(
                new HirTerm.IntLiteral(BigInteger.ONE),
                new HirTerm.IntLiteral(BigInteger.TWO),
                new HirTerm.IntLiteral(BigInteger.valueOf(3))));

        int ints = HirTreeWalker.count(tree,
                node -> node instanceof HirTerm.IntLiteral);
        assertEquals(3, ints);
    }

    @Test
    void walk_traversesLetRec() {
        var tree = new HirTerm.LetRec("f",
                new HirTerm.Lambda(List.of("n"),
                        new HirTerm.If(
                                new HirTerm.BoolLiteral(true),
                                new HirTerm.IntLiteral(BigInteger.ZERO),
                                new HirTerm.FunCall("f", List.of(
                                        new HirTerm.Var("n", HirType.INTEGER))))),
                new HirTerm.FunCall("f", List.of(
                        new HirTerm.IntLiteral(BigInteger.TEN))));

        assertTrue(HirTreeWalker.anyMatch(tree,
                node -> node instanceof HirTerm.If));
        int funCalls = HirTreeWalker.count(tree,
                node -> node instanceof HirTerm.FunCall);
        assertEquals(2, funCalls);
    }

    @Test
    void walk_traversesConstructor() {
        var tree = new HirTerm.Constructor("Pair", 0, List.of(
                new HirTerm.IntLiteral(BigInteger.ONE),
                new HirTerm.StringLiteral("hello")));

        assertTrue(HirTreeWalker.anyMatch(tree,
                node -> node instanceof HirTerm.StringLiteral));
    }

    @Test
    void walk_traversesMethodCall() {
        var tree = new HirTerm.MethodCall(
                new HirTerm.Var("list", new HirType.ListType(HirType.INTEGER)),
                "head", List.of());

        assertTrue(HirTreeWalker.anyMatch(tree,
                node -> node instanceof HirTerm.Var v && "list".equals(v.name())));
    }
}
