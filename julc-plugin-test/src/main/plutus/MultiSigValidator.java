import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/**
 * Multi-signature spending validator: requires two signers to unlock funds.
 * <p>
 * The redeemer contains two PubKeyHashes (key1, key2).
 * Both must appear in the transaction signatories.
 */
@SpendingValidator
class MultiSigValidator {
    record Keys(PlutusData key1, PlutusData key2) {}

    static boolean checkSignatures(TxInfo txInfo, PlutusData key1, PlutusData key2) {
        boolean sig1 = ContextsLib.signedBy(txInfo, Builtins.unBData(key1));
        boolean sig2 = ContextsLib.signedBy(txInfo, Builtins.unBData(key2));
        if (sig1) {
            return sig2;
        } else {
            return false;
        }
    }

    @Entrypoint
    static boolean validate(Keys datum, PlutusData redeemer, PlutusData ctx) {
        TxInfo txInfo = ContextsLib.getTxInfo(PlutusData.cast(ctx, ScriptContext.class));
        return checkSignatures(txInfo, datum.key1(), datum.key2());
    }
}
