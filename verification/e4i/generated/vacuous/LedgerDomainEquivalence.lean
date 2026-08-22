/- Kernel-check the solver-compatible domain against pinned V3. -/
import SecurityProperty

namespace JulcGenerated.UserProperty

open CardanoLedgerApi.V3

/-- Every pinned V3 ledger-valid certifying context is in the solver domain. -/
theorem validCertifyingContext_implies_blasterDomain
    (txInfo : TxInfo)
    (redeemer : PlutusCore.Data.Data)
    (index : Int)
    (certificate : TxCert)
    (valid : CardanoLedgerApi.V3.Contexts.validCertifyingContext
      ⟨txInfo, redeemer,
        .CertifyingScript index certificate⟩ = true) :
    blasterValidCertifyingContext
      ⟨txInfo, redeemer,
        .CertifyingScript index certificate⟩ = true := by
  let ctx : ScriptContext :=
    ⟨txInfo, redeemer, .CertifyingScript index certificate⟩
  change CardanoLedgerApi.V3.Contexts.validCertifyingContext ctx = true
    at valid
  change blasterValidCertifyingContext ctx = true
  unfold CardanoLedgerApi.V3.Contexts.validCertifyingContext at valid
  unfold CardanoLedgerApi.V3.Contexts.validScriptContext at valid
  have contextParts := Bool.and_eq_true_iff.mp valid
  have scriptInfo := contextParts.1
  have txValid := contextParts.2
  unfold CardanoLedgerApi.V3.Contexts.validTxInfo at txValid
  have p1 := Bool.and_eq_true_iff.mp txValid
  have p2 := Bool.and_eq_true_iff.mp p1.1
  have p3 := Bool.and_eq_true_iff.mp p2.1
  have p4 := Bool.and_eq_true_iff.mp p3.1
  unfold blasterValidCertifyingContext
  unfold blasterValidTxInfo
  apply Bool.and_eq_true_iff.mpr
  constructor
  · exact scriptInfo
  · exact p4.1

end JulcGenerated.UserProperty
