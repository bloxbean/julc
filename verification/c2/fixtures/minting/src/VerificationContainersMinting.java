import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MintingValidator;

@MintingValidator
class VerificationContainersMinting {
    record Redeemer(
            List<BigInteger> values,
            Map<byte[], BigInteger> balances,
            Optional<List<Map<byte[], BigInteger>>> nested,
            boolean enabled) {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        return true;
    }
}
