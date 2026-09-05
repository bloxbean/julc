import java.math.BigInteger;
import java.util.Optional;
@MintingValidator class PairRecord {
    record Redeemer(BigInteger amount, boolean approved, Optional<BigInteger> limit) {}
    @Entrypoint static boolean validate(Redeemer r, ScriptContext ctx) { return r.approved(); }
}
