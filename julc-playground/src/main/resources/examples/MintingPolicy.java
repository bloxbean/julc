import java.math.BigInteger;

@MintingValidator
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
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        BigInteger mintAmount = 1;
        return validateMint(mintAmount);
    }
}
