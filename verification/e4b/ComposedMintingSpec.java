package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

/** A novel supported mint conjunction with a nested disjunction. */
public final class ComposedMintingSpec implements VerificationSpecification {
    private static final String AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";
    private static final String ANCHOR =
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

    @Override
    public DslPropertySet properties() {
        var contract = new ComposedPolicyModel();
        var quantity = integer(1);
        var guarantee = contract.redeemerStrictlyDecodes()
                .and(contract.context().txInfo().signatories().contains(keyHash(AUTHORITY)))
                .and(contract.context().txInfo().inputs().consumes(txOutRef(ANCHOR, 0)))
                .and(contract.context().txInfo().mint().exactOwnPolicyAsset(
                        contract.ownPolicy(), tokenName("4a554c43"), quantity))
                .and(quantity.gt(integer(0)).or(quantity.eq(integer(0))));
        return DslPropertySet.composed(DslPurpose.MINTING,
                property("policy.composed-one-shot",
                        DslDomain.VALID_MINTING_V3_PINNED, guarantee));
    }
}
