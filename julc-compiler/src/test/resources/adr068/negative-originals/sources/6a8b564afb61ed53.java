import java.math.BigInteger;

@MintingPolicy
class DeprecatedMintTest {
    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        return true;
    }
}
