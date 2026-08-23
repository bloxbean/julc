package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

/** Rewarding property: strict redeemer, authority signer, and minimum own withdrawal. */
public final class RewardingSpec implements VerificationSpecification {
    private static final String AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";

    @Override
    public DslPropertySet properties() {
        var contract = new RewardingModel();
        var ownMinimumWithdrawal = contract.context().txInfo().withdrawals().existsEntry(
                (credential, amount) -> credential.eq(contract.rewardingCredential().typed())
                        .and(new com.bloxbean.cardano.julc.verification.dsl.IntegerExpr(
                                amount.node()).ge(integer(1_000_000))));
        var authorized = contract.context().txInfo().signatories()
                .contains(keyHash(AUTHORITY));
        return contract.properties(
                property("reward.authorized-minimum",
                        DslDomain.VALID_REWARDING_V3_PINNED,
                        contract.redeemer().isPresent()
                                .and(authorized)
                                .and(ownMinimumWithdrawal)));
    }
}
