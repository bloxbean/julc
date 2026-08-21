import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;

import java.math.BigInteger;
import java.util.Optional;

@SpendingValidator
class AuthorizedCollectionGate {
    record Config(byte[] owner, Optional<BigInteger> minimum,
                  JulcList<BigInteger> values,
                  JulcMap<byte[], BigInteger> balances) {}
    record Datum(Config config) {}
    sealed interface Action permits Use, Stop {}
    record Use(byte[] key) implements Action {}
    record Stop() implements Action {}

    @Entrypoint
    static boolean validate(Datum datum, Action redeemer, ScriptContext ctx) {
        return ContextsLib.signedBy(ctx.txInfo(), datum.config().owner());
    }
}
