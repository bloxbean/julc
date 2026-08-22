import com.bloxbean.cardano.julc.ledger.Interval;
import com.bloxbean.cardano.julc.ledger.IntervalBoundType;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

import java.math.BigInteger;

@SpendingValidator
class AuthorizedReviewedAdapters {
    private static final byte[] AUTHORITY = new byte[] {
        65,65,65,65,65,65,65,65,65,65,65,65,65,65,
        65,65,65,65,65,65,65,65,65,65,65,65,65,65};

    record Datum(BigInteger deadline) {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        Interval range = ctx.txInfo().validRange();
        boolean lowerBound = switch (range.from().boundType()) {
            case IntervalBoundType.Finite lower ->
                    lower.time().equals(datum.deadline()) && range.from().isInclusive();
            default -> false;
        };
        if (!lowerBound) return false;

        boolean upperBound = switch (range.to().boundType()) {
            case IntervalBoundType.Finite upper ->
                    upper.time().equals(BigInteger.valueOf(20)) && range.to().isInclusive();
            default -> false;
        };
        if (!upperBound) return false;

        var current = ctx.txInfo().currentTreasuryAmount();
        if (!current.isPresent() || !current.get().equals(BigInteger.valueOf(100))) {
            return false;
        }
        var signers = ctx.txInfo().signatories();
        return !ctx.txInfo().treasuryDonation().isPresent()
                && !signers.isEmpty()
                && Builtins.equalsByteString(signers.head().hash(), AUTHORITY);
    }
}
