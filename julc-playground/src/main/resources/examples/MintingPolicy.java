import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MintingValidator;

@MintingValidator
class MintingPolicy {
    sealed interface Action {
        record Mint(BigInteger amount) implements Action {}
        record Burn(BigInteger amount) implements Action {}
    }

    static boolean validateMint(BigInteger amount) {
        return amount.compareTo(BigInteger.ZERO) > 0;
    }

    static boolean validateBurn(BigInteger amount) {
        return amount.compareTo(BigInteger.ZERO) < 0;
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        BigInteger mintAmount = BigInteger.ONE;
        return validateMint(mintAmount);
    }
}
