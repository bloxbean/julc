import GeneratedSchemas

namespace JulcGenerated.C2CodecTests

open CardanoLedgerApi.IsData.Class
open JulcGenerated.Schemas
open PlutusCore.Data (Data)
open PlutusCore.Integer (Integer)

-- JuLC Bool is strict about constructor tag, arity, and outer shape.
example : IsData.fromData (α := Bool) (Data.Constr 0 []) = some false := rfl
example : IsData.fromData (α := Bool) (Data.Constr 1 []) = some true := rfl
example : IsData.fromData (α := Bool) (Data.Constr 2 []) = none := rfl
example : IsData.fromData (α := Bool) (Data.Constr 1 [Data.I 1]) = none := rfl
example : IsData.fromData (α := Bool) (Data.I 1) = none := rfl

-- JuLC Optional uses Some = Constr 0 [value], None = Constr 1 [].
example : IsData.fromData (α := Option Integer) (Data.Constr 0 [Data.I 7]) =
    some (some 7) := rfl
example : IsData.fromData (α := Option Integer) (Data.Constr 1 []) =
    some none := rfl
example : IsData.fromData (α := Option Integer) (Data.Constr 0 []) = none := rfl
example : IsData.fromData (α := Option Integer) (Data.Constr 1 [Data.I 7]) = none := rfl
example : IsData.fromData (α := Option Integer) (Data.Constr 2 []) = none := rfl
example : IsData.fromData (α := Option Integer) (Data.Constr 0 [Data.B "x"]) = none := rfl

-- List decoding validates every element and round-trips without normalization.
def integerList : JulcList Integer := ⟨[1, 2]⟩
example : IsData.fromData (IsData.toData integerList) = some integerList := rfl
example : IsData.fromData (α := JulcList Integer)
    (Data.List [Data.I 1, Data.B "bad"]) = none := rfl
example : IsData.fromData (α := JulcList Integer) (Data.Map []) = none := rfl

-- Map decoding validates both sides and preserves duplicate keys and order.
def duplicateMap : JulcMap Integer Bool := ⟨[(1, false), (1, true)]⟩
example : IsData.fromData (IsData.toData duplicateMap) = some duplicateMap := rfl
example : IsData.toData duplicateMap = Data.Map [
    (Data.I 1, Data.Constr 0 []),
    (Data.I 1, Data.Constr 1 [])] := rfl
example : IsData.fromData (α := JulcMap Integer Bool)
    (Data.Map [(Data.B "bad", Data.Constr 1 [])]) = none := rfl
example : IsData.fromData (α := JulcMap Integer Bool)
    (Data.Map [(Data.I 1, Data.I 1)]) = none := rfl
example : IsData.fromData (α := JulcMap Integer Bool) (Data.List []) = none := rfl

-- Arbitrary nonrecursive nesting fails if any nested value is malformed.
abbrev Nested := Option (JulcList (JulcMap Integer Bool))
def nested : Nested := some ⟨[⟨[(1, true)]⟩]⟩
example : IsData.fromData (IsData.toData nested) = some nested := rfl
example : IsData.fromData (α := Nested)
    (Data.Constr 0 [Data.List [Data.Map [(Data.I 1, Data.Constr 2 [])]]]) = none := rfl

end JulcGenerated.C2CodecTests
