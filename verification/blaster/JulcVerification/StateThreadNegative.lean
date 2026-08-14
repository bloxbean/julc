import PlutusCore.UPLC
import CardanoLedgerApi.V3
import Blaster
import JulcVerification.StateThread

namespace JulcVerification.StateThreadNegative

open CardanoLedgerApi.V3
open PlutusCore.Data (Data)
open PlutusCore.UPLC.Utils
open JulcVerification.StateThread (outputCommitsTo stateTransitionDomain)

set_option warn.sorry false

#import_uplc brokenStateThreadValidator PlutusV3 double_cbor_hex
  "artifacts/state-thread-broken.compiledCode.hex"

#prep_uplc appliedBrokenStateThreadValidator brokenStateThreadValidator
  spendingInputs 30000

def outputCommittedToCurrentRef (ctx : ScriptContext) : Prop :=
  match ctx.scriptContextScriptInfo,
      ctx.scriptContextRedeemer,
      ctx.scriptContextTxInfo.txInfoOutputs with
  | .SpendingScript currentRef _,
      Data.Constr _ (Data.I nextState :: _),
      nextOutput :: _ => outputCommitsTo nextOutput currentRef nextState
  | _, _, _ => False

/-- The vulnerable source omits the successor-output reference commitment. -/
def brokenSuccessImpliesOutputCommitment : Prop :=
  ∀ ctx : ScriptContext,
    stateTransitionDomain ctx →
    PlutusCore.UPLC.Utils.isSuccessful
      (appliedBrokenStateThreadValidator.prop ctx) →
    outputCommittedToCurrentRef ctx

/-- Non-vacuity control: this universal no-success claim must be refuted. -/
def brokenHasNoSuccessfulDomainInput : Prop :=
  ∀ ctx : ScriptContext,
    stateTransitionDomain ctx →
    ¬PlutusCore.UPLC.Utils.isSuccessful
      (appliedBrokenStateThreadValidator.prop ctx)

#blaster (gen-cex: 1) (solve-result: 1)
  [brokenHasNoSuccessfulDomainInput]

#blaster (gen-cex: 1) (solve-result: 1)
  [brokenSuccessImpliesOutputCommitment]

end JulcVerification.StateThreadNegative
