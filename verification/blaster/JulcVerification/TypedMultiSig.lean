import PlutusCore.UPLC
import CardanoLedgerApi.V3
import Blaster
import JulcVerification.CheckedExecution

namespace JulcVerification.TypedMultiSig

open CardanoLedgerApi.V3
open PlutusCore.Data (Data)
open PlutusCore.UPLC.Utils
open JulcVerification.CheckedExecution

set_option warn.sorry false

#import_uplc multiSigValidator PlutusV3 double_cbor_hex
  "artifacts/typed-multisig.compiledCode.hex"

#prep_uplc appliedMultiSigValidator multiSigValidator spendingInputs 4000

/--
The authorization predicate corresponding to the two record fields JuLC
currently decodes. Constructor tags and trailing fields are deliberately not
claimed here; the smoke proof records that separate schema discrepancy.
-/
def bothDatumKeysSigned (ctx : ScriptContext) : Prop :=
  match ctx.scriptContextScriptInfo with
  | .SpendingScript _
      (some (Data.Constr _ (Data.B firstKey :: Data.B secondKey :: _))) =>
      txSignedBy firstKey ctx.scriptContextTxInfo ∧
        txSignedBy secondKey ctx.scriptContextTxInfo
  | _ => False

/-- A zero verification bound remains distinguishable exhaustion. -/
theorem zeroFuelIsReportedAsExhaustion (ctx : ScriptContext) :
    CheckedExecution.isStepExhausted
      (executePlutusV3Pv11Checked multiSigValidator.script
        (spendingInputs ctx) 0) := by
  simp [executePlutusV3Pv11Checked, executeProgramChecked, runStepsChecked,
    CheckedExecution.isStepExhausted,
    PlutusCore.UPLC.CekMachine.initialState]

/--
Within the pinned `#prep_uplc` unfolding bound, every successful execution of
the exact JuLC multisig artifact requires both datum keys as signatories.

This is intentionally labeled as a bounded SMT result until checked
preprocessing also establishes coverage for the symbolic signatory-list
domain.
-/
def successfulImpBothSignersWithinPrepBound : Prop :=
    ∀ ctx : ScriptContext,
      PlutusCore.UPLC.Utils.isSuccessful (appliedMultiSigValidator.prop ctx) →
      bothDatumKeysSigned ctx

/-
The pinned solver does not currently complete this recursive-list claim within
the Milestone A execution window. The claim remains explicit and type-checked,
but deliberately has no theorem or trusted axiom attached to it. Treat it as
COULD-NOT-EVALUATE until decomposition or induction makes it tractable and
checked preprocessing establishes coverage.
-/

end JulcVerification.TypedMultiSig
