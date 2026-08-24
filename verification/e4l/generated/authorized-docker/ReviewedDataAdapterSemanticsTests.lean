/- Kernel-reduced controls for ADR-027 reviewed raw-data meanings. -/
import SecurityProperty

namespace JulcGenerated.ReviewedDataAdapterSemanticsTests

open CardanoLedgerApi.IsData.Class
open CardanoLedgerApi.V3
open JulcGenerated.UserProperty
open PlutusCore.Data (Data)

namespace Time
open CardanoLedgerApi.V1.Time

def finiteClosed : POSIXTimeRange :=
  ⟨.FiniteLowerBound 10 true, .FiniteUpperBound 20 true⟩
def finiteOpen : POSIXTimeRange :=
  ⟨.FiniteLowerBound 10 false, .FiniteUpperBound 20 false⟩
def canonicalInfinite : Data := IsData.toData everything
def noncanonicalInfinite : Data := Data.Constr 0 [
  Data.Constr 0 [Data.Constr 0 [], IsData.toData false],
  Data.Constr 0 [Data.Constr 2 [], IsData.toData false]]

example : julcValidityDecoderValid (IsData.toData finiteClosed) = true := by rfl
example : julcValidityCanonical (IsData.toData finiteClosed) = true := by
  native_decide
example : julcValidityContains 10 (IsData.toData finiteClosed) = true := by rfl
example : julcValidityContains 10 (IsData.toData finiteOpen) = false := by rfl
example : julcValidityContains 20 (IsData.toData finiteOpen) = false := by rfl
example : julcValidityContains 15 (IsData.toData finiteOpen) = true := by rfl
example : julcValidityIncludes canonicalInfinite
    (IsData.toData finiteClosed) = true := by rfl
example : julcValidityEntirelyBefore 21
    (IsData.toData finiteClosed) = true := by rfl
example : julcValidityEntirelyAfter 9
    (IsData.toData finiteClosed) = true := by rfl
example : julcValidityDecoderValid noncanonicalInfinite = true := by rfl
example : julcValidityCanonical noncanonicalInfinite = false := by
  native_decide
example : julcValidityDecoderValid (Data.Constr 1 []) = false := by rfl
example : julcValidityDecoderValid (Data.Constr 0 []) = false := by rfl
example : julcValidityDecoderValid
    (Data.Constr 0 [Data.I 1, Data.I 2]) = false := by rfl
end Time

namespace Treasury
def negative : Data := Data.Constr 0 [Data.I (-1)]
def zero : Data := Data.Constr 0 [Data.I 0]
def positive : Data := Data.Constr 0 [Data.I 1]
def absent : Data := Data.Constr 1 []
def malformed : Data := Data.B "bad"

example : julcDecodeTreasury negative = .present (-1) := by rfl
example : julcDecodeTreasury zero = .present 0 := by rfl
example : julcDecodeTreasury positive = .present 1 := by rfl
example : julcDecodeTreasury absent = .absent := by rfl
example : julcDecodeTreasury malformed = .malformed := by rfl
example : julcDecodeTreasury (Data.Constr 0 []) = .malformed := by rfl
example : julcDecodeTreasury (Data.Constr 0 [Data.I 1, Data.I 2]) =
    .malformed := by rfl
example : julcDecodeTreasury (Data.Constr 1 [Data.I 1]) =
    .malformed := by rfl
example : julcDecodeTreasury (Data.Constr 0 [Data.B "bad"]) =
    .malformed := by rfl
example : julcTreasuryMalformed malformed = true := by rfl
example : julcTreasuryAbsent malformed = false := by rfl
example : julcTreasuryWellFormed malformed = false := by rfl
example : CardanoLedgerApi.V3.Contexts.validTreasuryAmount malformed = true :=
  by rfl
example : CardanoLedgerApi.V3.Contexts.validTreasuryDonation malformed = true :=
  by rfl
end Treasury

namespace Parameters
def empty : Data := Data.Map []
def ascending : Data := Data.Map
  [(Data.I 1, Data.B "a"), (Data.I 2, Data.I 9)]
def descending : Data := Data.Map
  [(Data.I 2, Data.B "a"), (Data.I 1, Data.I 9)]
def duplicate : Data := Data.Map
  [(Data.I 1, Data.B "a"), (Data.I 1, Data.I 9)]
def badKey : Data := Data.Map [(Data.B "key", Data.I 1)]

example : julcChangedParametersWellFormed empty = true := by rfl
example : julcChangedParametersNonEmpty empty = false := by rfl
example : julcChangedParameterIds ascending = some [1, 2] := by rfl
example : julcChangedParametersStrictlyAscendingUnique ascending = true := by rfl
example : julcChangedParametersStrictlyAscendingUnique descending = false := by rfl
example : julcChangedParametersStrictlyAscendingUnique duplicate = false := by rfl
example : julcChangedParametersCountId duplicate 1 = 2 := by rfl
example : julcChangedParametersContainsId ascending 2 = true := by rfl
example : julcChangedParametersWellFormed badKey = false := by rfl
example : julcChangedParametersWellFormed (Data.List []) = false := by rfl
end Parameters

namespace Quorum
def half : Data := Data.Constr 0 [Data.I 1, Data.I 2]
def unreducedHalf : Data := Data.Constr 0 [Data.I 2, Data.I 4]
def negativeDenominator : Data := Data.Constr 0 [Data.I (-1), Data.I (-2)]
def zeroDenominator : Data := Data.Constr 0 [Data.I 1, Data.I 0]

example : julcDecodeQuorum half = some (1, 2) := by native_decide
example : julcDecodeQuorum unreducedHalf = some (1, 2) := by native_decide
example : julcDecodeQuorum negativeDenominator = some (1, 2) := by native_decide
example : julcQuorumDecoderValid zeroDenominator = false := by rfl
example : julcQuorumCanonical half = true := by native_decide
example : julcQuorumCanonical unreducedHalf = false := by native_decide
example : julcQuorumCanonical negativeDenominator = false := by native_decide
example : julcQuorumUnitInterval half = true := by native_decide
example : julcQuorumUnitInterval
    (Data.Constr 0 [Data.I 3, Data.I 2]) = false := by native_decide
example : julcQuorumDecoderValid (Data.Constr 1 [Data.I 1, Data.I 2]) =
    false := by rfl
example : julcQuorumDecoderValid (Data.Constr 0 [Data.I 1]) = false := by rfl
example : julcQuorumDecoderValid
    (Data.Constr 0 [Data.B "x", Data.I 2]) = false := by rfl
end Quorum

end JulcGenerated.ReviewedDataAdapterSemanticsTests
