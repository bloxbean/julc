import java.math.BigInteger;

@OnchainLibrary
@SpendingValidator
class Confused {
    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        return true;
    }
}
