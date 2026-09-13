import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.*;
import org.julclang.stdlib.lib.ValuesLib;
import java.math.BigInteger;

@SpendingValidator
class AuthorizedValue {
    static final byte[] POLICY = "1111111111111111111111111111".getBytes();
    static final byte[] TOKEN = "token".getBytes();
    record Datum() {}
    record Redeemer() {}
    @Entrypoint static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        if (ctx.txInfo().outputs().isEmpty()) return false;
        return ValuesLib.assetOf(ctx.txInfo().outputs().head().value(), POLICY, TOKEN)
                .compareTo(BigInteger.TEN) >= 0;
    }
}
