package com.bloxbean.cardano.plutus.stdlib;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;

import java.math.BigInteger;
import java.util.List;

/**
 * Script context operations built as PIR term builders.
 * <p>
 * Provides helpers to navigate the V3 ScriptContext/TxInfo structure:
 * <pre>
 * ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
 * TxInfo = Constr(0, [inputs(0), refInputs(1), outputs(2), fee(3), mint(4),
 *   certs(5), withdrawals(6), validRange(7), signatories(8), redeemers(9),
 *   datums(10), txId(11), votes(12), proposalProcs(13), treasury(14), donation(15)])
 * </pre>
 */
public final class ContextsLib {

    private ContextsLib() {}

    /**
     * Checks whether a given PubKeyHash is in the signatories list of the TxInfo.
     * <p>
     * signedBy(txInfo, pkh) extracts txInfoSignatories (field 8 of TxInfo)
     * and checks if pkh appears in the list using a recursive search.
     * <p>
     * In Cardano V3, PubKeyHash in signatories is encoded as raw BytesData(hash),
     * NOT Constr-wrapped. The comparison uses EqualsByteString on UnBData of each element.
     *
     * @param txInfo PIR term representing a TxInfo (as Data)
     * @param pkh    PIR term representing a PubKeyHash (as Data: BData(bs))
     * @return PIR term that evaluates to Bool
     */
    public static PirTerm signedBy(PirTerm txInfo, PirTerm pkh) {
        // 1. Extract signatories list: field 8 of TxInfo, then UnListData to get builtin list
        // 2. Extract the bytestring from pkh: UnBData(pkh) directly (raw BytesData)
        // 3. Recurse through the signatories list checking EqualsByteString

        var txInfoVar = new PirTerm.Var("txInfo_sb", new PirType.DataType());
        var pkhVar = new PirTerm.Var("pkh_sb", new PirType.DataType());

        // Extract signatories (index 8 of TxInfo fields) then UnListData to get builtin list
        var fieldsExpr = sndPairUnconstData(txInfoVar);
        var sigsDataExpr = listIndex(fieldsExpr, 8);
        var sigsExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), sigsDataExpr);

        // Extract pkh bytestring directly: UnBData(pkh)
        var pkhBs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), pkhVar);

        // Build recursive search using ListsLib.any pattern
        var sigListVar = new PirTerm.Var("sigList_sb", new PirType.ListType(new PirType.DataType()));
        var targetVar = new PirTerm.Var("target_sb", new PirType.ByteStringType());
        var goVar = new PirTerm.Var("go_sb", new PirType.FunType(
                new PirType.ListType(new PirType.DataType()), new PirType.BoolType()));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), sigListVar);
        var headElem = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), sigListVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), sigListVar);

        // Extract bytestring from each signatory: UnBData(elem) directly (raw BytesData)
        var headBs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), headElem);

        var equalCheck = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsByteString), targetVar),
                headBs);

        var recurse = new PirTerm.App(goVar, tailExpr);
        var ifFound = new PirTerm.IfThenElse(equalCheck,
                new PirTerm.Const(Constant.bool(true)),
                recurse);
        var body = new PirTerm.IfThenElse(nullCheck,
                new PirTerm.Const(Constant.bool(false)),
                ifFound);

        var goBody = new PirTerm.Lam("sigList_sb", new PirType.ListType(new PirType.DataType()), body);
        var binding = new PirTerm.Binding("go_sb", goBody);

        var sigsVar = new PirTerm.Var("sigs_sb", new PirType.ListType(new PirType.DataType()));

        var search = new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(goVar, sigsVar));

        // Wrap everything: compute values, then run the search
        return new PirTerm.Let("txInfo_sb", txInfo,
                new PirTerm.Let("pkh_sb", pkh,
                        new PirTerm.Let("sigs_sb", sigsExpr,
                                new PirTerm.Let("target_sb", pkhBs, search))));
    }

    /**
     * Extracts the list of inputs (field 0) from a TxInfo.
     * <p>
     * Returns the raw Data list of TxInInfo elements.
     *
     * @param txInfo PIR term representing a TxInfo (as Data)
     * @return PIR term evaluating to a List of Data (TxInInfo entries)
     */
    public static PirTerm txInfoInputs(PirTerm txInfo) {
        // UnListData(HeadList(SndPair(UnConstrData(txInfo))))
        var fields = sndPairUnconstData(txInfo);
        var inputsData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), fields);
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), inputsData);
    }

    /**
     * Extracts the list of outputs (field 2) from a TxInfo.
     *
     * @param txInfo PIR term representing a TxInfo (as Data)
     * @return PIR term evaluating to a List of Data (TxOut entries)
     */
    public static PirTerm txInfoOutputs(PirTerm txInfo) {
        var fields = sndPairUnconstData(txInfo);
        var outputsData = listIndex(fields, 2);
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), outputsData);
    }

    /**
     * Extracts the signatories list (field 8) from a TxInfo.
     *
     * @param txInfo PIR term representing a TxInfo (as Data)
     * @return PIR term evaluating to a List of Data (PubKeyHash entries)
     */
    public static PirTerm txInfoSignatories(PirTerm txInfo) {
        var fields = sndPairUnconstData(txInfo);
        var sigsData = listIndex(fields, 8);
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), sigsData);
    }

    /**
     * Extracts the valid range (field 7) from a TxInfo.
     *
     * @param txInfo PIR term representing a TxInfo (as Data)
     * @return PIR term evaluating to Data (an Interval)
     */
    public static PirTerm txInfoValidRange(PirTerm txInfo) {
        var fields = sndPairUnconstData(txInfo);
        return listIndex(fields, 7);
    }

    /**
     * Extracts the TxInfo (field 0) from a ScriptContext.
     *
     * @param ctx PIR term representing a ScriptContext (as Data)
     * @return PIR term evaluating to Data (TxInfo)
     */
    public static PirTerm getTxInfo(PirTerm ctx) {
        // HeadList(SndPair(UnConstrData(ctx)))
        var fields = sndPairUnconstData(ctx);
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), fields);
    }

    /**
     * Extracts the redeemer (field 1) from a ScriptContext.
     *
     * @param ctx PIR term representing a ScriptContext (as Data)
     * @return PIR term evaluating to Data (the redeemer)
     */
    public static PirTerm getRedeemer(PirTerm ctx) {
        var fields = sndPairUnconstData(ctx);
        return listIndex(fields, 1);
    }

    /**
     * Extracts the optional datum from a spending ScriptContext.
     * <p>
     * V3 ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo])
     * Spending ScriptInfo = Constr(1, [txOutRef, optionalDatum])
     * optionalDatum is either Constr(0, [datum]) for Some or Constr(1, []) for None.
     *
     * @param ctx PIR term representing a ScriptContext (as Data)
     * @return PIR term evaluating to Data (Optional datum encoding)
     */
    public static PirTerm getSpendingDatum(PirTerm ctx) {
        var ctxFields = sndPairUnconstData(ctx);
        var scriptInfoData = listIndex(ctxFields, 2);
        var scriptInfoFields = sndPairUnconstData(scriptInfoData);
        return listIndex(scriptInfoFields, 1);
    }

    // ---- Internal helpers ----

    /**
     * SndPair(UnConstrData(term)) — extracts the fields list from a Constr-encoded Data.
     */
    private static PirTerm sndPairUnconstData(PirTerm term) {
        return new PirTerm.App(
                new PirTerm.Builtin(DefaultFun.SndPair),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), term));
    }

    /**
     * Extracts the nth element from a Data list by chaining TailList n times then HeadList.
     */
    private static PirTerm listIndex(PirTerm list, int index) {
        PirTerm current = list;
        for (int i = 0; i < index; i++) {
            current = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), current);
        }
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), current);
    }

    /**
     * Extract a bytestring from a Constr(0, [bs]) Data value:
     * UnBData(HeadList(SndPair(UnConstrData(data))))
     */
    private static PirTerm unBDataFromConstr0(PirTerm data) {
        var fields = sndPairUnconstData(data);
        var firstField = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), fields);
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), firstField);
    }
}
