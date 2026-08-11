import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.core.PlutusData;

@SpendingValidator
public class VerificationMultiSig {
    record Keys(PlutusData key1, PlutusData key2) {}

    @Entrypoint
    public static boolean validate(Keys datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        var signatories = txInfo.signatories();
        // Deliberately vulnerable negative-control fixture: key2 is ignored.
        return signatories.contains(datum.key1());
    }
}
