package org.julclang.testkit.fixtures;

import java.math.BigInteger;

/** Calls {@link SiblingLib}, which the testkit finds only through the source root (ADR-068 D8). */
public class SiblingLibraryUser {
    static BigInteger m(BigInteger a) {
        return SiblingLib.twice(a);
    }
}
