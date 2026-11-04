package org.julclang.testkit.fixtures;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

import java.math.BigInteger;

/** A validator that calls {@link SiblingLib}, found only through the source root, for the class-based compile entries. */
@SpendingValidator
public class SiblingLibraryValidator {
    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return SiblingLib.twice(BigInteger.ONE).signum() > 0;
    }
}
