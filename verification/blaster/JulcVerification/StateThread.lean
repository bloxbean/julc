import PlutusCore.UPLC
import CardanoLedgerApi.V3
import Blaster

namespace JulcVerification.StateThread

open CardanoLedgerApi.IsData.Class
open CardanoLedgerApi.V3
open PlutusCore.Data (Data)
open PlutusCore.Integer (Integer)
open PlutusCore.UPLC.Utils

set_option warn.sorry false

#import_uplc stateThreadValidator PlutusV3 double_cbor_hex
  "artifacts/state-thread.compiledCode.hex"

#prep_uplc appliedStateThreadValidator stateThreadValidator spendingInputs 30000

/--
Explicit bounded ledger domain: the first input is the current spending input.
-/
def stateTransitionDomain (ctx : ScriptContext) : Prop :=
  match ctx.scriptContextScriptInfo,
      ctx.scriptContextTxInfo.txInfoInputs,
      ctx.scriptContextTxInfo.txInfoOutputs with
  | .SpendingScript currentRef _, ownInput :: _, _ :: _ =>
      ownInput.txInInfoOutRef = currentRef
  | _, _, _ => False

/--
The bounded-shape state transition enforced by the exact artifact. Constructor
tags and trailing record fields remain deliberately unconstrained because the
Milestone A decoder finding has not yet been fixed.
-/
def expectedStateTransition (ctx : ScriptContext) : Prop :=
  match ctx.scriptContextScriptInfo,
      ctx.scriptContextRedeemer,
      ctx.scriptContextTxInfo.txInfoSignatories,
      ctx.scriptContextTxInfo.txInfoInputs,
      ctx.scriptContextTxInfo.txInfoOutputs with
  | .SpendingScript currentRef
        (some (Data.Constr _ (Data.B owner :: Data.I currentState :: _))),
      Data.Constr _ (Data.I nextState :: _),
      firstSigner :: _,
      ownInput :: _,
      nextOutput :: _ =>
        firstSigner = owner ∧
        nextState > currentState ∧
        ownInput.txInInfoOutRef = currentRef ∧
        ownInput.txInInfoResolved.txOutValue = nextOutput.txOutValue ∧
        nextOutput.txOutDatum =
          .OutputDatum
            (Data.Constr 0 [IsData.toData currentRef, Data.I nextState])
  | _, _, _, _, _ => False

/-- Successful exact-artifact execution implies the full state transition. -/
theorem successfulImpliesExpectedStateTransition :
    ∀ ctx : ScriptContext,
      stateTransitionDomain ctx →
      PlutusCore.UPLC.Utils.isSuccessful
          (appliedStateThreadValidator.prop ctx) →
        expectedStateTransition ctx := by
  blaster

/-- Non-vacuity control: the no-success claim must be solver-refuted. -/
def hasNoSuccessfulStateTransitionInput : Prop :=
  ∀ ctx : ScriptContext,
    stateTransitionDomain ctx →
    ¬PlutusCore.UPLC.Utils.isSuccessful
      (appliedStateThreadValidator.prop ctx)

#blaster (gen-cex: 1) (solve-result: 1)
  [hasNoSuccessfulStateTransitionInput]

/-- A state output commits to one consumed reference and next-state value. -/
def outputCommitsTo
    (out : CardanoLedgerApi.V2.Tx.TxOut)
    (ref : CardanoLedgerApi.V3.Tx.TxOutRef)
    (nextState : Integer) : Prop :=
  out.txOutDatum =
    .OutputDatum (Data.Constr 0 [IsData.toData ref, Data.I nextState])

/-- Encoding a V3 output reference as Data is injective. -/
theorem txOutRefData_injective :
    Function.Injective
      (IsData.toData : CardanoLedgerApi.V3.Tx.TxOutRef → Data) := by
  intro a b h
  cases a with
  | mk aid aidx =>
      cases b with
      | mk bid bidx =>
          change Data.Constr 0 [Data.B aid, Data.I aidx] =
            Data.Constr 0 [Data.B bid, Data.I bidx] at h
          simp at h
          rcases h with ⟨rfl, rfl⟩
          rfl

/--
Kernel-checked double-satisfaction composition lemma: the same successor output
cannot commit to two distinct consumed references.
-/
theorem sharedOutputCommitmentImpliesSameRef
    (out : CardanoLedgerApi.V2.Tx.TxOut)
    (ref₁ ref₂ : CardanoLedgerApi.V3.Tx.TxOutRef)
    (next₁ next₂ : Integer)
    (h₁ : outputCommitsTo out ref₁ next₁)
    (h₂ : outputCommitsTo out ref₂ next₂) :
    ref₁ = ref₂ := by
  have hdata : IsData.toData ref₁ = IsData.toData ref₂ := by
    rw [outputCommitsTo] at h₁ h₂
    rw [h₁] at h₂
    injection h₂ with hconstr
    have hfirst := congrArg
      (fun d : Data => match d with
        | Data.Constr _ (refData :: _) => some refData
        | _ => none)
      hconstr
    simpa using hfirst
  exact txOutRefData_injective hdata

end JulcVerification.StateThread
