import java.math.BigInteger;

@SpendingValidator
class TestValidator {
    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        if (redeemer > 0) {
            return true;
        }
    }
}
