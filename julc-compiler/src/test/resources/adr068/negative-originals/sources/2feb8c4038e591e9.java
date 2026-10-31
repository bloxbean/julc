package com.myapp;
record Bid(java.math.BigInteger amount) {}
@SpendingValidator
class TestValidator {
    @Entrypoint
    static boolean validate(PlutusData redeemer, PlutusData ctx) {
        return true;
    }
}
