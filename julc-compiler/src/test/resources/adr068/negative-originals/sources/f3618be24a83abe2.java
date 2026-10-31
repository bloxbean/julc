import java.math.BigInteger;

@SpendingValidator
class MyValidator {
    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        return unknownVar == 42;
    }
}
