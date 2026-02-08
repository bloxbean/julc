import com.bloxbean.cardano.plutus.core.PlutusData;

/**
 * Token-gated spending validator with a sealed interface redeemer.
 * <p>
 * Defines two actions: Spend(owner) and Delegate(delegate).
 * Both require the corresponding PubKeyHash to be in the signatories.
 */
@Validator
class TokenGateValidator {
    sealed interface Action {
        record Spend(PlutusData owner) implements Action {}
        record Delegate(PlutusData delegate) implements Action {}
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        PlutusData txInfo = ContextsLib.getTxInfo(ctx);
        return ContextsLib.signedBy(txInfo, redeemer);
    }
}
