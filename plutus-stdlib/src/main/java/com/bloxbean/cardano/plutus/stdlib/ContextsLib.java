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

    /**
     * Emits a UPLC Trace with the given message string, returning Unit.
     * <p>
     * The message must be a PIR term that evaluates to a UPLC string (Text).
     *
     * @param message PIR term representing the trace message (string/Text)
     * @return PIR term that emits the trace and evaluates to Unit
     */
    public static PirTerm trace(PirTerm message) {
        return new PirTerm.Trace(message, new PirTerm.Const(Constant.unit()));
    }

    // =========================================================================
    // TxInfo field accessors
    // =========================================================================

    /**
     * Extracts the mint field (index 4) from a TxInfo.
     * <p>
     * The mint value is a Map (policy → Map(name → amount)).
     *
     * @param txInfo PIR term representing a TxInfo (as Data)
     * @return PIR term evaluating to Data (the mint Value, a Map)
     */
    public static PirTerm txInfoMint(PirTerm txInfo) {
        var fields = sndPairUnconstData(txInfo);
        return listIndex(fields, 4);
    }

    /**
     * Extracts the fee field (index 3) from a TxInfo, decoded as an Integer.
     * <p>
     * The fee is stored as IData in the TxInfo. This method applies UnIData to
     * extract the raw Integer value.
     *
     * @param txInfo PIR term representing a TxInfo (as Data)
     * @return PIR term evaluating to Integer
     */
    public static PirTerm txInfoFee(PirTerm txInfo) {
        var fields = sndPairUnconstData(txInfo);
        var feeData = listIndex(fields, 3);
        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), feeData);
    }

    /**
     * Extracts the txId field (index 11) from a TxInfo.
     * <p>
     * Returns the raw Data representation of the transaction hash (typically BData-wrapped).
     *
     * @param txInfo PIR term representing a TxInfo (as Data)
     * @return PIR term evaluating to Data (the txId)
     */
    public static PirTerm txInfoId(PirTerm txInfo) {
        var fields = sndPairUnconstData(txInfo);
        return listIndex(fields, 11);
    }

    // =========================================================================
    // ScriptContext navigation
    // =========================================================================

    /**
     * Finds the own input for a spending validator.
     * <p>
     * Extracts the TxOutRef from ScriptInfo (Spending = tag 1, field 0),
     * then searches the inputs list for a TxInInfo whose TxOutRef matches.
     * Returns Optional: Constr(0, [TxInInfo]) for Some, Constr(1, []) for None.
     *
     * @param ctx PIR term representing a ScriptContext (as Data)
     * @return PIR term evaluating to Optional Data (TxInInfo)
     */
    public static PirTerm findOwnInput(PirTerm ctx) {
        // 1. Extract scriptInfo (field 2 of ctx)
        var ctxVar = new PirTerm.Var("ctx_foi", new PirType.DataType());
        var ctxFields = sndPairUnconstData(ctxVar);
        var scriptInfoData = listIndex(ctxFields, 2);

        // 2. Extract txOutRef from SpendingScript (field 0 of scriptInfo fields)
        var scriptInfoFields = sndPairUnconstData(scriptInfoData);
        var targetTxOutRef = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), scriptInfoFields);

        // 3. Extract inputs from txInfo (field 0)
        var txInfoData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), ctxFields);
        var txInfoFields = sndPairUnconstData(txInfoData);
        var inputsData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), txInfoFields);
        var inputsList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), inputsData);

        // 4. LetRec search through inputs
        var lstVar = new PirTerm.Var("lst_foi", new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var("go_foi", new PirType.FunType(
                new PirType.ListType(new PirType.DataType()), new PirType.DataType()));
        var refVar = new PirTerm.Var("ref_foi", new PirType.DataType());

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var none = constrData(1, List.of());

        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var hVar = new PirTerm.Var("h_foi", new PirType.DataType());

        // Extract TxOutRef from TxInInfo: field 0 = HeadList(SndPair(UnConstrData(h)))
        var hFields = sndPairUnconstData(hVar);
        var inputTxOutRef = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), hFields);

        var eqCheck = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsData), inputTxOutRef), refVar);
        var some = constrData(0, List.of(hVar));
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var recurse = new PirTerm.App(goVar, tailExpr);

        var innerIf = new PirTerm.IfThenElse(eqCheck, some, recurse);
        var letHead = new PirTerm.Let("h_foi", headExpr, innerIf);
        var outerIf = new PirTerm.IfThenElse(nullCheck, none, letHead);

        var goBody = new PirTerm.Lam("lst_foi", new PirType.ListType(new PirType.DataType()), outerIf);
        var binding = new PirTerm.Binding("go_foi", goBody);

        var search = new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(goVar, new PirTerm.Var("inputs_foi", new PirType.ListType(new PirType.DataType()))));

        return new PirTerm.Let("ctx_foi", ctx,
                new PirTerm.Let("ref_foi", targetTxOutRef,
                        new PirTerm.Let("inputs_foi", inputsList, search)));
    }

    /**
     * Returns the list of outputs that pay to the same address as the own spending input.
     * <p>
     * For spending validators: calls findOwnInput to get own TxInInfo, extracts the address,
     * then filters outputs where the output address matches.
     * Returns a builtin list of Data (TxOut entries).
     *
     * @param ctx PIR term representing a ScriptContext (as Data)
     * @return PIR term evaluating to a builtin list of Data (matching TxOut entries)
     */
    public static PirTerm getContinuingOutputs(PirTerm ctx) {
        var ctxVar = new PirTerm.Var("ctx_gco", new PirType.DataType());

        // 1. findOwnInput(ctx) returns Optional TxInInfo
        var ownInputOpt = findOwnInput(ctxVar);
        var ownInputOptVar = new PirTerm.Var("ownOpt_gco", new PirType.DataType());

        // 2. Extract the TxInInfo from Some: HeadList(SndPair(UnConstrData(opt)))
        //    (assuming the validator ensures the input is found)
        var optFields = sndPairUnconstData(ownInputOptVar);
        var ownInput = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), optFields);
        var ownInputVar = new PirTerm.Var("ownIn_gco", new PirType.DataType());

        // 3. Extract address from own TxInInfo:
        //    TxInInfo = Constr(0, [txOutRef, txOut])
        //    TxOut = Constr(0, [address, value, datum, referenceScript])
        //    address = field 0 of TxOut, TxOut = field 1 of TxInInfo
        var ownInFields = sndPairUnconstData(ownInputVar);
        var ownTxOut = listIndex(ownInFields, 1);
        var ownTxOutFields = sndPairUnconstData(ownTxOut);
        var ownAddress = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), ownTxOutFields);
        var ownAddrVar = new PirTerm.Var("ownAddr_gco", new PirType.DataType());

        // 4. Get outputs from txInfo
        var ctxFields = sndPairUnconstData(ctxVar);
        var txInfoData = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), ctxFields);
        var outputs = txInfoOutputs(txInfoData);
        var outputsVar = new PirTerm.Var("outs_gco", new PirType.ListType(new PirType.DataType()));

        // 5. Filter: for each output, extract address (field 0), compare with ownAddress
        var accVar = new PirTerm.Var("acc_gco", new PirType.ListType(new PirType.DataType()));
        var xVar = new PirTerm.Var("x_gco", new PirType.DataType());
        var xFields = sndPairUnconstData(xVar);
        var xAddr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), xFields);
        var addrMatch = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsData), xAddr), ownAddrVar);
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), xVar), accVar);
        var filterBody = new PirTerm.IfThenElse(addrMatch, consExpr, accVar);
        var filterFn = new PirTerm.Lam("acc_gco", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("x_gco", new PirType.DataType(), filterBody));
        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var filtered = ListsLib.foldl(filterFn, emptyList, outputsVar);

        return new PirTerm.Let("ctx_gco", ctx,
                new PirTerm.Let("ownOpt_gco", ownInputOpt,
                        new PirTerm.Let("ownIn_gco", ownInput,
                                new PirTerm.Let("ownAddr_gco", ownAddress,
                                        new PirTerm.Let("outs_gco", outputs, filtered)))));
    }

    /**
     * Searches the txInfo datums map for a datum matching the given hash.
     * <p>
     * The datums field (index 10 of TxInfo) is a Map&lt;DataHash, Data&gt;.
     * This method applies UnMapData to get the pair list and recursively searches
     * for a pair whose key matches the given hash using EqualsData.
     * Returns Optional: Constr(0, [datum]) for Some, Constr(1, []) for None.
     *
     * @param txInfo PIR term representing a TxInfo (as Data)
     * @param hash   PIR term representing the datum hash (as Data)
     * @return PIR term evaluating to Optional Data
     */
    public static PirTerm findDatum(PirTerm txInfo, PirTerm hash) {
        var txInfoVar = new PirTerm.Var("txInfo_fd", new PirType.DataType());
        var hashVar = new PirTerm.Var("hash_fd", new PirType.DataType());

        // Extract datums map: field 10 of TxInfo, then UnMapData
        var fields = sndPairUnconstData(txInfoVar);
        var datumsData = listIndex(fields, 10);
        var datumsPairs = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), datumsData);

        // LetRec search through pairs
        var pairsVar = new PirTerm.Var("pairs_fd", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())));
        var goVar = new PirTerm.Var("go_fd", new PirType.FunType(
                new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())),
                new PirType.DataType()));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), pairsVar);
        var none = constrData(1, List.of());

        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), pairsVar);
        var hVar = new PirTerm.Var("h_fd", new PirType.PairType(new PirType.DataType(), new PirType.DataType()));
        var fstH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), hVar);
        var sndH = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), hVar);
        var eqCheck = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsData), fstH), hashVar);
        var some = constrData(0, List.of(sndH));
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), pairsVar);
        var recurse = new PirTerm.App(goVar, tailExpr);

        var innerIf = new PirTerm.IfThenElse(eqCheck, some, recurse);
        var letHead = new PirTerm.Let("h_fd", headExpr, innerIf);
        var outerIf = new PirTerm.IfThenElse(nullCheck, none, letHead);

        var goBody = new PirTerm.Lam("pairs_fd", new PirType.ListType(
                new PirType.PairType(new PirType.DataType(), new PirType.DataType())), outerIf);
        var binding = new PirTerm.Binding("go_fd", goBody);

        var search = new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(goVar, new PirTerm.Var("datums_fd",
                        new PirType.ListType(new PirType.PairType(new PirType.DataType(), new PirType.DataType())))));

        return new PirTerm.Let("txInfo_fd", txInfo,
                new PirTerm.Let("hash_fd", hash,
                        new PirTerm.Let("datums_fd", datumsPairs, search)));
    }

    /**
     * Collects the values of all inputs as a builtin list of Data.
     * <p>
     * For each TxInInfo in the inputs list, extracts the TxOut (field 1),
     * then the value (field 1 of TxOut), and collects them into a list.
     * <p>
     * Note: Returns a list of individual Value entries, NOT a summed Value.
     * To sum the values, use ValuesLib.add in a fold over the result.
     *
     * @param txInfo PIR term representing a TxInfo (as Data)
     * @return PIR term evaluating to a builtin list of Data (Value entries)
     */
    public static PirTerm valueSpent(PirTerm txInfo) {
        var txInfoVar = new PirTerm.Var("txInfo_vs", new PirType.DataType());

        // Extract inputs list
        var inputs = txInfoInputs(txInfoVar);
        var inputsVar = new PirTerm.Var("inputs_vs", new PirType.ListType(new PirType.DataType()));

        // Fold function: \acc input ->
        //   let txOut = listIndex(sndPairUnconstData(input), 1) in
        //   let value = listIndex(sndPairUnconstData(txOut), 1) in
        //   MkCons(value, acc)
        var accVar = new PirTerm.Var("acc_vs", new PirType.ListType(new PirType.DataType()));
        var inputVar = new PirTerm.Var("input_vs", new PirType.DataType());
        var inputFields = sndPairUnconstData(inputVar);
        var txOut = listIndex(inputFields, 1);
        var txOutFields = sndPairUnconstData(txOut);
        var value = listIndex(txOutFields, 1);
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), value), accVar);

        var foldFn = new PirTerm.Lam("acc_vs", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("input_vs", new PirType.DataType(), consExpr));
        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var folded = ListsLib.foldl(foldFn, emptyList, inputsVar);

        return new PirTerm.Let("txInfo_vs", txInfo,
                new PirTerm.Let("inputs_vs", inputs, folded));
    }

    /**
     * Filters outputs by address and returns their values as a builtin list.
     * <p>
     * For each output whose address matches the given address (via EqualsData),
     * extracts the value (field 1 of TxOut) and collects it into the result list.
     *
     * @param txInfo PIR term representing a TxInfo (as Data)
     * @param addr   PIR term representing the target address (as Data)
     * @return PIR term evaluating to a builtin list of Data (Value entries)
     */
    public static PirTerm valuePaid(PirTerm txInfo, PirTerm addr) {
        var txInfoVar = new PirTerm.Var("txInfo_vp", new PirType.DataType());
        var addrVar = new PirTerm.Var("addr_vp", new PirType.DataType());

        // Extract outputs list
        var outputs = txInfoOutputs(txInfoVar);
        var outputsVar = new PirTerm.Var("outs_vp", new PirType.ListType(new PirType.DataType()));

        // Filter fold: for each output, extract address (field 0) and value (field 1)
        var accVar = new PirTerm.Var("acc_vp", new PirType.ListType(new PirType.DataType()));
        var outVar = new PirTerm.Var("out_vp", new PirType.DataType());
        var outFields = sndPairUnconstData(outVar);
        var outAddr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), outFields);
        var outValue = listIndex(outFields, 1);

        var addrMatch = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsData), outAddr), addrVar);
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), outValue), accVar);
        var filterBody = new PirTerm.IfThenElse(addrMatch, consExpr, accVar);

        var foldFn = new PirTerm.Lam("acc_vp", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("out_vp", new PirType.DataType(), filterBody));
        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var folded = ListsLib.foldl(foldFn, emptyList, outputsVar);

        return new PirTerm.Let("txInfo_vp", txInfo,
                new PirTerm.Let("addr_vp", addr,
                        new PirTerm.Let("outs_vp", outputs, folded)));
    }

    /**
     * Extracts the own script hash from the ScriptContext's ScriptInfo.
     * <p>
     * For MintingScript (tag 0): returns the policyId (first field).
     * For SpendingScript (tag 1): finds the own input and extracts the script hash
     * from the payment credential of the address.
     * Dispatches based on the ScriptInfo constructor tag using IfThenElse.
     *
     * @param ctx PIR term representing a ScriptContext (as Data)
     * @return PIR term evaluating to Data (the script hash, BData-wrapped)
     */
    public static PirTerm ownHash(PirTerm ctx) {
        var ctxVar = new PirTerm.Var("ctx_oh", new PirType.DataType());

        // Extract scriptInfo (field 2 of ctx)
        var ctxFields = sndPairUnconstData(ctxVar);
        var scriptInfoData = listIndex(ctxFields, 2);
        var scriptInfoVar = new PirTerm.Var("si_oh", new PirType.DataType());

        // Decompose scriptInfo
        var unconstr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), scriptInfoVar);
        var unconstrVar = new PirTerm.Var("uc_oh",
                new PirType.PairType(new PirType.IntegerType(), new PirType.ListType(new PirType.DataType())));
        var tag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), unconstrVar);
        var siFields = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), unconstrVar);

        // Tag 0 (Minting): policyId = HeadList(fields)
        var mintHash = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), siFields);

        // Tag 1 (Spending): need to find own input and extract script hash from address credential
        // - findOwnInput(ctx) -> Optional TxInInfo
        // - Extract TxInInfo from Some: HeadList(SndPair(UnConstrData(opt)))
        // - TxInInfo field 1 -> TxOut, TxOut field 0 -> Address
        // - Address field 0 -> Credential, Credential field 0 -> scriptHash (for ScriptCredential)
        var ownInputOpt = findOwnInput(ctxVar);
        var optFields = sndPairUnconstData(ownInputOpt);
        var ownInput = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), optFields);
        var ownInFields = sndPairUnconstData(ownInput);
        var ownTxOut = listIndex(ownInFields, 1);
        var ownTxOutFields = sndPairUnconstData(ownTxOut);
        var ownAddress = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), ownTxOutFields);
        var addrFields = sndPairUnconstData(ownAddress);
        var credential = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), addrFields);
        var credFields = sndPairUnconstData(credential);
        var spendHash = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), credFields);

        // Dispatch: if tag == 0 -> mintHash, else -> spendHash
        var isTag0 = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), tag),
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
        var dispatch = new PirTerm.IfThenElse(isTag0, mintHash, spendHash);

        return new PirTerm.Let("ctx_oh", ctx,
                new PirTerm.Let("si_oh", scriptInfoData,
                        new PirTerm.Let("uc_oh", unconstr, dispatch)));
    }

    /**
     * Filters outputs whose address has a ScriptCredential matching the given script hash.
     * <p>
     * For each output: extracts address (field 0 of TxOut), then credential (field 0 of Address),
     * checks if credential is a ScriptCredential (tag == 1), and if the hash (field 0 of credential)
     * matches using EqualsData. Matching outputs are collected into a builtin list.
     *
     * @param txInfo     PIR term representing a TxInfo (as Data)
     * @param scriptHash PIR term representing the script hash to match (as Data)
     * @return PIR term evaluating to a builtin list of Data (matching TxOut entries)
     */
    public static PirTerm scriptOutputsAt(PirTerm txInfo, PirTerm scriptHash) {
        var txInfoVar = new PirTerm.Var("txInfo_soa", new PirType.DataType());
        var hashVar = new PirTerm.Var("hash_soa", new PirType.DataType());

        // Extract outputs list
        var outputs = txInfoOutputs(txInfoVar);
        var outputsVar = new PirTerm.Var("outs_soa", new PirType.ListType(new PirType.DataType()));

        // LetRec search: for each output, check credential
        var lstVar = new PirTerm.Var("lst_soa", new PirType.ListType(new PirType.DataType()));
        var accVar = new PirTerm.Var("acc_soa", new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var("go_soa", new PirType.FunType(
                new PirType.ListType(new PirType.DataType()),
                new PirType.FunType(new PirType.ListType(new PirType.DataType()),
                        new PirType.ListType(new PirType.DataType()))));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var outVar = new PirTerm.Var("out_soa", new PirType.DataType());

        // Extract address -> credential -> decompose
        var outFields = sndPairUnconstData(outVar);
        var outAddr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), outFields);
        var addrFields = sndPairUnconstData(outAddr);
        var credential = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), addrFields);
        var credUnconstr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), credential);
        var credTag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair), credUnconstr);
        var credFields = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SndPair), credUnconstr);
        var credHash = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), credFields);

        // Check: tag == 1 (ScriptCredential) AND hash matches
        var isScript = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger), credTag),
                new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        var hashMatch = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsData), credHash), hashVar);

        // Combined check: isScript AND hashMatch
        // In UPLC, AND = IfThenElse(isScript, hashMatch, False)
        var bothMatch = new PirTerm.IfThenElse(isScript, hashMatch,
                new PirTerm.Const(Constant.bool(false)));

        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var consExpr = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), outVar), accVar);
        var keepRecurse = new PirTerm.App(new PirTerm.App(goVar, tailExpr), consExpr);
        var skipRecurse = new PirTerm.App(new PirTerm.App(goVar, tailExpr), accVar);

        var innerIf = new PirTerm.IfThenElse(bothMatch, keepRecurse, skipRecurse);
        var letOut = new PirTerm.Let("out_soa", headExpr, innerIf);
        var outerIf = new PirTerm.IfThenElse(nullCheck, accVar, letOut);

        var goBody = new PirTerm.Lam("lst_soa", new PirType.ListType(new PirType.DataType()),
                new PirTerm.Lam("acc_soa", new PirType.ListType(new PirType.DataType()), outerIf));
        var binding = new PirTerm.Binding("go_soa", goBody);

        var emptyList = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var search = new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(new PirTerm.App(goVar, outputsVar), emptyList));

        return new PirTerm.Let("txInfo_soa", txInfo,
                new PirTerm.Let("hash_soa", scriptHash,
                        new PirTerm.Let("outs_soa", outputs, search)));
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

    /**
     * Build a Constr Data value at the PIR level.
     * ConstrData(tag, MkCons(f1, MkCons(f2, ... MkNilData())))
     */
    private static PirTerm constrData(int tag, List<PirTerm> fields) {
        PirTerm list = new PirTerm.App(
                new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        for (int i = fields.size() - 1; i >= 0; i--) {
            list = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), fields.get(i)),
                    list);
        }
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                        new PirTerm.Const(Constant.integer(BigInteger.valueOf(tag)))),
                list);
    }
}
