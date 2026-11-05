package org.julclang.testkit.fixtures;

import org.julclang.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/** A same-project library that only the source root offers (not bundled on the class path), for {@code SiblingLibraryUser}. */
@OnchainLibrary
public class SiblingLib {
    public static BigInteger twice(BigInteger a) {
        return a.add(a);
    }
}
