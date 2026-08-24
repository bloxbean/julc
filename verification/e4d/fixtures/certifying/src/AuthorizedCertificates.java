import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.*;

@CertifyingValidator
class AuthorizedCertificates {
    static final byte[] AUTHORITY = new byte[]{
        74,85,76,67,95,86,69,82,73,70,89,95,65,85,
        84,72,79,82,73,84,89,95,48,48,48,48,48,49};
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        if (ctx.txInfo().signatories().isEmpty()
                || !Builtins.equalsByteString(
                    ctx.txInfo().signatories().head().hash(), AUTHORITY)) return false;
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
