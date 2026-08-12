import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SpendingValidator
class VerificationContainersSpending {
    record Datum(
            List<BigInteger> values,
            Map<byte[], BigInteger> balances,
            Optional<List<Map<byte[], BigInteger>>> nested,
            boolean enabled) {}

    record Redeemer(BigInteger expected) {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return true;
    }
}
