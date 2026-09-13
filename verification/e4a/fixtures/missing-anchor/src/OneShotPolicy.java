import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.*;

@MintingValidator
class OneShotPolicy {
    record Redeemer() {}
    @Entrypoint static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        return true;
    }
}
