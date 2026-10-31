import java.math.BigInteger;
import java.util.Optional;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MintingValidator;
@MintingValidator class PairRecord {
    record Redeemer(BigInteger amount, boolean approved, Optional<BigInteger> limit) {}
    @Entrypoint static boolean validate(Redeemer r, ScriptContext ctx) { return r.approved(); }
}
