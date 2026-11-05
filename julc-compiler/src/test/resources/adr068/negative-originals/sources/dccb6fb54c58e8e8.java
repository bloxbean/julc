import java.math.BigInteger;

@SpendingValidator
class TestValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        long counter = 3;
        while (counter > 0) {
            return true;
        }
        return false;
    }
}
