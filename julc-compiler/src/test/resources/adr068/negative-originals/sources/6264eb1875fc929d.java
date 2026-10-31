import java.math.BigInteger;

@SpendingValidator
class TestValidator {
    static boolean helper(long x) {
        if (x > 0) {
            if (x > 10) {
                return true;
            }
        } else {
            return false;
        }
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        return true;
    }
}
