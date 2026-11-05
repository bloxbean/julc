import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.PubKeyHash;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/**
 * Token-gated spending validator with a sealed interface redeemer.
 * <p>
 * Defines two actions: Spend(owner) and Delegate(delegate).
 * Both require the corresponding PubKeyHash to be in the signatories.
 */
@SpendingValidator
class TokenGateValidator {
    sealed interface Action {
        record Spend(PlutusData owner) implements Action {}
        record Delegate(PlutusData delegate) implements Action {}
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        TxInfo txInfo = ContextsLib.getTxInfo(PlutusData.cast(ctx, ScriptContext.class));
        return ContextsLib.signedBy(txInfo, Builtins.unBData(redeemer));
    }
}
