package com.example.libs;

import com.example.libs.A;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.core.PlutusData;

@SpendingValidator
class ValidatorUsesTransitiveSamePackageLibrary {
    @Entrypoint
    static boolean validate(long redeemer, PlutusData ctx) {
        return A.calc() == 42;
    }
}
