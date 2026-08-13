import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.AddressLib;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import java.math.BigInteger;

@SpendingValidator
class AuthorizedSale {
    record Datum(byte[] seller, BigInteger price) {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        var attached = ContextsLib.getSpendingDatum(ctx);
        if (attached.isEmpty()) return false;
        PlutusData raw = attached.get();
        var fields = Builtins.constrFields(raw);
        boolean exact = Builtins.constrTag(raw) == 0
                && !Builtins.nullList(fields)
                && !Builtins.nullList(Builtins.tailList(fields))
                && Builtins.nullList(Builtins.tailList(Builtins.tailList(fields)));
        var outputs = ctx.txInfo().outputs();
        if (outputs.isEmpty()) return false;
        var output = outputs.head();
        return exact
                && AddressLib.isPubKeyAddress(output.address())
                && Builtins.equalsByteString(
                        AddressLib.credentialHash(output.address()), datum.seller())
                && ValuesLib.lovelaceOf(output.value()).compareTo(datum.price()) >= 0;
    }
}
