import org.julclang.ledger.*;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.*;

@CertifyingValidator
class AuthorizedDRepUpdate {
    static final byte[] EXPECTED_DREP = new byte[] {
        65,65,65,65,65,65,65,65,65,65,65,65,65,65,
        65,65,65,65,65,65,65,65,65,65,65,65,65,65};
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.CertifyingScript certifying ->
                    switch (certifying.cert()) {
                        case TxCert.UpdateDRep update ->
                                switch (update.credential()) {
                                    case Credential.PubKeyCredential key ->
                                            Builtins.equalsByteString(
                                                    key.hash().hash(), EXPECTED_DREP);
                                    default -> false;
                                };
                        default -> false;
                    };
            default -> false;
        };
    }
}
