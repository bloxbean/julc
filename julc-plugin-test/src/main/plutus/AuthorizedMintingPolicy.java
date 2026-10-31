import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.PubKeyHash;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MintingValidator;

/**
 * Authorized minting policy: the redeemer is a PubKeyHash,
 * and the policy checks that the tx is signed by that key.
 */
@MintingValidator
class AuthorizedMintingPolicy {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        TxInfo txInfo = ContextsLib.getTxInfo(PlutusData.cast(ctx, ScriptContext.class));
        return ContextsLib.signedBy(txInfo, Builtins.unBData(redeemer));
    }
}
