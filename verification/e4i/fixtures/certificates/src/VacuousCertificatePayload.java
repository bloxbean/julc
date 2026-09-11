import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.*;

@CertifyingValidator
class VacuousCertificatePayload {
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        return false;
    }
}
