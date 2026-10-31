import java.math.BigInteger;

@SpendingValidator
class TestValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        long x = 5;
    }
}
