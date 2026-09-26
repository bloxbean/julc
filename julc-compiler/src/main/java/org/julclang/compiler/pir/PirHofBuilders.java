package org.julclang.compiler.pir;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import java.math.BigInteger;
import java.util.List;

/**
 * Pure PIR construction methods for higher-order function (HOF) list operations.
 * <p>
 * These builders generate PIR terms for map, filter, any, all, find, foldl, reverse, and zip.
 * They are used by both:
 * <ul>
 *   <li>{@code StdlibRegistry} (via {@code ListsLibHof}) for static calls like {@code ListsLib.map(list, fn)}</li>
 *   <li>{@code TypeMethodRegistry} for instance calls like {@code list.map(fn)}</li>
 * </ul>
 * <p>
 * This class lives in {@code julc-compiler} so that {@code TypeMethodRegistry} can reference it
 * without a dependency on {@code julc-stdlib}.
 * <p>
 * Caller terms (lists, predicates, functions, initial values) are placed inside the builders'
 * own binders. Every such binder is named with {@link PirHelpers#hygienicName} against the
 * free variables of the caller terms in its scope, so a user variable such as an outer lambda
 * parameter {@code x} is never captured by a builder binder of the same name.
 */
public final class PirHofBuilders {

    private PirHofBuilders() {}

    /**
     * Returns true if the predicate holds for any element of the list.
     * <p>
     * Implemented as a left fold: foldl (\acc x -> if pred(x) then True else acc) False list
     */
    public static PirTerm any(PirTerm list, PirTerm predicate) {
        var avoid = PirHelpers.freeVariables(predicate);
        var accVar = new PirTerm.Var(PirHelpers.hygienicName("#acc", avoid), new PirType.BoolType());
        var xVar = new PirTerm.Var(PirHelpers.hygienicName("#x", avoid), new PirType.DataType());
        var predApp = new PirTerm.App(predicate, xVar);
        var body = new PirTerm.IfThenElse(
                predApp,
                new PirTerm.Const(Constant.bool(true)),
                accVar);
        var foldFn = new PirTerm.Lam(accVar.name(), new PirType.BoolType(),
                new PirTerm.Lam(xVar.name(), new PirType.DataType(), body));
        return foldl(foldFn, new PirTerm.Const(Constant.bool(false)), list);
    }

    /**
     * Returns true if the predicate holds for all elements of the list.
     * <p>
     * Implemented as a left fold: foldl (\acc x -> if pred(x) then acc else False) True list
     */
    public static PirTerm all(PirTerm list, PirTerm predicate) {
        var avoid = PirHelpers.freeVariables(predicate);
        var accVar = new PirTerm.Var(PirHelpers.hygienicName("#acc", avoid), new PirType.BoolType());
        var xVar = new PirTerm.Var(PirHelpers.hygienicName("#x", avoid), new PirType.DataType());
        var predApp = new PirTerm.App(predicate, xVar);
        var body = new PirTerm.IfThenElse(
                predApp,
                accVar,
                new PirTerm.Const(Constant.bool(false)));
        var foldFn = new PirTerm.Lam(accVar.name(), new PirType.BoolType(),
                new PirTerm.Lam(xVar.name(), new PirType.DataType(), body));
        return foldl(foldFn, new PirTerm.Const(Constant.bool(true)), list);
    }

    /**
     * Returns the first element matching the predicate as an Optional.
     * <p>
     * Returns Constr(0, [x]) (Some) if found, Constr(1, []) (None) if not found.
     * Implemented using LetRec recursion.
     */
    public static PirTerm find(PirTerm list, PirTerm predicate) {
        // The predicate runs under go, lst and h; the list is applied under go.
        var avoid = PirHelpers.freeVariables(list, predicate);
        var lstVar = new PirTerm.Var(PirHelpers.hygienicName("#lst", avoid), new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var(PirHelpers.hygienicName("#go", avoid), new PirType.FunType(
                new PirType.ListType(new PirType.DataType()),
                new PirType.DataType()));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var none = new PirTerm.DataConstr(1, new PirType.OptionalType(new PirType.DataType()), List.of());

        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var hVar = new PirTerm.Var(PirHelpers.hygienicName("#h", avoid), new PirType.DataType());
        var predH = new PirTerm.App(predicate, hVar);
        var some = new PirTerm.DataConstr(0, new PirType.OptionalType(new PirType.DataType()), List.of(hVar));
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var recurse = new PirTerm.App(goVar, tailExpr);

        var innerIf = new PirTerm.IfThenElse(predH, some, recurse);
        var letHead = new PirTerm.Let(hVar.name(), headExpr, innerIf);
        var outerIf = new PirTerm.IfThenElse(nullCheck, none, letHead);

        var goBody = new PirTerm.Lam(lstVar.name(), new PirType.ListType(new PirType.DataType()), outerIf);
        var binding = new PirTerm.Binding(goVar.name(), goBody);

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
        // f is applied under go, acc and lst; init and list are applied under go.
        var avoid = PirHelpers.freeVariables(f, init, list);
        var accVar = new PirTerm.Var(PirHelpers.hygienicName("#acc", avoid), new PirType.DataType());
        var lstVar = new PirTerm.Var(PirHelpers.hygienicName("#lst", avoid), new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var(PirHelpers.hygienicName("#go", avoid), new PirType.FunType(new PirType.DataType(),
                new PirType.FunType(new PirType.ListType(new PirType.DataType()), new PirType.DataType())));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);

        // f acc (HeadList lst)
        var fApp = new PirTerm.App(new PirTerm.App(f, accVar), headExpr);
        // go (f acc (HeadList lst)) (TailList lst)
        var recurse = new PirTerm.App(new PirTerm.App(goVar, fApp), tailExpr);

        var ifExpr = new PirTerm.IfThenElse(nullCheck, accVar, recurse);

        var goBody = new PirTerm.Lam(accVar.name(), new PirType.DataType(),
                new PirTerm.Lam(lstVar.name(), new PirType.ListType(new PirType.DataType()), ifExpr));
        var binding = new PirTerm.Binding(goVar.name(), goBody);

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
        var avoid = PirHelpers.freeVariables(f);
        var accVar = new PirTerm.Var(PirHelpers.hygienicName("#acc_map", avoid), new PirType.ListType(new PirType.DataType()));
        var xVar = new PirTerm.Var(PirHelpers.hygienicName("#x_map", avoid), new PirType.DataType());
        var mapped = new PirTerm.App(f, xVar);
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), mapped),
                accVar);
        var foldFn = new PirTerm.Lam(accVar.name(), new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam(xVar.name(), new PirType.DataType(), consExpr));
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
        var avoid = PirHelpers.freeVariables(predicate);
        var accVar = new PirTerm.Var(PirHelpers.hygienicName("#acc_flt", avoid), new PirType.ListType(new PirType.DataType()));
        var xVar = new PirTerm.Var(PirHelpers.hygienicName("#x_flt", avoid), new PirType.DataType());
        var predApp = new PirTerm.App(predicate, xVar);
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), xVar),
                accVar);
        var body = new PirTerm.IfThenElse(predApp, consExpr, accVar);
        var foldFn = new PirTerm.Lam(accVar.name(), new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam(xVar.name(), new PirType.DataType(), body));
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
        // Both lists are applied under go_zip.
        var avoid = PirHelpers.freeVariables(a, b);
        var lstAVar = new PirTerm.Var("#lstA_zip", new PirType.ListType(new PirType.DataType()));
        var lstBVar = new PirTerm.Var("#lstB_zip", new PirType.ListType(new PirType.DataType()));
        var accVar = new PirTerm.Var("#acc_zip", new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var(PirHelpers.hygienicName("#go_zip", avoid), new PirType.FunType(
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

        var goBody = new PirTerm.Lam("#lstA_zip", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("#lstB_zip", new PirType.ListType(new PirType.DataType()),
                        new PirTerm.Lam("#acc_zip", new PirType.ListType(new PirType.DataType()), body)));
        var binding = new PirTerm.Binding(goVar.name(), goBody);

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
        var accVar = new PirTerm.Var("#acc_rev", new PirType.ListType(new PirType.DataType()));
        var xVar = new PirTerm.Var("#x_rev", new PirType.DataType());
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), xVar),
                accVar);
        var foldFn = new PirTerm.Lam("#acc_rev", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("#x_rev", new PirType.DataType(), consExpr));
        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        return foldl(foldFn, emptyList, list);
    }
}
