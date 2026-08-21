/- Kernel-check the solver-compatible domain against pinned V3. -/
import SecurityProperty

namespace JulcGenerated.UserProperty

open CardanoLedgerApi.V3
open PlutusCore.Integer (Integer)

/-- Every pinned V3 ledger-valid spending context is in the solver domain. -/
theorem validSpendingContext_implies_blasterDomain
    (txInfo : TxInfo)
    (redeemer : PlutusCore.Data.Data)
    (ref : TxOutRef)
    (datum : Option CardanoLedgerApi.V2.Datum)
    (valid : CardanoLedgerApi.V3.Contexts.validSpendingContext
      ⟨txInfo, redeemer, .SpendingScript ref datum⟩ = true) :
    blasterValidSpendingContext
      ⟨txInfo, redeemer, .SpendingScript ref datum⟩ = true := by
  let ctx : ScriptContext :=
    ⟨txInfo, redeemer, .SpendingScript ref datum⟩
  change CardanoLedgerApi.V3.Contexts.validSpendingContext ctx = true
    at valid
  change blasterValidSpendingContext ctx = true
  unfold CardanoLedgerApi.V3.Contexts.validSpendingContext at valid
  unfold CardanoLedgerApi.V3.Contexts.validScriptContext at valid
  have contextParts := Bool.and_eq_true_iff.mp valid
  have scriptInfo := contextParts.1
  have txValid := contextParts.2
  unfold CardanoLedgerApi.V3.Contexts.validTxInfo at txValid
  have p1 := Bool.and_eq_true_iff.mp txValid
  have p2 := Bool.and_eq_true_iff.mp p1.1
  have p3 := Bool.and_eq_true_iff.mp p2.1
  have p4 := Bool.and_eq_true_iff.mp p3.1
  unfold blasterValidSpendingContext
  unfold blasterValidTxInfo
  apply Bool.and_eq_true_iff.mpr
  constructor
  · exact scriptInfo
  · exact p4.1

end JulcGenerated.UserProperty
