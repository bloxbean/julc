import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.*;

@SpendingValidator
class VulnerableValue {
    record Datum() {}
    record Redeemer() {}
    @Entrypoint static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return true;
    }
}
