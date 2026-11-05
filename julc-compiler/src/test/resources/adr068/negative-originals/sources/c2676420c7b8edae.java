import java.math.BigInteger;

@MintingValidator
class BadMint {
    @Entrypoint
    static boolean validate(BigInteger datum, BigInteger redeemer, BigInteger ctx) {
        return true;
    }
}
