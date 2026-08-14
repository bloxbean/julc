import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.Builtins;
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
        var attached = ContextsLib.getSpendingDatum(ctx);
        if (attached.isEmpty()) {
            return false;
        }
        PlutusData rawDatum = attached.get();
        var fields = Builtins.constrFields(rawDatum);
        boolean exactDatumShape = Builtins.constrTag(rawDatum) == 0
                && !Builtins.nullList(fields)
                && Builtins.nullList(Builtins.tailList(fields));
        return exactDatumShape
                && Builtins.equalsByteString(
                        Builtins.unBData(Builtins.headList(fields)), datum.owner())
                && ContextsLib.signedBy(ctx.txInfo(), datum.owner());
    }
}
