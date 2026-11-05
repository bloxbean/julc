package com.example.libs;
import org.julclang.stdlib.annotation.OnchainLibrary;

@OnchainLibrary
class A {
    static long calc() {
        return B.helper();
    }
}
