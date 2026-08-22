import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.annotation.*;

import java.math.BigInteger;

@CertifyingValidator
class AuthorizedDRepRegistration {
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.CertifyingScript certifying ->
                    switch (certifying.cert()) {
                        case TxCert.RegDRep registration ->
                                registration.deposit().compareTo(BigInteger.ONE) == 0;
                        default -> false;
                    };
            default -> false;
        };
    }
}
