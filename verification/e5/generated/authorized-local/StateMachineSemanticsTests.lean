/- Kernel-reduced controls for generated total helper behavior. -/
import StateMachineModel

namespace JulcGenerated.StateMachineExperiment.Controls

open JulcGenerated.StateMachineExperiment

example : unique ([] : List Int) = none := by rfl
example : unique [1] = some 1 := by rfl
example : unique [1, 2] = none := by rfl
example (input : MachineInput) (state : MachineState) :
    (strictSuccessor input.context = none) →
    (nextState input state).value = none := by
  intro missing
  simp [nextState, missing]

end JulcGenerated.StateMachineExperiment.Controls
