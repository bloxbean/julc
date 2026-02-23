package com.bloxbean.cardano.julc.compiler.codegen;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;

import java.math.BigInteger;
import java.util.Map;

/**
 * Generates the validator wrapper that decodes ScriptContext and calls the user's validate method.
 * <p>
 * V3 validators receive a single argument: ScriptContext as Data.
 * <p>
 * Spending wrapper structure (2-param):
 * <pre>
 * \scriptContextData ->
 *   let ctxFields = SndPair(UnConstrData(scriptContextData))
 *   let redeemerData = HeadList(TailList(ctxFields))
 *   let result = validate(redeemerData, scriptContextData)
 *   in IfThenElse(result, Unit, Error)
 * </pre>
 * <p>
 * Spending wrapper structure (3-param with datum):
 * <pre>
 * \scriptContextData ->
 *   let ctxFields = SndPair(UnConstrData(scriptContextData))
 *   let redeemerData = HeadList(TailList(ctxFields))
 *   let scriptInfoData = HeadList(TailList(TailList(ctxFields)))
 *   let scriptInfoFields = SndPair(UnConstrData(scriptInfoData))
 *   let optDatumData = HeadList(TailList(scriptInfoFields))
 *   let result = validate(optDatumData, redeemerData, scriptContextData)
 *   in IfThenElse(result, Unit, Error)
 * </pre>
 */
public class ValidatorWrapper {

    /**
     * Wrap a spending validator. Detects 2-param or 3-param based on paramCount.
     *
     * @param validateFn      the user's validate function as PIR term
     * @param paramCount      2 for (redeemer, ctx), 3 for (datum, redeemer, ctx)
     * @param datumIsOptional true if the datum parameter is Optional (skip force-unwrap)
     */
    public PirTerm wrapSpendingValidator(PirTerm validateFn, int paramCount, boolean datumIsOptional) {
        if (paramCount == 3) {
            return wrapSpending3Param(validateFn, datumIsOptional);
        }
        return wrapSpending2Param(validateFn);
    }

    /**
     * Wrap a spending validator (2-param backward compat).
     */
    public PirTerm wrapSpendingValidator(PirTerm validateFn) {
        return wrapSpending2Param(validateFn);
    }

    /**
     * 2-param spending: validate(redeemer, scriptContext) -> bool
     */
    private PirTerm wrapSpending2Param(PirTerm validateFn) {
        var ctxParam = "scriptContextData";
        var ctxFieldsVar = "ctxFields__";
        var redeemerVar = "redeemer__";
        var resultVar = "result__";

        var ctxVar = new PirTerm.Var(ctxParam, new PirType.DataType());

        var body = new PirTerm.Let(ctxFieldsVar,
                sndPairUnConstrData(ctxVar),
                new PirTerm.Let(redeemerVar,
                        listIndex(new PirTerm.Var(ctxFieldsVar, new PirType.DataType()), 1),
                        new PirTerm.Let(resultVar,
                                new PirTerm.App(
                                        new PirTerm.App(validateFn,
                                                new PirTerm.Var(redeemerVar, new PirType.DataType())),
                                        ctxVar),
                                new PirTerm.IfThenElse(
                                        new PirTerm.Var(resultVar, new PirType.BoolType()),
                                        new PirTerm.Const(Constant.unit()),
                                        new PirTerm.Error(new PirType.UnitType())))));

        return new PirTerm.Lam(ctxParam, new PirType.DataType(), body);
    }

    /**
     * 3-param spending: validate(datum, redeemer, scriptContext) -> bool
     * <p>
     * Extracts datum from ScriptInfo (field 2 of ScriptContext).
     * Spending ScriptInfo = Constr(1, [txOutRef, optionalDatum]).
     * optionalDatum is Constr(0,[datum]) for Some or Constr(1,[]) for None.
     * <p>
     * If datumIsOptional is false (default): force-unwraps the Optional via
     * HeadList(SndPair(UnConstrData(optDatum))). Crashes if datum is None.
     * <p>
     * If datumIsOptional is true: passes the raw Optional ConstrData to the user
     * function. The user can call .isPresent()/.get() to safely handle None.
     */
    private PirTerm wrapSpending3Param(PirTerm validateFn, boolean datumIsOptional) {
        var ctxParam = "scriptContextData";
        var ctxFieldsVar = "ctxFields__";
        var redeemerVar = "redeemer__";
        var scriptInfoVar = "scriptInfo__";
        var scriptInfoFieldsVar = "scriptInfoFields__";
        var optDatumVar = "optDatum__";
        var datumVar = "datum__";
        var resultVar = "result__";

        var ctxVar = new PirTerm.Var(ctxParam, new PirType.DataType());

        // Datum binding: either pass raw Optional or force-unwrap
        PirTerm datumBinding;
        if (datumIsOptional) {
            // Pass raw Optional ConstrData — user calls .isPresent()/.get()
            datumBinding = new PirTerm.Var(optDatumVar, new PirType.DataType());
        } else {
            // Force-unwrap Optional: HeadList(SndPair(UnConstrData(optDatum)))
            datumBinding = listIndex(sndPairUnConstrData(
                    new PirTerm.Var(optDatumVar, new PirType.DataType())), 0);
        }

        var body = new PirTerm.Let(ctxFieldsVar,
                sndPairUnConstrData(ctxVar),
                new PirTerm.Let(redeemerVar,
                        listIndex(new PirTerm.Var(ctxFieldsVar, new PirType.DataType()), 1),
                        new PirTerm.Let(scriptInfoVar,
                                listIndex(new PirTerm.Var(ctxFieldsVar, new PirType.DataType()), 2),
                                new PirTerm.Let(scriptInfoFieldsVar,
                                        sndPairUnConstrData(new PirTerm.Var(scriptInfoVar, new PirType.DataType())),
                                        new PirTerm.Let(optDatumVar,
                                                listIndex(new PirTerm.Var(scriptInfoFieldsVar, new PirType.DataType()), 1),
                                                new PirTerm.Let(datumVar,
                                                        datumBinding,
                                                        new PirTerm.Let(resultVar,
                                                                new PirTerm.App(
                                                                        new PirTerm.App(
                                                                                new PirTerm.App(validateFn,
                                                                                        new PirTerm.Var(datumVar, new PirType.DataType())),
                                                                                new PirTerm.Var(redeemerVar, new PirType.DataType())),
                                                                        ctxVar),
                                                                new PirTerm.IfThenElse(
                                                                        new PirTerm.Var(resultVar, new PirType.BoolType()),
                                                                        new PirTerm.Const(Constant.unit()),
                                                                        new PirTerm.Error(new PirType.UnitType())))))))));

        return new PirTerm.Lam(ctxParam, new PirType.DataType(), body);
    }

    /**
     * Wrap a minting policy. The user function takes (redeemer, scriptContext) -> bool.
     */
    public PirTerm wrapMintingPolicy(PirTerm validateFn) {
        // Minting always uses 2-param
        return wrapSpending2Param(validateFn);
    }

    /**
     * Wrap a multi-validator with auto-dispatch based on ScriptInfo tag.
     * <p>
     * Generated structure:
     * <pre>
     * \scriptContextData ->
     *   let ctxFields = SndPair(UnConstrData(scriptContextData))
     *   let redeemer = HeadList(TailList(ctxFields))
     *   let scriptInfo = HeadList(TailList(TailList(ctxFields)))
     *   let scriptInfoPair = UnConstrData(scriptInfo)
     *   let tag = FstPair(scriptInfoPair)
     *   in
     *     IfThenElse(EqualsInteger(tag, T1), handler1_call,
     *     IfThenElse(EqualsInteger(tag, T2), handler2_call,
     *     Error))
     * </pre>
     *
     * @param handlers           map of ScriptInfo tag → handler PIR term
     * @param paramCounts        map of ScriptInfo tag → parameter count (2 or 3; 3 only for SPEND)
     * @param datumOptionalFlags map of ScriptInfo tag → whether datum param is Optional
     */
    public PirTerm wrapMultiValidator(Map<Integer, PirTerm> handlers, Map<Integer, Integer> paramCounts,
                                       Map<Integer, Boolean> datumOptionalFlags) {
        var ctxParam = "scriptContextData";
        var ctxFieldsVar = "ctxFields__";
        var redeemerVar = "redeemer__";
        var scriptInfoVar = "scriptInfo__";
        var scriptInfoPairVar = "scriptInfoPair__";
        var tagVar = "tag__";

        var ctxVar = new PirTerm.Var(ctxParam, new PirType.DataType());

        // Build dispatch chain from handlers (sorted by tag for deterministic output)
        var sortedTags = handlers.keySet().stream().sorted().toList();

        // Start with Error as the fallback
        PirTerm dispatch = new PirTerm.Error(new PirType.UnitType());

        // Build chain in reverse order so first tag is outermost
        for (int i = sortedTags.size() - 1; i >= 0; i--) {
            int tag = sortedTags.get(i);
            var handler = handlers.get(tag);
            int paramCount = paramCounts.getOrDefault(tag, 2);

            boolean datumOpt = datumOptionalFlags.getOrDefault(tag, false);
            PirTerm handlerCall = buildHandlerCall(handler, tag, paramCount, datumOpt,
                    redeemerVar, scriptInfoPairVar, ctxVar);

            dispatch = new PirTerm.IfThenElse(
                    equalsInteger(new PirTerm.Var(tagVar, new PirType.IntegerType()), tag),
                    handlerCall,
                    dispatch);
        }

        // Wrap with common preamble
        var body = new PirTerm.Let(ctxFieldsVar,
                sndPairUnConstrData(ctxVar),
                new PirTerm.Let(redeemerVar,
                        listIndex(new PirTerm.Var(ctxFieldsVar, new PirType.DataType()), 1),
                        new PirTerm.Let(scriptInfoVar,
                                listIndex(new PirTerm.Var(ctxFieldsVar, new PirType.DataType()), 2),
                                new PirTerm.Let(scriptInfoPairVar,
                                        builtinApp1(DefaultFun.UnConstrData,
                                                new PirTerm.Var(scriptInfoVar, new PirType.DataType())),
                                        new PirTerm.Let(tagVar,
                                                builtinApp1(DefaultFun.FstPair,
                                                        new PirTerm.Var(scriptInfoPairVar, new PirType.DataType())),
                                                dispatch)))));

        return new PirTerm.Lam(ctxParam, new PirType.DataType(), body);
    }

    /**
     * Build the handler invocation for a specific purpose tag.
     * For SPEND (tag 1) with 3 params: extracts datum from ScriptInfo fields.
     * If datumIsOptional, passes raw Optional ConstrData; otherwise force-unwraps.
     * For all others: calls handler(redeemer, ctx).
     */
    private PirTerm buildHandlerCall(PirTerm handler, int tag, int paramCount, boolean datumIsOptional,
                                      String redeemerVar, String scriptInfoPairVar, PirTerm ctxVar) {
        PirTerm callExpr;
        if (tag == 1 && paramCount == 3) {
            // SPEND with datum: extract from ScriptInfo fields
            var siFieldsVar = "siFields__spend";
            var optDatumVar = "optDatum__spend";
            var datumVar = "datum__spend";
            var resultVar = "result__spend";

            // Datum binding: either pass raw Optional or force-unwrap
            PirTerm datumBinding;
            if (datumIsOptional) {
                datumBinding = new PirTerm.Var(optDatumVar, new PirType.DataType());
            } else {
                datumBinding = listIndex(sndPairUnConstrData(
                        new PirTerm.Var(optDatumVar, new PirType.DataType())), 0);
            }

            callExpr = new PirTerm.Let(siFieldsVar,
                    builtinApp1(DefaultFun.SndPair,
                            new PirTerm.Var(scriptInfoPairVar, new PirType.DataType())),
                    new PirTerm.Let(optDatumVar,
                            listIndex(new PirTerm.Var(siFieldsVar, new PirType.DataType()), 1),
                            new PirTerm.Let(datumVar,
                                    datumBinding,
                                    new PirTerm.Let(resultVar,
                                            new PirTerm.App(
                                                    new PirTerm.App(
                                                            new PirTerm.App(handler,
                                                                    new PirTerm.Var(datumVar, new PirType.DataType())),
                                                            new PirTerm.Var(redeemerVar, new PirType.DataType())),
                                                    ctxVar),
                                            new PirTerm.IfThenElse(
                                                    new PirTerm.Var(resultVar, new PirType.BoolType()),
                                                    new PirTerm.Const(Constant.unit()),
                                                    new PirTerm.Error(new PirType.UnitType()))))));
        } else {
            // All 2-param handlers: handler(redeemer, ctx)
            var resultVar = "result__tag" + tag;
            callExpr = new PirTerm.Let(resultVar,
                    new PirTerm.App(
                            new PirTerm.App(handler,
                                    new PirTerm.Var(redeemerVar, new PirType.DataType())),
                            ctxVar),
                    new PirTerm.IfThenElse(
                            new PirTerm.Var(resultVar, new PirType.BoolType()),
                            new PirTerm.Const(Constant.unit()),
                            new PirTerm.Error(new PirType.UnitType())));
        }
        return callExpr;
    }

    private static PirTerm equalsInteger(PirTerm a, int value) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), a),
                new PirTerm.Const(new Constant.IntegerConst(BigInteger.valueOf(value))));
    }

    // --- Helpers ---

    private static PirTerm builtinApp1(DefaultFun fun, PirTerm arg) {
        return new PirTerm.App(new PirTerm.Builtin(fun), arg);
    }

    private static PirTerm sndPairUnConstrData(PirTerm term) {
        return builtinApp1(DefaultFun.SndPair,
                builtinApp1(DefaultFun.UnConstrData, term));
    }

    private static PirTerm listIndex(PirTerm list, int index) {
        PirTerm current = list;
        for (int i = 0; i < index; i++) {
            current = builtinApp1(DefaultFun.TailList, current);
        }
        return builtinApp1(DefaultFun.HeadList, current);
    }
}
