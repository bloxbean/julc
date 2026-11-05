import java.math.BigInteger;
import org.julclang.stdlib.annotation.NewType;

@SpendingValidator
class TestValidator {
    @NewType
    record Nonce(BigInteger raw) {}

    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        var n = Nonce.of(BigInteger.ONE, BigInteger.TWO);
        return true;
    }
}
