import java.math.BigInteger;

@MintingPolicy
class MintingPolicy {
    sealed interface Action {
        record Mint(BigInteger amount) implements Action {}
        record Burn(BigInteger amount) implements Action {}
    }

    static boolean validateMint(BigInteger amount) {
        return amount > 0;
    }

    static boolean validateBurn(BigInteger amount) {
        return amount < 0;
    }

    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        BigInteger mintAmount = 1;
        return validateMint(mintAmount);
    }
}
