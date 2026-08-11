import PlutusCore.Default
import PlutusCore.UPLC.CekMachine
import PlutusCore.UPLC.Utils

namespace JulcVerification.CheckedExecution

open PlutusCore.Default
open PlutusCore.UPLC.CekMachine
open PlutusCore.UPLC.Term

/-- A bounded CEK result that keeps verification fuel exhaustion distinct. -/
inductive CheckedResult where
  | finished : State → CheckedResult
  | stepExhausted : State → CheckedResult
deriving Repr

/-- Run at most `fuel` CEK transitions without converting exhaustion to error. -/
def runStepsChecked
    (semanticsVariant : BuiltinSemanticsVariant)
    (state : State) : Nat → CheckedResult
  | 0 =>
      match state with
      | .Halt _ | .Error => .finished state
      | _ => .stepExhausted state
  | Nat.succ remaining =>
      match state with
      | .Halt _ | .Error => .finished state
      | _ => runStepsChecked semanticsVariant (step semanticsVariant state) remaining

/-- Execute one program under an explicit semantics variant and checked fuel. -/
def executeProgramChecked
    (semanticsVariant : BuiltinSemanticsVariant)
    (program : Program)
    (params : List Term)
    (fuel : Nat) : CheckedResult :=
  match program with
  | .Program _ body =>
      runStepsChecked semanticsVariant
        (initialState (applyParams body params)) fuel

/-- JuLC's current Plutus V3/PV11 verification profile. -/
def executePlutusV3Pv11Checked : Program → List Term → Nat → CheckedResult :=
  executeProgramChecked .defaultFunSemanticsVariantE

def isSuccessful : CheckedResult → Prop
  | .finished (.Halt _) => True
  | _ => False

def isEvaluationError : CheckedResult → Prop
  | .finished .Error => True
  | _ => False

def isStepExhausted : CheckedResult → Prop
  | .stepExhausted _ => True
  | _ => False

end JulcVerification.CheckedExecution
