import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import java.math.BigInteger;

@SpendingValidator
class UnpaidSale {
    record Datum(byte[] seller, BigInteger price) {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        var attached = ContextsLib.getSpendingDatum(ctx);
        if (attached.isEmpty()) return false;
        PlutusData raw = attached.get();
        var fields = Builtins.constrFields(raw);
        return Builtins.constrTag(raw) == 0
                && !Builtins.nullList(fields)
                && !Builtins.nullList(Builtins.tailList(fields))
                && Builtins.nullList(Builtins.tailList(Builtins.tailList(fields)));
    }
}
