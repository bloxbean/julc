import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.AddressLib;
import org.julclang.stdlib.lib.ValuesLib;
import java.math.BigInteger;

/**
 * Deliberately local payment check: two inputs can share one seller output.
 * E.3 should establish its local property while documenting this global gap.
 */
@SpendingValidator
class MultiSatisfactionSale {
    record Datum(byte[] seller, BigInteger price) {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        var outputs = ctx.txInfo().outputs();
        if (outputs.isEmpty()) return false;
        var output = outputs.head();
        return AddressLib.isPubKeyAddress(output.address())
                && Builtins.equalsByteString(
                        AddressLib.credentialHash(output.address()), datum.seller())
                && ValuesLib.lovelaceOf(output.value()).compareTo(datum.price()) >= 0;
    }
}
