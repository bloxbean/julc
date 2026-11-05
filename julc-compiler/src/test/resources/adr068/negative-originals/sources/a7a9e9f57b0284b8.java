import java.math.BigInteger;

@Validator
class DeprecatedTest {
    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        return true;
    }
}
