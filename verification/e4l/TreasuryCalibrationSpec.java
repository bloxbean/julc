package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.integer;
import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.property;

/**
 * Calibration against the pinned model. The expected result is REFUTED: the
 * model's treasury helper/decoder boundary is deliberately weaker than this
 * reviewed strict optional interpretation.
 */
public final class TreasuryCalibrationSpec implements VerificationSpecification {
    @Override
    public DslPropertySet properties() {
        var contract = new AuthorizedReviewedAdaptersModel();
        var tx = contract.context().txInfo();
        var guarantee = tx.currentTreasuryStrict()
                .whenPresent(amount -> amount.eq(integer(100)))
                .and(tx.treasuryDonationStrict().isAbsent());
        return contract.properties(property("reviewed.treasury-calibration",
                DslDomain.VALID_SPENDING_V3_PINNED, guarantee));
    }
}
