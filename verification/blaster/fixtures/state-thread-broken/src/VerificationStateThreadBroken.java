import java.math.BigInteger;

@SpendingValidator
class VerificationStateThreadBroken {
    record StateDatum(byte[] owner, BigInteger state) {}
    record Transition(BigInteger nextState) {}

    @Entrypoint
    static boolean validate(StateDatum datum, Transition redeemer, PlutusData ctx) {
        return redeemer.nextState().compareTo(datum.state()) > 0;
    }
}
