package com.bloxbean.cardano.plutus.compiler.desugar;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Transforms loops into PIR recursive terms.
 *
 * For-each desugaring (accumulator fold pattern):
 *   for (var item : items) { acc = f(acc, item); }
 *   → LetRec(["loop" = \xs \acc -> IfThenElse(NullList(xs), acc, loop(TailList(xs), f(acc, HeadList(xs))))],
 *            loop(items, initAcc))
 *
 * While desugaring:
 *   while (cond) { body; }
 *   → LetRec(["loop" = \_ -> IfThenElse(cond, Let("_", body, loop(Unit)), Unit)], loop(Unit))
 */
public class LoopDesugarer {

    /**
     * Desugar a for-each loop with an accumulator pattern.
     *
     * @param iterableExpr the list being iterated over
     * @param itemName     the loop variable name
     * @param accName      the accumulator variable name
     * @param accInit      the initial accumulator value
     * @param accType      the accumulator type
     * @param loopBody     the body that computes new acc from (acc, item)
     * @return LetRec term that folds over the list
     */
    public PirTerm desugarForEach(PirTerm iterableExpr, String itemName, String accName,
                                   PirTerm accInit, PirType accType, PirTerm loopBody) {
        var listType = new PirType.ListType(new PirType.DataType());
        var loopName = "loop__forEach";
        var xsName = "xs__";

        // loop = \xs \acc -> IfThenElse(NullList(xs), acc, loop(TailList(xs), body))
        // where body substitutes item = HeadList(xs)
        var xsVar = new PirTerm.Var(xsName, listType);
        var accVar = new PirTerm.Var(accName, accType);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), xsVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), xsVar);
        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), xsVar);

        // Bind item = HeadList(xs), then evaluate body to get new acc
        var bodyWithItem = new PirTerm.Let(itemName, headExpr, loopBody);

        // Recursive call: loop(TailList(xs), newAcc)
        var recursiveCall = new PirTerm.App(
                new PirTerm.App(
                        new PirTerm.Var(loopName, new PirType.FunType(listType, new PirType.FunType(accType, accType))),
                        tailExpr),
                bodyWithItem);

        // if NullList(xs) then acc else loop(TailList(xs), body)
        var loopLambda = new PirTerm.Lam(xsName, listType,
                new PirTerm.Lam(accName, accType,
                        new PirTerm.IfThenElse(nullCheck, accVar, recursiveCall)));

        // Initial call: loop(items, accInit)
        var initialCall = new PirTerm.App(
                new PirTerm.App(
                        new PirTerm.Var(loopName, new PirType.FunType(listType, new PirType.FunType(accType, accType))),
                        iterableExpr),
                accInit);

        return new PirTerm.LetRec(
                List.of(new PirTerm.Binding(loopName, loopLambda)),
                initialCall);
    }

    /**
     * Desugar a for-each loop with break support.
     * The bodyBuilder receives (loopRef applied to tailExpr, accVar) and must construct
     * a term that either returns accVal directly (break) or calls loopRef(tailExpr, newAcc) (continue).
     *
     * Generated structure:
     *   LetRec([loop = \xs \acc ->
     *     IfThenElse(NullList(xs), acc,
     *       Let(item, HeadList(xs), bodyBuilder(loop(TailList(xs)), acc)))
     *   ], loop(items, accInit))
     *
     * @param iterableExpr the list being iterated over
     * @param itemName     the loop variable name
     * @param accName      the accumulator variable name
     * @param accInit      the initial accumulator value
     * @param accType      the accumulator type
     * @param bodyBuilder  (continueFn, accVar) → term. continueFn accepts a newAcc and returns loop(tail, newAcc).
     *                     To break: return newAcc directly. To continue: return continueFn.apply(newAcc).
     * @return LetRec term that folds over the list with break support
     */
    public PirTerm desugarForEachWithBreak(PirTerm iterableExpr, String itemName, String accName,
                                            PirTerm accInit, PirType accType,
                                            BiFunction<java.util.function.Function<PirTerm, PirTerm>, PirTerm, PirTerm> bodyBuilder) {
        var listType = new PirType.ListType(new PirType.DataType());
        var loopName = "loop__forEach";
        var xsName = "xs__";

        var xsVar = new PirTerm.Var(xsName, listType);
        var accVar = new PirTerm.Var(accName, accType);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), xsVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), xsVar);
        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), xsVar);

        var loopFunType = new PirType.FunType(listType, new PirType.FunType(accType, accType));

        // continueFn: given a newAcc, produces loop(TailList(xs), newAcc)
        java.util.function.Function<PirTerm, PirTerm> continueFn = newAcc ->
                new PirTerm.App(
                        new PirTerm.App(new PirTerm.Var(loopName, loopFunType), tailExpr),
                        newAcc);

        // Build body: Let(item, HeadList(xs), bodyBuilder(continueFn, accVar))
        var bodyTerm = bodyBuilder.apply(continueFn, accVar);
        var bodyWithItem = new PirTerm.Let(itemName, headExpr, bodyTerm);

        // loop = \xs \acc -> IfThenElse(NullList(xs), acc, bodyWithItem)
        var loopLambda = new PirTerm.Lam(xsName, listType,
                new PirTerm.Lam(accName, accType,
                        new PirTerm.IfThenElse(nullCheck, accVar, bodyWithItem)));

        // Initial call: loop(items, accInit)
        var initialCall = new PirTerm.App(
                new PirTerm.App(new PirTerm.Var(loopName, loopFunType), iterableExpr),
                accInit);

        return new PirTerm.LetRec(
                List.of(new PirTerm.Binding(loopName, loopLambda)),
                initialCall);
    }

    /**
     * Desugar a while loop.
     *
     * @param condition the loop condition
     * @param body      the loop body
     * @return LetRec term that loops until condition is false
     */
    public PirTerm desugarWhile(PirTerm condition, PirTerm body) {
        var unitType = new PirType.UnitType();
        var loopName = "loop__while";

        // loop = \_ -> IfThenElse(cond, Let("_", body, loop(Unit)), Unit)
        var recursiveCall = new PirTerm.App(
                new PirTerm.Var(loopName, new PirType.FunType(unitType, unitType)),
                new PirTerm.Const(Constant.unit()));

        var bodyThenContinue = new PirTerm.Let("_body", body, recursiveCall);

        var loopLambda = new PirTerm.Lam("_u", unitType,
                new PirTerm.IfThenElse(condition, bodyThenContinue, new PirTerm.Const(Constant.unit())));

        var initialCall = new PirTerm.App(
                new PirTerm.Var(loopName, new PirType.FunType(unitType, unitType)),
                new PirTerm.Const(Constant.unit()));

        return new PirTerm.LetRec(
                List.of(new PirTerm.Binding(loopName, loopLambda)),
                initialCall);
    }
}
