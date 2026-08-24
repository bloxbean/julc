import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.annotation.*;

@CertifyingValidator
class MissingAuthorityCertificates {
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.CertifyingScript certifying ->
                    switch (certifying.cert()) {
                        case TxCert.UpdateDRep ignored -> true;
                        default -> false;
                    };
            default -> false;
        };
    }
}
