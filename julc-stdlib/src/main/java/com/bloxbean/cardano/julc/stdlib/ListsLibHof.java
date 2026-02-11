package com.bloxbean.cardano.julc.stdlib;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;

import java.math.BigInteger;
import java.util.List;

/**
 * Higher-order function (HOF) list operations built as PIR term builders.
 * <p>
 * These methods require lambda parameters (PirTerm.Lam) or LetRec recursion,
 * which cannot be compiled from @OnchainLibrary Java source.
 * Non-HOF list operations (length, isEmpty, head, tail, reverse, concat, nth,
 * take, drop, contains) are compiled from Java source in onchain/ListsLib.java.
 */
public final class ListsLibHof {

    private ListsLibHof() {}

    /**
     * Returns true if the predicate holds for any element of the list.
     * <p>
     * Implemented as a left fold: foldl (\acc x -> if pred(x) then True else acc) False list
     */
    public static PirTerm any(PirTerm list, PirTerm predicate) {
        var accVar = new PirTerm.Var("acc", new PirType.BoolType());
        var xVar = new PirTerm.Var("x", new PirType.DataType());
        var predApp = new PirTerm.App(predicate, xVar);
        var body = new PirTerm.IfThenElse(
                predApp,
                new PirTerm.Const(Constant.bool(true)),
                accVar);
        var foldFn = new PirTerm.Lam("acc", new PirType.BoolType(),
                new PirTerm.Lam("x", new PirType.DataType(), body));
        return foldl(foldFn, new PirTerm.Const(Constant.bool(false)), list);
    }

    /**
     * Returns true if the predicate holds for all elements of the list.
     * <p>
     * Implemented as a left fold: foldl (\acc x -> if pred(x) then acc else False) True list
     */
    public static PirTerm all(PirTerm list, PirTerm predicate) {
        var accVar = new PirTerm.Var("acc", new PirType.BoolType());
        var xVar = new PirTerm.Var("x", new PirType.DataType());
        var predApp = new PirTerm.App(predicate, xVar);
        var body = new PirTerm.IfThenElse(
                predApp,
                accVar,
                new PirTerm.Const(Constant.bool(false)));
        var foldFn = new PirTerm.Lam("acc", new PirType.BoolType(),
                new PirTerm.Lam("x", new PirType.DataType(), body));
        return foldl(foldFn, new PirTerm.Const(Constant.bool(true)), list);
    }

    /**
     * Returns the first element matching the predicate as an Optional.
     * <p>
     * Returns Constr(0, [x]) (Some) if found, Constr(1, []) (None) if not found.
     * Implemented using LetRec recursion.
     */
    public static PirTerm find(PirTerm list, PirTerm predicate) {
        var lstVar = new PirTerm.Var("lst", new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var("go", new PirType.FunType(
                new PirType.ListType(new PirType.DataType()),
                new PirType.DataType()));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var none = new PirTerm.DataConstr(1, new PirType.OptionalType(new PirType.DataType()), List.of());

        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var hVar = new PirTerm.Var("h", new PirType.DataType());
        var predH = new PirTerm.App(predicate, hVar);
        var some = new PirTerm.DataConstr(0, new PirType.OptionalType(new PirType.DataType()), List.of(hVar));
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var recurse = new PirTerm.App(goVar, tailExpr);

        var innerIf = new PirTerm.IfThenElse(predH, some, recurse);
        var letHead = new PirTerm.Let("h", headExpr, innerIf);
        var outerIf = new PirTerm.IfThenElse(nullCheck, none, letHead);

        var goBody = new PirTerm.Lam("lst", new PirType.ListType(new PirType.DataType()), outerIf);
        var binding = new PirTerm.Binding("go", goBody);

        return new PirTerm.LetRec(
                List.of(binding),
                new PirTerm.App(goVar, list));
    }

    /**
     * Left fold over a list using LetRec recursion.
     * <p>
     * foldl(f, init, list) applies f to each element from left to right,
     * accumulating a result starting from init.
     */
    public static PirTerm foldl(PirTerm f, PirTerm init, PirTerm list) {
        var accVar = new PirTerm.Var("acc", new PirType.DataType());
        var lstVar = new PirTerm.Var("lst", new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var("go", new PirType.FunType(new PirType.DataType(),
                new PirType.FunType(new PirType.ListType(new PirType.DataType()), new PirType.DataType())));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);

        // f acc (HeadList lst)
        var fApp = new PirTerm.App(new PirTerm.App(f, accVar), headExpr);
        // go (f acc (HeadList lst)) (TailList lst)
        var recurse = new PirTerm.App(new PirTerm.App(goVar, fApp), tailExpr);

        var ifExpr = new PirTerm.IfThenElse(nullCheck, accVar, recurse);

        var goBody = new PirTerm.Lam("acc", new PirType.DataType(),
                new PirTerm.Lam("lst", new PirType.ListType(new PirType.DataType()), ifExpr));
        var binding = new PirTerm.Binding("go", goBody);

        return new PirTerm.LetRec(
                List.of(binding),
                new PirTerm.App(new PirTerm.App(goVar, init), list));
    }

    /**
     * Maps a function over a list, returning a new list.
     * <p>
     * Implemented as: reverse(foldl (\acc x -> MkCons(f(x), acc)) MkNilData list)
     */
    public static PirTerm map(PirTerm list, PirTerm f) {
        var accVar = new PirTerm.Var("acc_map", new PirType.ListType(new PirType.DataType()));
        var xVar = new PirTerm.Var("x_map", new PirType.DataType());
        var mapped = new PirTerm.App(f, xVar);
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), mapped),
                accVar);
        var foldFn = new PirTerm.Lam("acc_map", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("x_map", new PirType.DataType(), consExpr));
        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        return reverse(foldl(foldFn, emptyList, list));
    }

    /**
     * Filters a list, keeping only elements for which the predicate returns true.
     * <p>
     * Implemented as: reverse(foldl (\acc x -> if pred(x) then MkCons(x, acc) else acc) MkNilData list)
     */
    public static PirTerm filter(PirTerm list, PirTerm predicate) {
        var accVar = new PirTerm.Var("acc_flt", new PirType.ListType(new PirType.DataType()));
        var xVar = new PirTerm.Var("x_flt", new PirType.DataType());
        var predApp = new PirTerm.App(predicate, xVar);
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), xVar),
                accVar);
        var body = new PirTerm.IfThenElse(predApp, consExpr, accVar);
        var foldFn = new PirTerm.Lam("acc_flt", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("x_flt", new PirType.DataType(), body));
        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        return reverse(foldl(foldFn, emptyList, list));
    }

    /**
     * Zips two lists into a list of pairs.
     * <p>
     * Each pair is encoded as ConstrData(0, [elemA, elemB]).
     * Stops when either list is exhausted.
     */
    public static PirTerm zip(PirTerm a, PirTerm b) {
        var lstAVar = new PirTerm.Var("lstA_zip", new PirType.ListType(new PirType.DataType()));
        var lstBVar = new PirTerm.Var("lstB_zip", new PirType.ListType(new PirType.DataType()));
        var accVar = new PirTerm.Var("acc_zip", new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var("go_zip", new PirType.FunType(
                new PirType.ListType(new PirType.DataType()),
                new PirType.FunType(new PirType.ListType(new PirType.DataType()),
                        new PirType.FunType(new PirType.ListType(new PirType.DataType()),
                                new PirType.ListType(new PirType.DataType())))));

        var nullA = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstAVar);
        var nullB = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstBVar);
        var headA = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstAVar);
        var headB = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstBVar);
        var tailA = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstAVar);
        var tailB = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstBVar);

        var emptyFieldsList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var pairFields = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), headB),
                emptyFieldsList);
        pairFields = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), headA),
                pairFields);
        var pairData = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                        new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                pairFields);

        var consPair = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), pairData), accVar);
        var recurse = new PirTerm.App(new PirTerm.App(new PirTerm.App(goVar, tailA), tailB), consPair);

        var innerIf = new PirTerm.IfThenElse(nullB, accVar, recurse);
        var body = new PirTerm.IfThenElse(nullA, accVar, innerIf);

        var goBody = new PirTerm.Lam("lstA_zip", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("lstB_zip", new PirType.ListType(new PirType.DataType()),
                        new PirTerm.Lam("acc_zip", new PirType.ListType(new PirType.DataType()), body)));
        var binding = new PirTerm.Binding("go_zip", goBody);

        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var collected = new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(new PirTerm.App(new PirTerm.App(goVar, a), b), emptyList));
        return reverse(collected);
    }

    // =========================================================================
    // Internal helper (used by map, filter, zip)
    // =========================================================================

    /**
     * Reverses a list (PIR builder version).
     * <p>
     * Implemented as: foldl (\acc x -> MkCons(x, acc)) MkNilData list.
     * Used internally by map, filter, zip which need to reverse accumulated results.
     */
    static PirTerm reverse(PirTerm list) {
        var accVar = new PirTerm.Var("acc_rev", new PirType.ListType(new PirType.DataType()));
        var xVar = new PirTerm.Var("x_rev", new PirType.DataType());
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), xVar),
                accVar);
        var foldFn = new PirTerm.Lam("acc_rev", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("x_rev", new PirType.DataType(), consExpr));
        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        return foldl(foldFn, emptyList, list);
    }
}
