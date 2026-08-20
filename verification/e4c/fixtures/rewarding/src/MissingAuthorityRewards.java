import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.annotation.*;
import java.math.BigInteger;

@WithdrawValidator
class MissingAuthorityRewards {
    static final BigInteger MINIMUM = BigInteger.valueOf(1000000);
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Redeemer redeemer, ScriptContext ctx) {
        return switch (ctx.scriptInfo()) {
            case ScriptInfo.RewardingScript rewarding ->
                    ctx.txInfo().withdrawals().containsKey(rewarding.credential())
                    && ctx.txInfo().withdrawals().get(rewarding.credential())
                        .compareTo(MINIMUM) >= 0;
            default -> false;
        };
    }
}
