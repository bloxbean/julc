import java.math.BigInteger;

@SpendingValidator
class TestValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        long outer = 2;
        long acc = 0;
        while (outer > 0) {
            long inner = 2;
            while (inner > 0) {
                return true;
            }
            acc = acc + 1;
            outer = outer - 1;
        }
        return acc == 2;
    }
}
