import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.core.PlutusData;
import org.julclang.ledger.PubKeyHash;
import org.julclang.stdlib.Builtins;

@SpendingValidator
public class VerificationMultiSig {
    record Keys(PlutusData key1, PlutusData key2) {}

    @Entrypoint
    public static boolean validate(Keys datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        var signatories = txInfo.signatories();
        boolean firstSigned = signatories.contains(PubKeyHash.of(Builtins.unBData(datum.key1())));
        boolean secondSigned = signatories.contains(PubKeyHash.of(Builtins.unBData(datum.key2())));
        return firstSigned && secondSigned;
    }
}
