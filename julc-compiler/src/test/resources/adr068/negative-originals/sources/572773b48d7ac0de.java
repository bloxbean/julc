import java.math.BigInteger;

record Datum(BigInteger value) {}

@SpendingValidator
class DupValidator {
    @Entrypoint
    static boolean validate(BigInteger r, BigInteger c) { return true; }
}
