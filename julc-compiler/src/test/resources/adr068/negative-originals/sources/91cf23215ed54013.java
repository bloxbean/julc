import java.math.BigInteger;

@SpendingValidator
class TestValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        long counter = 3;
        long acc = 0;
        while (counter > 0) {
            if (acc > 5) {
                acc = acc + 1;
            } else {
                return false;
            }
            counter = counter - 1;
        }
        return true;
    }
}
