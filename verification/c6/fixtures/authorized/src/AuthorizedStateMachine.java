import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.verification.annotation.*;
import java.math.BigInteger;

@RequiresSigner("datum.owner")
@Monotonic(
        current = "datum.state",
        next = "redeemer.nextState",
        relation = Relation.GREATER_THAN)
@PreservesValue(output = OutputSelection.SINGLE_CONTINUING_OUTPUT)
@SpendingValidator
class AuthorizedStateMachine {
    record Datum(byte[] owner, BigInteger state) {}
    record Redeemer(BigInteger nextState) {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        if (ctx.txInfo().inputs().isEmpty() || ctx.txInfo().outputs().isEmpty()
                || !ctx.txInfo().outputs().tail().isEmpty()) {
            return false;
        }
        TxInInfo ownInput = ctx.txInfo().inputs().head();
        TxOut successor = ctx.txInfo().outputs().head();
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.SpendingScript spending ->
                Builtins.equalsData(ownInput.outRef(), spending.txOutRef())
                    && Builtins.equalsData(successor.address(), ownInput.resolved().address())
                    && Builtins.equalsData(successor.value(), ownInput.resolved().value())
                    && switch (successor.datum()) {
                        case OutputDatum.OutputDatumInline inline ->
                            isExactSuccessor(inline.datum(), datum, redeemer)
                                && ContextsLib.signedBy(ctx.txInfo(), datum.owner())
                                && datum.state().compareTo(redeemer.nextState()) < 0;
                        default -> false;
                    };
            default -> false;
        };
    }

    static boolean isExactSuccessor(PlutusData raw, Datum datum, Redeemer redeemer) {
        var fields = Builtins.constrFields(raw);
        return Builtins.constrTag(raw) == 0
                && !Builtins.nullList(fields)
                && !Builtins.nullList(Builtins.tailList(fields))
                && Builtins.nullList(Builtins.tailList(Builtins.tailList(fields)))
                && Builtins.equalsByteString(
                        Builtins.unBData(Builtins.headList(fields)), datum.owner())
                && Builtins.unIData(Builtins.headList(Builtins.tailList(fields)))
                        .equals(redeemer.nextState());
    }
}
