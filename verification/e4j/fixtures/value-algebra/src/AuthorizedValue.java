import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.*;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
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
