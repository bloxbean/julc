/- Generated E.5 exact-artifact transition relation; do not edit. -/
import Blaster.StateMachine
import PlutusCore.UPLC
import CardanoLedgerApi.V3
import GeneratedSchemas
import SecurityProperty

namespace JulcGenerated.StateMachineExperiment

open Blaster.StateMachine
open CardanoLedgerApi.IsData.Class
open CardanoLedgerApi.V3
open PlutusCore.Data (Data)
open PlutusCore.UPLC.Utils
open JulcGenerated.UserProperty

#import_uplc validator PlutusV3 double_cbor_hex
  "artifacts/exact-counter.compiledCode.hex"
#prep_uplc appliedValidator validator spendingInputs 3000

abbrev ContractState := JulcGenerated.Schemas.State
abbrev ContractEvent := JulcGenerated.Schemas.Action

structure MachineInput where
  datumData : Data
  redeemerData : Data
  context : ScriptContext

structure MachineState where
  value : Option ContractState
  depth : Int

def strictCurrent (input : MachineInput) : Option ContractState :=
  IsData.fromData input.datumData

def strictEvent (input : MachineInput) : Option ContractEvent :=
  IsData.fromData input.redeemerData

def unique {α : Type} : List α → Option α
  | [value] => some value
  | _ => none

def strictSuccessor (ctx : ScriptContext) : Option ContractState :=
  match unique (julcContinuingOutputs ctx).items with
  | some output =>
      match output.txOutDatum with
      | .OutputDatum raw => IsData.fromData raw
      | _ => none
  | none => none

def contextCarriesInput (input : MachineInput) : Bool :=
  input.context.scriptContextRedeemer == input.redeemerData &&
  match input.context.scriptContextScriptInfo with
  | .SpendingScript _ (some raw) => raw == input.datumData
  | _ => false

def reviewedDomain (ctx : ScriptContext) : Bool :=
  true

def admittedStep (input : MachineInput) (state : MachineState) : Prop :=
  match strictCurrent input, strictEvent input, strictSuccessor input.context,
      state.value with
  | some current, some event, some successor, some carried =>
      current = carried ∧
      contextCarriesInput input = true ∧
      selectedPurpose input.context = true ∧
      reviewedDomain input.context = true ∧
      PlutusCore.UPLC.Utils.isSuccessful
        (appliedValidator.prop input.context) ∧
      (state.depth = 0 → stateMachineInitial current = true) ∧
      stateMachineAssumption current event input.context = true
  | _, _, _, _ => False

def nextState (input : MachineInput) (state : MachineState) : MachineState :=
  { value := strictSuccessor input.context, depth := state.depth + 1 }

def stateInvariant (input : MachineInput) (state : MachineState) : Prop :=
  match strictEvent input, strictSuccessor input.context, state.value with
  | some event, some successor, some current =>
      stateMachineInvariant current = true ∧
      stateMachineTransitionInvariant current event successor input.context = true
  | _, _, _ => False

instance exactArtifactMachine : StateMachine MachineInput MachineState where
  init input := { value := strictCurrent input, depth := 0 }
  next := nextState
  assumptions := admittedStep
  invariants := stateInvariant

instance targetDepthMachine : StateMachine MachineInput MachineState where
  init input := { value := strictCurrent input, depth := 0 }
  next := nextState
  assumptions := admittedStep
  invariants _ state := state.depth < 1

end JulcGenerated.StateMachineExperiment
