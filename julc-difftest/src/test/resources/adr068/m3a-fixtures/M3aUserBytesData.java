package m3a.fixtures;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;

/** ADR-068 D7: a {@code @Param} rule on a type's name is about the API's type, not a user record of that name. */
@SpendingValidator
class M3aUserBytesData {
    record BytesData(byte[] value) { }

    @Param static BytesData p;

    @Entrypoint
    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
