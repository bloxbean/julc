import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.ContextsLib;

@SpendingValidator
class VestingValidator {
    record VestingDatum(PlutusData beneficiary, PlutusData deadline) {}

    static boolean isPastDeadline(BigInteger currentTime, BigInteger deadline) {
        return currentTime.compareTo(deadline) > 0;
    }

    @Entrypoint
    static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ContextsLib.getTxInfo(ctx);
        byte[] pkh = Builtins.unBData(datum.beneficiary());
        return ContextsLib.signedBy(txInfo, pkh);
    }
}
