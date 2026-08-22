/- Kernel bridge from pinned V3 spending validity to the proved domain. -/
import VulnerableThresholdGate_authorization_vulnerable_thresholdProof
import LedgerDomainEquivalence

namespace JulcGenerated.VulnerableThresholdGate_authorization_vulnerable_threshold
open CardanoLedgerApi.V3
open JulcGenerated.UserProperty

theorem composedLedgerCorollary
    (txInfo : TxInfo) (redeemer : PlutusCore.Data.Data)
    (ref : TxOutRef) (datum : Option CardanoLedgerApi.V2.Datum)
    (valid : CardanoLedgerApi.V3.Contexts.validSpendingContext
      ⟨txInfo, redeemer, .SpendingScript ref datum⟩ = true)
    (successful : PlutusCore.UPLC.Utils.isSuccessful
      (appliedValidator.prop
        ⟨txInfo, redeemer, .SpendingScript ref datum⟩)) :
    dslProperty_authorization_vulnerable_threshold
      ⟨txInfo, redeemer, .SpendingScript ref datum⟩ := by
  exact composedPropertyEstablished _ rfl
    (validSpendingContext_implies_blasterDomain
      txInfo redeemer ref datum valid) successful

end JulcGenerated.VulnerableThresholdGate_authorization_vulnerable_threshold
