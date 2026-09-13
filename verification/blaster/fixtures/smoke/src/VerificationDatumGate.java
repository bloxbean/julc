import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.ledger.ScriptContext;
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
