import java.math.BigInteger;

@SpendingValidator
class FakeValidator {
    @Entrypoint
    static boolean validate(BigInteger r, BigInteger c) { return true; }
}
