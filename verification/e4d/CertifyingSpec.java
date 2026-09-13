package evidence;

import org.julclang.verification.dsl.VerificationSpecification;
import org.julclang.verification.dsl.ir.DslDomain;
import org.julclang.verification.dsl.ir.DslPropertySet;
import org.julclang.verification.dsl.ir.TxCertKind;

import static org.julclang.verification.dsl.VerificationDsl.*;

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
        return contract.properties(
                property("certificate.authorized-update",
                        DslDomain.VALID_CERTIFYING_V3_PINNED,
                        contract.redeemer().isPresent()
                                .and(contract.certificate().isKind(TxCertKind.UPDATE_DREP))
                                .and(currentCertificate)
                                .and(authorized)));
    }
}
