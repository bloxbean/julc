package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.ledger.*;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Script context operations compiled from Java source to UPLC.
 * <p>
 * Uses typed records (ScriptContext, TxInfo, ScriptInfo, etc.) for readability.
 * Simple field accessors work both on-chain and off-chain.
 * Complex methods use typed returns (Optional, JulcList, byte[]) for DX-friendly API.
 */
@OnchainLibrary
@SuppressWarnings("unchecked")
public class ContextsLib {

    // =========================================================================
    // Trace (on-chain: emits UPLC Trace; off-chain: prints to stdout)
    // =========================================================================

    /** Emits a trace message. On-chain becomes UPLC Trace builtin.
     *  Body is a no-op — the compiler uses the PIR-registered version. */
    public static void trace(String message) {
        // no-op off-chain; on-chain the StdlibRegistry generates PIR Trace
    }

    // =========================================================================
    // ScriptContext field accessors (off-chain compatible)
    // =========================================================================

    /** Extracts the TxInfo from a ScriptContext. */
    public static TxInfo getTxInfo(ScriptContext ctx) {
        return ctx.txInfo();
    }

    /** Extracts the redeemer from a ScriptContext. */
    public static PlutusData getRedeemer(ScriptContext ctx) {
        return ctx.redeemer();
    }

    // =========================================================================
    // TxInfo field accessors (off-chain compatible)
    // =========================================================================

    /** Extracts the list of inputs from a TxInfo. */
    public static JulcList<TxInInfo> txInfoInputs(TxInfo txInfo) {
        return txInfo.inputs();
    }

    /** Extracts the list of outputs from a TxInfo. */
    public static JulcList<TxOut> txInfoOutputs(TxInfo txInfo) {
        return txInfo.outputs();
    }

    /** Extracts the signatories list from a TxInfo. */
    public static JulcList<PubKeyHash> txInfoSignatories(TxInfo txInfo) {
        return txInfo.signatories();
    }

    /** Extracts the valid range from a TxInfo. */
    public static Interval txInfoValidRange(TxInfo txInfo) {
        return txInfo.validRange();
    }

    /** Extracts the mint field from a TxInfo. */
    public static Value txInfoMint(TxInfo txInfo) {
        return txInfo.mint();
    }

    /** Extracts the fee from a TxInfo. */
    public static BigInteger txInfoFee(TxInfo txInfo) {
        return txInfo.fee();
    }

    /** Extracts the txId from a TxInfo. */
    public static TxId txInfoId(TxInfo txInfo) {
        return txInfo.id();
    }

    /** Extracts reference inputs from TxInfo. */
    public static JulcList<TxInInfo> txInfoRefInputs(TxInfo txInfo) {
        return txInfo.referenceInputs();
    }

    /** Extracts withdrawals map from TxInfo. */
    public static JulcMap<Credential, BigInteger> txInfoWithdrawals(TxInfo txInfo) {
        return txInfo.withdrawals();
    }

    /** Extracts redeemers map from TxInfo. */
    public static JulcMap<ScriptPurpose, PlutusData> txInfoRedeemers(TxInfo txInfo) {
        return txInfo.redeemers();
    }

    // =========================================================================
    // signedBy (off-chain compatible via Builtins.equalsByteString byte[] overload)
    // =========================================================================

    /** Checks whether a given PubKeyHash is in the signatories list. */
    public static boolean signedBy(TxInfo txInfo, byte[] pkh) {
        boolean found = false;
        for (PubKeyHash sig : txInfo.signatories()) {
            if (Builtins.equalsByteString(sig.hash(), pkh)) {
                found = true;
            } else {
                found = found;
            }
        }
        return found;
    }

    // =========================================================================
    // getSpendingDatum — returns Optional<PlutusData>
    // =========================================================================

    /** Extracts the optional datum from a spending ScriptContext.
     *  Returns Optional.of(datum) for SpendingScript with datum, Optional.empty() otherwise. */
    public static Optional<PlutusData> getSpendingDatum(ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript ss -> ss.datum();
            case ScriptInfo.MintingScript ms -> Optional.empty();
            case ScriptInfo.RewardingScript rs -> Optional.empty();
            case ScriptInfo.CertifyingScript cs -> Optional.empty();
            case ScriptInfo.VotingScript vs -> Optional.empty();
            case ScriptInfo.ProposingScript ps -> Optional.empty();
        };
    }

    // =========================================================================
    // findOwnInput — returns Optional<TxInInfo>
    // =========================================================================

    /** Finds the own input for a spending validator. Returns Optional<TxInInfo>. */
    public static Optional<TxInInfo> findOwnInput(ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript ss -> {
                Optional<TxInInfo> result = Optional.empty();
                for (TxInInfo input : ctx.txInfo().inputs()) {
                    if (Builtins.equalsData(input.outRef(), ss.txOutRef())) {
                        result = Optional.of(input);
                    } else {
                        result = result;
                    }
                }
                yield result;
            }
            case ScriptInfo.MintingScript ms -> Optional.empty();
            case ScriptInfo.RewardingScript rs -> Optional.empty();
            case ScriptInfo.CertifyingScript cs -> Optional.empty();
            case ScriptInfo.VotingScript vs -> Optional.empty();
            case ScriptInfo.ProposingScript ps -> Optional.empty();
        };
    }

    // =========================================================================
    // ownInputScriptHash — returns byte[]
    // =========================================================================

    /** Extracts the script credential hash from the own input's address. */
    public static byte[] ownInputScriptHash(ScriptContext ctx) {
        Optional<TxInInfo> ownInputOpt = findOwnInput(ctx);
        TxInInfo ownInput = ownInputOpt.get();
        return AddressLib.credentialHash(ownInput.resolved().address());
    }

    // =========================================================================
    // ownHash — returns byte[]
    // =========================================================================

    /** Extracts the own script hash from the ScriptContext's ScriptInfo.
     *  Minting -> policyId. Others -> script credential hash from own input. */
    public static byte[] ownHash(ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.MintingScript ms -> (byte[])(Object) ms.policyId();
            case ScriptInfo.SpendingScript ss -> ownInputScriptHash(ctx);
            case ScriptInfo.RewardingScript rs -> ownInputScriptHash(ctx);
            case ScriptInfo.CertifyingScript cs -> ownInputScriptHash(ctx);
            case ScriptInfo.VotingScript vs -> ownInputScriptHash(ctx);
            case ScriptInfo.ProposingScript ps -> ownInputScriptHash(ctx);
        };
    }

    // =========================================================================
    // getContinuingOutputs — returns JulcList<TxOut>
    // =========================================================================

    /** Returns outputs that pay to the same address as the own spending input. */
    public static JulcList<TxOut> getContinuingOutputs(ScriptContext ctx) {
        Optional<TxInInfo> ownInputOpt = findOwnInput(ctx);
        TxInInfo ownInput = ownInputOpt.get();
        Address ownAddress = ownInput.resolved().address();

        JulcList<TxOut> result = JulcList.empty();
        for (TxOut out : ctx.txInfo().outputs()) {
            if (Builtins.equalsData(out.address(), ownAddress)) {
                result = result.prepend(out);
            } else {
                result = result;
            }
        }
        return result;
    }

    // =========================================================================
    // valueSpent — returns JulcList<Value>
    // =========================================================================

    /** Collects the values of all inputs as a list. */
    public static JulcList<Value> valueSpent(TxInfo txInfo) {
        JulcList<Value> result = JulcList.empty();
        for (TxInInfo input : txInfo.inputs()) {
            result = result.prepend(input.resolved().value());
        }
        return result;
    }

    // =========================================================================
    // valuePaid — returns JulcList<Value>, takes Address param
    // =========================================================================

    /** Filters outputs by address and returns their values as a list. */
    public static JulcList<Value> valuePaid(TxInfo txInfo, Address addr) {
        JulcList<Value> result = JulcList.empty();
        for (TxOut out : txInfo.outputs()) {
            if (Builtins.equalsData(out.address(), addr)) {
                result = result.prepend(out.value());
            } else {
                result = result;
            }
        }
        return result;
    }

    // =========================================================================
    // scriptOutputsAt — returns JulcList<TxOut>, takes byte[] param
    // =========================================================================

    /** Filters outputs whose address has a ScriptCredential matching the given hash. */
    public static JulcList<TxOut> scriptOutputsAt(TxInfo txInfo, byte[] scriptHash) {
        JulcList<TxOut> result = JulcList.empty();
        for (TxOut out : txInfo.outputs()) {
            Address outAddr = out.address();
            byte[] credHash = AddressLib.credentialHash(outAddr);
            if (AddressLib.isScriptAddress(outAddr)
                    && Builtins.equalsByteString(credHash, scriptHash)) {
                result = result.prepend(out);
            } else {
                result = result;
            }
        }
        return result;
    }

    // =========================================================================
    // findDatum — returns Optional<PlutusData>
    // =========================================================================

    /** Searches the txInfo datums map for a datum matching the given hash. Returns Optional. */
    public static Optional<PlutusData> findDatum(TxInfo txInfo, PlutusData hash) {
        PlutusData current = (PlutusData)(Object) txInfo.datums();
        Optional<PlutusData> result = Optional.empty();
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(pair), hash)) {
                result = Optional.of(Builtins.sndPair(pair));
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** Get the nth element from a list by chaining tailList/headList. */
    public static PlutusData listIndex(PlutusData.ListData list, BigInteger n) {
        var current = list;
        var idx = n;
        while (idx.compareTo(BigInteger.ZERO) > 0) {
            current = Builtins.tailList(current);
            idx = idx.subtract(BigInteger.ONE);
        }
        return Builtins.headList(current);
    }
}
