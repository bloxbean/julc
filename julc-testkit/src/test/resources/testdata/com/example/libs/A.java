package com.example.libs;

@OnchainLibrary
class A {
    static long calc() {
        return B.helper();
    }
}
