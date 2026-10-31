package com.example.libs;

import com.example.libs.A;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

@SpendingValidator
class ValidatorUsesTransitiveSamePackageLibrary {
    @Entrypoint
    static boolean validate(long redeemer, long ctx) {
        return A.calc() == 42;
    }
}
