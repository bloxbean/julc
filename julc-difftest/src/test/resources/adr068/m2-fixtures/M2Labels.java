package m2.fixtures;

import java.math.BigInteger;
import java.util.Optional;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.NewType;
import org.julclang.stdlib.annotation.SpendingValidator;

/**
 * ADR-068 M2: type shapes the recorded corpus does not hold, so the corpus tools see them too. A String @NewType, a
 * boolean @NewType, and a @NewType inside containers.
 */
@NewType record Label(String v) {}

@NewType record Flag(boolean v) {}

record Tagged(Label label, Flag flag, JulcList<Label> labels, Optional<Label> first, BigInteger amount) {}

@SpendingValidator
class M2Labels {
    @Entrypoint
    static boolean validate(Tagged datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
