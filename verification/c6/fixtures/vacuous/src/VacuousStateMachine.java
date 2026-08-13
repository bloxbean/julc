import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.*;
import com.bloxbean.cardano.julc.verification.annotation.*;
import java.math.BigInteger;

@RequiresSigner("datum.owner")
@Monotonic(current="datum.state", next="redeemer.nextState",
    relation=Relation.GREATER_THAN)
@PreservesValue(output=OutputSelection.SINGLE_CONTINUING_OUTPUT)
@SpendingValidator
class VacuousStateMachine {
    record Datum(byte[] owner, BigInteger state) {}
    record Redeemer(BigInteger nextState) {}
    @Entrypoint static boolean validate(Datum d, Redeemer r, ScriptContext c) {
        return false;
    }
}
