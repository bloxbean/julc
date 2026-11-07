package org.julclang.processor.testshadow;

import org.julclang.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/** A class-path library source that a marked class of the compilation, of the same name, must win over (ADR-071 tests). */
@OnchainLibrary
public class Shadowed {
    public static BigInteger twice(BigInteger x) {
        return x.add(x).add(BigInteger.valueOf(1000));
    }
}
