import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

@SpendingValidator
class VulnerableLedgerContextGate {
    record Datum() {}
    record Redeemer() {}
    @Entrypoint static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return true;
    }
}
