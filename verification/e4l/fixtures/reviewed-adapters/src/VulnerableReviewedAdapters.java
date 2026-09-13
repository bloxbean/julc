import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

import java.math.BigInteger;

@SpendingValidator
class VulnerableReviewedAdapters {
    record Datum(BigInteger deadline) {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return true;
    }
}
