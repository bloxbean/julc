package com.example.libs;

import com.example.libs.A;

@Validator
class ValidatorUsesTransitiveSamePackageLibrary {
    @Entrypoint
    static boolean validate(long redeemer, long ctx) {
        return A.calc() == 42;
    }
}
