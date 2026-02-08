import com.bloxbean.cardano.plutus.core.PlutusData;

/**
 * Multi-signature spending validator: requires two signers to unlock funds.
 * <p>
 * The redeemer contains two PubKeyHashes (key1, key2).
 * Both must appear in the transaction signatories.
 */
@Validator
class MultiSigValidator {
    record Keys(PlutusData key1, PlutusData key2) {}

    static boolean checkSignatures(PlutusData txInfo, PlutusData key1, PlutusData key2) {
        boolean sig1 = ContextsLib.signedBy(txInfo, key1);
        boolean sig2 = ContextsLib.signedBy(txInfo, key2);
        if (sig1) {
            return sig2;
        } else {
            return false;
        }
    }

    @Entrypoint
    static boolean validate(Keys datum, PlutusData redeemer, PlutusData ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        return checkSignatures(txInfo, datum.key1(), datum.key2());
    }
}
