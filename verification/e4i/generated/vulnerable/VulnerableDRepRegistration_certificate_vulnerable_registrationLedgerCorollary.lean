/- Kernel bridge from pinned V3 certifying validity to the proved domain. -/
import VulnerableDRepRegistration_certificate_vulnerable_registrationProof
import LedgerDomainEquivalence

namespace JulcGenerated.VulnerableDRepRegistration_certificate_vulnerable_registration
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
    dslProperty_certificate_vulnerable_registration
      ⟨txInfo, redeemer,
        .CertifyingScript index certificate⟩ := by
  exact composedPropertyEstablished _ rfl
    (validCertifyingContext_implies_blasterDomain
      txInfo redeemer index certificate valid) successful

end JulcGenerated.VulnerableDRepRegistration_certificate_vulnerable_registration
