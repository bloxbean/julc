import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.*;

@MintingValidator
class OneShotPolicy {
    record Redeemer() {}
    @Entrypoint static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        return false;
    }
}
