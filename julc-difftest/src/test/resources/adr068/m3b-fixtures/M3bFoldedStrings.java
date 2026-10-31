package m3b.fixtures;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 M3b: adjacent string literals in a concatenation (F940). */
@SpendingValidator
class M3bFoldedStrings {
    static final String GREETING = "hello" + ", " + "world";

    static String concatThree(String a) { return a + "b" + "c"; }
    static boolean constantConcatenation() { return "a" + "b" == "ab"; }

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
