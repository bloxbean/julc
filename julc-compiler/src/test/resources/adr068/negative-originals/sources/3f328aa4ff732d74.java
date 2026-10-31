import java.math.BigInteger;

@SpendingValidator
class MainValidator {
    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        return true;
    }
}
