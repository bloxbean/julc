package com.example.validators;

import java.math.BigInteger;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

@SpendingValidator
class SimpleValidator {
    @Entrypoint
    static boolean validate(BigInteger redeemer, BigInteger ctx) {
        return true;
    }
}
