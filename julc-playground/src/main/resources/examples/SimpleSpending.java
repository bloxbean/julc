import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.ContextsLib;

@SpendingValidator
class SimpleSpending {
    @Param static byte[] authorizedSigner;

    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ContextsLib.getTxInfo(ctx);
        return ContextsLib.signedBy(txInfo, authorizedSigner);
    }
}
