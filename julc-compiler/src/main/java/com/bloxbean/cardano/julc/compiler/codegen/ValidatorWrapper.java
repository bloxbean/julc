package com.bloxbean.cardano.julc.compiler.codegen;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;

import java.math.BigInteger;

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
     * @param validateFn the user's validate function as PIR term
     * @param paramCount 2 for (redeemer, ctx), 3 for (datum, redeemer, ctx)
     */
    public PirTerm wrapSpendingValidator(PirTerm validateFn, int paramCount) {
        if (paramCount == 3) {
            return wrapSpending3Param(validateFn);
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
     * The wrapper unwraps the Optional: datum = HeadList(SndPair(UnConstrData(optDatum))).
     */
    private PirTerm wrapSpending3Param(PirTerm validateFn) {
        var ctxParam = "scriptContextData";
        var ctxFieldsVar = "ctxFields__";
        var redeemerVar = "redeemer__";
        var scriptInfoVar = "scriptInfo__";
        var scriptInfoFieldsVar = "scriptInfoFields__";
        var optDatumVar = "optDatum__";
        var datumVar = "datum__";
        var resultVar = "result__";

        var ctxVar = new PirTerm.Var(ctxParam, new PirType.DataType());

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
                                                // Unwrap Optional: HeadList(SndPair(UnConstrData(optDatum)))
                                                new PirTerm.Let(datumVar,
                                                        listIndex(sndPairUnConstrData(
                                                                new PirTerm.Var(optDatumVar, new PirType.DataType())), 0),
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
