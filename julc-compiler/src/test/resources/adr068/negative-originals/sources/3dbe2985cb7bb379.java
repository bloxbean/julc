import java.math.BigInteger;

@SpendingValidator
class TestValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        long counter = 3;
        long acc = 0;
        while (counter > 0) {
            if (acc > 5) {
                return true;
            }
            acc = acc + counter;
            counter = counter - 1;
        }
        return false;
    }
}
