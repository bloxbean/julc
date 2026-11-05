import java.math.BigInteger;

@SpendingValidator
class MyValidator {
    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        for (int i = 0; i < 10; i++) {
            // do nothing
        }
        return true;
    }
}
