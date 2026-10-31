import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

@SpendingValidator
class VerificationStateThreadBroken {
    record StateDatum(byte[] owner, BigInteger state) {}
    record Transition(BigInteger nextState) {}

    @Entrypoint
    static boolean validate(StateDatum datum, Transition redeemer, PlutusData ctx) {
        return redeemer.nextState().compareTo(datum.state()) > 0;
    }
}
