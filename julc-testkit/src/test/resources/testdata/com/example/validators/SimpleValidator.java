package com.example.validators;

import java.math.BigInteger;

@SpendingValidator
class SimpleValidator {
    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        return true;
    }
}
