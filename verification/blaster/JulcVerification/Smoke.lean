import PlutusCore.UPLC
import CardanoLedgerApi.V3
import Blaster
import JulcVerification.CheckedExecution

namespace JulcVerification.Smoke

open CardanoLedgerApi.V3
open PlutusCore.Data (Data)
open PlutusCore.UPLC.Utils
open JulcVerification.CheckedExecution

set_option warn.sorry false

#import_uplc smokeValidator PlutusV3 double_cbor_hex
  "artifacts/smoke.compiledCode.hex"

#prep_uplc appliedSmokeValidator smokeValidator spendingInputs 10000

/-- JuLC currently decodes a one-field record from the first constructor field. -/
def firstFieldIsExpectedSecret : Data → Prop
  | Data.Constr _ (Data.I 424242 :: _) => True
  | _ => False

/-- The behavior of the exact compiled VerificationDatumGate artifact. -/
def expectedSecretMatch (ctx : ScriptContext) : Prop :=
  match ctx.scriptContextScriptInfo, ctx.scriptContextRedeemer with
  | .SpendingScript _ (some datum), redeemer =>
      firstFieldIsExpectedSecret datum ∧ firstFieldIsExpectedSecret redeemer
  | _, _ => False

/-- The stricter one-field constructor shape advertised by the schema. -/
def strictSchemaSecretMatch (ctx : ScriptContext) : Prop :=
  match ctx.scriptContextScriptInfo, ctx.scriptContextRedeemer with
  | .SpendingScript _ (some (Data.Constr 0 [Data.I 424242])),
      Data.Constr 0 [Data.I 424242] => True
  | _, _ => False

/-- A zero verification bound remains exhaustion, never validator failure. -/
theorem zeroFuelIsReportedAsExhaustion (ctx : ScriptContext) :
    CheckedExecution.isStepExhausted
      (executePlutusV3Pv11Checked smokeValidator.script (spendingInputs ctx) 0) := by
  simp [executePlutusV3Pv11Checked, executeProgramChecked, runStepsChecked,
    CheckedExecution.isStepExhausted,
    PlutusCore.UPLC.CekMachine.initialState]

/--
The exact JuLC smoke artifact succeeds exactly when the first decoded datum and
redeemer fields contain the expected secret. The theorem quantifies over all
V3 script contexts, so it does not depend on the current V3 ledger-validity
predicate being within Blaster's translation subset.

This result is SMT-valid with the current Blaster trusted base; it is not a
Lean-kernel reconstruction of the Z3 proof.
-/
theorem successfulIffExpectedSecretMatch :
    ∀ ctx : ScriptContext,
      (PlutusCore.UPLC.Utils.isSuccessful (appliedSmokeValidator.prop ctx) ↔
        expectedSecretMatch ctx) := by
  blaster

/--
Negative control and implementation finding: successful record decoding does
not currently imply the exact constructor tag and arity advertised by the
blueprint schema. Blaster must refute this claim.
-/
def successImpliesStrictSchema : Prop :=
  ∀ ctx : ScriptContext,
    PlutusCore.UPLC.Utils.isSuccessful (appliedSmokeValidator.prop ctx) →
    strictSchemaSecretMatch ctx

#blaster (gen-cex: 1) (solve-result: 1) [successImpliesStrictSchema]

end JulcVerification.Smoke
