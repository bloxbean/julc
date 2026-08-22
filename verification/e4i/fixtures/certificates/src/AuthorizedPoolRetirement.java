import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.*;

import java.math.BigInteger;

@CertifyingValidator
class AuthorizedPoolRetirement {
    static final byte[] EXPECTED_POOL = new byte[] {
        65,65,65,65,65,65,65,65,65,65,65,65,65,65,
        65,65,65,65,65,65,65,65,65,65,65,65,65,65};
    static final BigInteger LAST_ALLOWED_EPOCH = BigInteger.valueOf(100);
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.CertifyingScript certifying ->
                    switch (certifying.cert()) {
                        case TxCert.PoolRetire retirement ->
                                Builtins.equalsByteString(
                                        retirement.pubKeyHash().hash(), EXPECTED_POOL)
                                && retirement.epoch().compareTo(
                                        LAST_ALLOWED_EPOCH) <= 0;
                        default -> false;
                    };
            default -> false;
        };
    }
}
