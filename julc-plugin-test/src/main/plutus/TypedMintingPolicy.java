import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MintingValidator;
import org.julclang.ledger.PubKeyHash;
import org.julclang.stdlib.Builtins;

/**
 * Typed authorized minting policy using Milestone 6 typed access features.
 * <p>
 * Demonstrates:
 * - ScriptContext as typed parameter in a minting policy
 * - Chained method call: ctx.txInfo().signatories().contains(redeemer)
 * - No ContextsLib dependency — pure typed access
 */
@MintingValidator
class TypedMintingPolicy {
    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        return txInfo.signatories().contains(PubKeyHash.of(Builtins.unBData(redeemer)));
    }
}
