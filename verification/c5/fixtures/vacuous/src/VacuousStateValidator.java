import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.verification.annotation.RequiresSigner;

@RequiresSigner("datum.owner")
@SpendingValidator
class VacuousStateValidator {
    record Datum(byte[] owner) {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return false;
    }
}
