package com.example.validators;

import java.math.BigInteger;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.core.PlutusData;

@SpendingValidator
class SimpleValidator {
    @Entrypoint
    static boolean validate(BigInteger redeemer, PlutusData ctx) {
        return true;
    }
}
