import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.*;

@WithdrawValidator
class VacuousRewards {
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        return false;
    }
}
