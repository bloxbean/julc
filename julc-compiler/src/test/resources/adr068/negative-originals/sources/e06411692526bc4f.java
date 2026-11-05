import java.math.BigInteger;

@MintingValidator
class FakePolicy {
    @Entrypoint
    static boolean validate(BigInteger r, BigInteger c) { return true; }
}
