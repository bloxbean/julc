package evidence;

import com.bloxbean.cardano.julc.verification.dsl.VerificationSpecification;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose;
import com.bloxbean.cardano.julc.verification.dsl.ir.TxCertKind;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;

/** Certifying property: strict redeemer, current certificate, kind, and signer. */
public final class CertifyingSpec implements VerificationSpecification {
    private static final String AUTHORITY =
            "4a554c435f5645524946595f415554484f524954595f303030303031";

    @Override
    public DslPropertySet properties() {
        var contract = new CertifyingModel();
        var currentCertificate = contract.context().txInfo().certificates().containsAt(
                contract.certificateIndex(), contract.certificate());
        var authorized = contract.context().txInfo().signatories()
                .contains(keyHash(AUTHORITY));
        return DslPropertySet.composed(DslPurpose.CERTIFYING,
                property("certificate.authorized-update",
                        DslDomain.VALID_CERTIFYING_V3_PINNED,
                        contract.redeemerStrictlyDecodes()
                                .and(contract.certificate().isKind(TxCertKind.UPDATE_DREP))
                                .and(currentCertificate)
                                .and(authorized)));
    }
}
