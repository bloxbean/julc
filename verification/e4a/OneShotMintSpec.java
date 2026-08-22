package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;

import static com.bloxbean.cardano.julc.verification.dsl.MintingDsl.oneShotPropertySet;

/** User-owned E.4a typed property; generated TokenPolicyModel is disposable. */
public final class OneShotMintSpec implements VerificationSpecification {
    private static final String AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";
    private static final String ANCHOR_TX_ID =
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

    @Override
    public DslPropertySet properties() {
        return oneShotPropertySet(
                "one-shot-authorized-mint", AUTHORITY, ANCHOR_TX_ID,
                0, "4a554c43");
    }
}
