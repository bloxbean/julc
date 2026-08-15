import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;

@RequiresSigner("datum.owner")
@SpendingValidator
class AuthorizedStateValidator {
    record Datum(byte[] owner) {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return ContextsLib.signedBy(ctx.txInfo(), datum.owner());
    }
}
