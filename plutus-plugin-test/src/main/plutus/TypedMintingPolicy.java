import com.bloxbean.cardano.plutus.core.PlutusData;

/**
 * Typed authorized minting policy using Milestone 6 typed access features.
 * <p>
 * Demonstrates:
 * - ScriptContext as typed parameter in a minting policy
 * - Chained method call: ctx.txInfo().signatories().contains(redeemer)
 * - No ContextsLib dependency — pure typed access
 */
@MintingPolicy
class TypedMintingPolicy {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        return txInfo.signatories().contains(redeemer);
    }
}
