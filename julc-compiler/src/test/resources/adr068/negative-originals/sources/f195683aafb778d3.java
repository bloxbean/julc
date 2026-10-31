import java.math.BigInteger;

sealed interface Action permits Mint, Burn {}
record Mint(long amt) implements Action {}
record Burn(long amt) implements Action {}

@SpendingValidator
class TestValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        Action a = (Action) redeemer;
        return switch (a) {
            case Mint m -> m.amt() > 0;
        };
    }
}
