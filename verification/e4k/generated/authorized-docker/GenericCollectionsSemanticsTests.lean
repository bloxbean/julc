/- Kernel-reduced controls for schema-4 collection meanings. -/
import SecurityProperty

namespace JulcGenerated.GenericCollectionsSemanticsTests

open JulcGenerated.Schemas
open JulcGenerated.UserProperty
open PlutusCore.Data (Data)

example : (!false) = true := by rfl
example : (true && false) = false := by rfl
example : (true || false) = true := by rfl
example : ((-3 + 5 : Int) = 2) := by rfl

example : julcListAt ([10, 20] : List Int) (-1) = none := by rfl
example : julcListAt ([10, 20] : List Int) 2 = none := by rfl
example : julcListAt ([10, 20] : List Int) 1 = some 20 := by rfl
example : julcListCount (fun value : Int => value == 1)
    [1, 2, 1] = 2 := by simp [julcListCount]
example : julcListContains ([1, 2] : List Int) 2 = true := by
  simp [julcListContains, julcStructuralEq,
    CardanoLedgerApi.IsData.Class.instIsDataInteger]

example : julcMapCountKey ([(1, 10), (1, 20), (2, 30)] :
    List (Int × Int)) 1 = 2 := by
  simp [julcMapCountKey, julcListCount, julcStructuralEq,
    CardanoLedgerApi.IsData.Class.instIsDataInteger]
example : julcMapLookupFirst ([(1, 10), (1, 20)] :
    List (Int × Int)) 1 = some 10 := by
  simp [julcMapLookupFirst, julcStructuralEq,
    CardanoLedgerApi.IsData.Class.instIsDataInteger]
example : julcMapLookupAll ([(1, 10), (1, 20), (2, 30)] :
    List (Int × Int)) 1 = [10, 20] := by
  simp [julcMapLookupAll, julcStructuralEq,
    CardanoLedgerApi.IsData.Class.instIsDataInteger]

example : julcStructuralEq
    (Data.Map [(Data.I 1, Data.I 10), (Data.I 1, Data.I 20)])
    (Data.Map [(Data.I 1, Data.I 10), (Data.I 1, Data.I 20)]) = true := by
  simp [julcStructuralEq, CardanoLedgerApi.IsData.Class.instIsDataData]
example : julcStructuralEq
    (Data.Map [(Data.I 1, Data.I 10), (Data.I 1, Data.I 20)])
    (Data.Map [(Data.I 1, Data.I 20), (Data.I 1, Data.I 10)]) = false := by
  simp [julcStructuralEq, CardanoLedgerApi.IsData.Class.instIsDataData]

end JulcGenerated.GenericCollectionsSemanticsTests
