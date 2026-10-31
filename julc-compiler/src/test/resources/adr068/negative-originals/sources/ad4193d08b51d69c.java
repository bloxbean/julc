import java.math.BigInteger;

@WithdrawValidator
class BadLib {
    @Entrypoint
    static boolean validate(BigInteger a, BigInteger b) {
        return true;
    }
}
