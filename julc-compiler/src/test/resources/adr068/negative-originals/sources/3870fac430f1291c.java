import java.math.BigInteger;

@SpendingValidator
class TestValidator {
    static boolean isEmpty(long x) {
        long y = x + 1;
    }

    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        return true;
    }
}
