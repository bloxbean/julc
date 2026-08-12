import GeneratedSchemas

namespace JulcGenerated.C3CodecTests

open CardanoLedgerApi.IsData.Class
open JulcGenerated.Schemas
open PlutusCore.Data (Data)

def chain : Chain :=
  .ChainCons 1 (.ChainCons 2 (.ChainCons 3 (.ChainCons 4 .ChainEnd)))

example : IsData.fromData (α := Chain) (IsData.toData chain) = some chain := rfl

example : IsData.fromData (α := Chain)
    (Data.Constr 1 [Data.I 1, Data.Constr 9 []]) = none := rfl

example : IsData.fromData (α := Chain)
    (Data.Constr 1 [Data.I 1]) = none := rfl

example : IsData.fromData (α := Chain)
    (Data.Constr 1 [Data.Constr 0 [], Data.Constr 0 []]) = none := rfl

/-- The explicit recursive-domain decoder bound is distinct from CEK fuel. -/
example : decodeChain 1 (IsData.toData chain) = none := rfl

def optionalNode : Node := .Cons 1 (some (.Cons 2 (some .End)))

example : IsData.fromData (α := Node) (IsData.toData optionalNode) =
    some optionalNode := rfl

example : IsData.fromData (α := Node)
    (Data.Constr 1 [Data.I 1, Data.Constr 0 [Data.I 2]]) = none := rfl

def tree : Tree :=
  .Tree ⟨[.Tree ⟨[]⟩, .Tree ⟨[.Tree ⟨[]⟩]⟩]⟩

example : IsData.fromData (α := Tree) (IsData.toData tree) = some tree := rfl

example : IsData.fromData (α := Tree)
    (Data.Constr 0 [Data.List [Data.I 1]]) = none := rfl

def duplicateGraph : Graph :=
  .Graph ⟨[(1, .Graph ⟨[]⟩), (1, .Graph ⟨[]⟩)]⟩

example : IsData.fromData (α := Graph) (IsData.toData duplicateGraph) =
    some duplicateGraph := rfl

example : IsData.fromData (α := Graph)
    (Data.Constr 0 [Data.Map [(Data.I 1, Data.I 2)]]) = none := rfl

example : IsData.toData duplicateGraph = Data.Constr 0 [Data.Map [
    (Data.I 1, Data.Constr 0 [Data.Map []]),
    (Data.I 1, Data.Constr 0 [Data.Map []])]] := rfl

def mutualPath : Left := .ToRight (.ToLeft .LeftEnd)

example : IsData.fromData (α := Left) (IsData.toData mutualPath) =
    some mutualPath := rfl

/-- Deliberately vulnerable codec control: it ignores tags and fields. -/
def permissiveDecodeChain : Data → Option Chain
  | Data.Constr _ _ => some .ChainEnd
  | _ => none

example : permissiveDecodeChain (Data.Constr 9 [Data.I 1]) =
    some .ChainEnd := rfl

example : IsData.fromData (α := Chain) (Data.Constr 9 [Data.I 1]) = none := rfl

/-- A sufficient structural decoder depth for a finite `Chain`. -/
def chainDecodeDepth : Chain → Nat
  | .ChainEnd => 1
  | .ChainCons _ next => chainDecodeDepth next + 1

/--
Unbounded, kernel-checked codec composition by structural induction. This is
separate from, and stronger than, testing a fixed `--recursive-depth` sample;
it is not by itself a theorem about the imported validator UPLC.
-/
theorem decodeChain_encodeChain (value : Chain) :
    decodeChain (chainDecodeDepth value) (encodeChain value) = some value := by
  induction value with
  | ChainEnd => rfl
  | ChainCons head tail ih =>
      simp [chainDecodeDepth, encodeChain, decodeChain, mkDataConstr, ih]

private theorem one_le_dataDepth (value : Data) : 1 ≤ dataDepth value := by
  cases value <;> simp [dataDepth]

/--
The generated `IsData` instance's finite-`Data` depth is sufficient for every
`Chain`, not only the explicit structural depth used above.
-/
theorem chainIsData_roundtrip (value : Chain) :
    IsData.fromData (IsData.toData value) = some value := by
  change decodeChain (dataDepth (encodeChain value) + 1) (encodeChain value) = some value
  induction value with
  | ChainEnd => rfl
  | ChainCons head tail ih =>
      simp [encodeChain, decodeChain, dataDepth, dataListDepth, mkDataConstr,
        Nat.max_eq_right (one_le_dataDepth (encodeChain tail)), ih]

end JulcGenerated.C3CodecTests
