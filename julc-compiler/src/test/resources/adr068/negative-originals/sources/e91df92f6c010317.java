import java.math.BigInteger;

@MintingValidator
class BadMintPolicy {
    @Entrypoint
    static boolean validate(BigInteger datum, BigInteger redeemer, BigInteger ctx) {
        return true;
    }
}
