import java.math.BigInteger;

@SpendingValidator
class MyValidator {
    static BigInteger fa(BigInteger n) {
        if (n == 0) { return 1; } else { return fb(n - 1); }
    }
    static BigInteger fb(BigInteger n) {
        if (n == 0) { return 2; } else { return fc(n - 1); }
    }
    static BigInteger fc(BigInteger n) {
        if (n == 0) { return 3; } else { return fa(n - 1); }
    }

    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        return fa(redeemer) > 0;
    }
}
