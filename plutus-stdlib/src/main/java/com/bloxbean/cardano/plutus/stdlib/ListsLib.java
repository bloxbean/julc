package com.bloxbean.cardano.plutus.stdlib;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;

import java.math.BigInteger;
import java.util.List;

/**
 * List operations built as PIR term builders.
 * <p>
 * Each method returns a {@link PirTerm} that can be plugged into the compiler output.
 * Lists are represented as Plutus builtin lists (BuiltinList).
 */
public final class ListsLib {

    private ListsLib() {}

    /**
     * Returns true if the predicate holds for any element of the list.
     * <p>
     * Implemented as a left fold: foldl (\acc x -> if pred(x) then True else acc) False list
     *
     * @param list      PIR term representing a builtin list
     * @param predicate PIR term representing a function from element to Bool
     * @return PIR term that evaluates to Bool
     */
    public static PirTerm any(PirTerm list, PirTerm predicate) {
        // any(list, pred) = foldl (\acc x -> IfThenElse(pred(x), True, acc)) False list
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
     *
     * @param list      PIR term representing a builtin list
     * @param predicate PIR term representing a function from element to Bool
     * @return PIR term that evaluates to Bool
     */
    public static PirTerm all(PirTerm list, PirTerm predicate) {
        // all(list, pred) = foldl (\acc x -> IfThenElse(pred(x), acc, False)) True list
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
     *
     * @param list      PIR term representing a builtin list
     * @param predicate PIR term representing a function from element to Bool
     * @return PIR term that evaluates to Optional (Constr-encoded)
     */
    public static PirTerm find(PirTerm list, PirTerm predicate) {
        // find(list, pred) =
        //   letrec go = \lst ->
        //     IfThenElse(NullList(lst),
        //       Constr(1, []),                         -- None
        //       let h = HeadList(lst) in
        //         IfThenElse(pred(h),
        //           Constr(0, [h]),                    -- Some(h)
        //           go(TailList(lst))))
        //   in go(list)

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
     *
     * @param f    PIR term: function (acc, elem) -> acc
     * @param init PIR term: initial accumulator value
     * @param list PIR term: the list to fold over
     * @return PIR term that evaluates to the accumulated result
     */
    public static PirTerm foldl(PirTerm f, PirTerm init, PirTerm list) {
        // foldl(f, init, list) =
        //   letrec go = \acc -> \lst ->
        //     IfThenElse(NullList(lst),
        //       acc,
        //       go (f acc (HeadList lst)) (TailList lst))
        //   in go init list

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
     * Returns the length of a list as an integer.
     * <p>
     * Implemented as foldl (\acc _ -> acc + 1) 0 list.
     *
     * @param list PIR term representing a builtin list
     * @return PIR term that evaluates to Integer
     */
    public static PirTerm length(PirTerm list) {
        // length(list) = foldl (\acc _ -> AddInteger acc 1) 0 list
        var accVar = new PirTerm.Var("acc", new PirType.IntegerType());
        var xVar = new PirTerm.Var("_x", new PirType.DataType());
        var addOne = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger), accVar),
                new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        var foldFn = new PirTerm.Lam("acc", new PirType.IntegerType(),
                new PirTerm.Lam("_x", new PirType.DataType(), addOne));
        return foldl(foldFn, new PirTerm.Const(Constant.integer(BigInteger.ZERO)), list);
    }

    /**
     * Returns true if the list is empty.
     * <p>
     * Equivalent to NullList(list).
     *
     * @param list PIR term representing a builtin list
     * @return PIR term that evaluates to Bool
     */
    public static PirTerm isEmpty(PirTerm list) {
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), list);
    }
}
