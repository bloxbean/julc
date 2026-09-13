import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import java.math.BigInteger;

@SpendingValidator
class VacuousSale {
    record Datum(byte[] seller, BigInteger price) {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return false;
    }
}
