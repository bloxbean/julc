import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.*;

@SpendingValidator
class VulnerableValue {
    record Datum() {}
    record Redeemer() {}
    @Entrypoint static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return true;
    }
}
