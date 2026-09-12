import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

import java.math.BigInteger;
import java.util.Optional;

@SpendingValidator
class VulnerableCollectionGate {
    record Config(byte[] owner, Optional<BigInteger> minimum,
                  JulcList<BigInteger> values,
                  JulcMap<byte[], BigInteger> balances) {}
    record Datum(Config config) {}
    sealed interface Action permits Use, Stop {}
    record Use(byte[] key) implements Action {}
    record Stop() implements Action {}

    @Entrypoint
    static boolean validate(Datum datum, Action redeemer, ScriptContext ctx) {
        return true;
    }
}
