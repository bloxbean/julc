import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.core.PlutusData;

@SpendingValidator
public class VerificationMultiSig {
    record Keys(PlutusData key1, PlutusData key2) {}

    @Entrypoint
    public static boolean validate(Keys datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        var signatories = txInfo.signatories();
        boolean firstSigned = signatories.contains(datum.key1());
        boolean secondSigned = signatories.contains(datum.key2());
        return firstSigned && secondSigned;
    }
}
