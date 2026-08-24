import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import java.math.BigInteger;

@SpendingValidator
class AuthorizedLedgerContextGate {
    record Datum() {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        TxInInfo input = ctx.txInfo().referenceInputs().head();
        boolean inline = switch (input.resolved().datum()) {
            case OutputDatum.OutputDatumInline ignored -> true;
            case OutputDatum.OutputDatumHash ignored -> false;
            case OutputDatum.NoOutputDatum ignored -> false;
        };
        boolean scriptCredential = switch (input.resolved().address().credential()) {
            case Credential.ScriptCredential ignored -> true;
            case Credential.PubKeyCredential ignored -> false;
        };
        return inline
                && scriptCredential
                && input.resolved().referenceScript().isEmpty()
                && ctx.txInfo().fee().compareTo(BigInteger.ZERO) >= 0
                && !ctx.txInfo().datums().isEmpty()
                && !ctx.txInfo().redeemers().isEmpty();
    }
}
