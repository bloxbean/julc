import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import java.math.BigInteger;

@SpendingValidator
public class VerificationDatumGate {
    record Datum(BigInteger secret) {}
    record Redeemer(BigInteger secret) {}

    @Entrypoint
    public static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return datum.secret() == 424242 && redeemer.secret() == 424242;
    }
}
