import com.bloxbean.cardano.julc.core.PlutusData;

/**
 * Authorized minting policy: the redeemer is a PubKeyHash,
 * and the policy checks that the tx is signed by that key.
 */
@MintingPolicy
class AuthorizedMintingPolicy {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        return ContextsLib.signedBy(txInfo, redeemer);
    }
}
