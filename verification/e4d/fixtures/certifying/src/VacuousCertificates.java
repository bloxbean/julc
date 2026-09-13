import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.*;

@CertifyingValidator
class VacuousCertificates {
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        return false;
    }
}
