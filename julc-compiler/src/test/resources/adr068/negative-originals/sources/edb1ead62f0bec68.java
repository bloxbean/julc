import java.math.BigInteger;

@SpendingValidator
class MyValidator {
    @Entrypoint
    static boolean validate(BigInteger r, BigInteger c) { return true; }
}
