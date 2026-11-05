import java.math.BigInteger;

@OnchainLibrary
@MintingValidator
class ConfusedLib {
    @Entrypoint
    static boolean validate(BigInteger a, BigInteger b) {
        return true;
    }
}
