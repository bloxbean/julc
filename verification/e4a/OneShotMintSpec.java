package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.MintingContractModel;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

/** User-owned E.4a typed property; generated TokenPolicyModel is disposable. */
public final class OneShotMintSpec implements VerificationSpecification {
    private static final String AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";
    private static final String ANCHOR_TX_ID =
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

    @Override
    public DslPropertySet properties() {
        var contract = new MintingContractModel();
        var generated = new TokenPolicyModel();
        var quantity = integer(1);
        var guarantee = contract.redeemerStrictlyDecodes()
                .and(contract.context().txInfo().inputs()
                        .consumes(txOutRef(ANCHOR_TX_ID, 0)))
                .and(contract.context().txInfo().signatories()
                        .contains(keyHash(AUTHORITY)))
                .and(contract.context().txInfo().mint().exactOwnPolicyAsset(
                        contract.ownPolicy(), bytes("4a554c43"), quantity))
                .and(quantity.gt(integer(0)));
        return generated.properties(property("one-shot-authorized-mint",
                DslDomain.VALID_MINTING_V3_PINNED, guarantee));
    }
}
