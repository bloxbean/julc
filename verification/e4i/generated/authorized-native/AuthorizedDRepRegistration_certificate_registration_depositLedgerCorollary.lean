/- Kernel bridge from pinned V3 certifying validity to the proved domain. -/
import AuthorizedDRepRegistration_certificate_registration_depositProof
import LedgerDomainEquivalence

namespace JulcGenerated.AuthorizedDRepRegistration_certificate_registration_deposit
open CardanoLedgerApi.V3
open JulcGenerated.UserProperty

theorem composedLedgerCorollary
    (txInfo : TxInfo) (redeemer : PlutusCore.Data.Data)
    (index : Int) (certificate : TxCert)
    (valid : CardanoLedgerApi.V3.Contexts.validCertifyingContext
      ⟨txInfo, redeemer, .CertifyingScript index certificate⟩ = true)
    (successful : PlutusCore.UPLC.Utils.isSuccessful
      (appliedValidator.prop
        ⟨txInfo, redeemer,
          .CertifyingScript index certificate⟩)) :
    dslProperty_certificate_registration_deposit
      ⟨txInfo, redeemer,
        .CertifyingScript index certificate⟩ := by
  exact composedPropertyEstablished _ rfl
    (validCertifyingContext_implies_blasterDomain
      txInfo redeemer index certificate valid) successful

end JulcGenerated.AuthorizedDRepRegistration_certificate_registration_deposit
