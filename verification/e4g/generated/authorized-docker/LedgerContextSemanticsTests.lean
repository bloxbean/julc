/- Kernel-reduced controls for schema-5 ledger-context meanings. -/
import SecurityProperty

namespace JulcGenerated.LedgerContextSemanticsTests

open CardanoLedgerApi.IsData.Class
open CardanoLedgerApi.V3
open JulcGenerated.UserProperty
open PlutusCore.Data (Data)

def ref0 : TxOutRef := ⟨"tx", 0⟩
def ref1 : TxOutRef := ⟨"tx", 1⟩
def scriptAddress : CardanoLedgerApi.V2.Address :=
  ⟨.ScriptCredential "script", none⟩
def stakedScriptAddress : CardanoLedgerApi.V2.Address :=
  ⟨.ScriptCredential "script", some (.StakingPtr 1 2 3)⟩
def pubKeyAddress : CardanoLedgerApi.V2.Address :=
  ⟨.PubKeyCredential "key", none⟩
def outputAt (address : CardanoLedgerApi.V2.Address) (datum : Data) :
    CardanoLedgerApi.V2.TxOut :=
  ⟨address, [], .OutputDatum datum, none⟩
def firstInput : TxInInfo := ⟨ref0, outputAt scriptAddress (Data.I 1)⟩
def duplicateInput : TxInInfo :=
  ⟨ref0, outputAt stakedScriptAddress (Data.I 2)⟩
def publicInput : TxInInfo := ⟨ref1, outputAt pubKeyAddress (Data.I 3)⟩
def spendingPurpose : ScriptPurpose := .Spending ref0
def votingPurpose : ScriptPurpose :=
  .Voting (.StakePoolVoter "pool")
def redeemerEntries : RedeemerMap :=
  [(votingPurpose, Data.I 90),
   (spendingPurpose, Data.I 10),
   (spendingPurpose, Data.I 20)]
def datumEntries : CardanoLedgerApi.V2.DatumMap :=
  [("datum", Data.I 10), ("datum", Data.I 20)]

example : resolveInput ref0 [firstInput, duplicateInput] =
    some firstInput := by native_decide
example : resolveInput ref1 [firstInput] = none := by native_decide
example : findScriptInputs "script" [publicInput, firstInput] =
    [firstInput] := by native_decide
example : findPubKeyInputs "key" [firstInput, publicInput] =
    [publicInput] := by native_decide
example : scriptAddress != stakedScriptAddress := by native_decide

example : ((.SpendingScript ref0 (some (Data.I 7)) : ScriptInfo).toScriptPurpose) =
    spendingPurpose := by rfl
example : julcMapLookupFirst redeemerEntries spendingPurpose =
    some (Data.I 10) := by native_decide
example : julcMapLookupAll redeemerEntries spendingPurpose =
    [Data.I 10, Data.I 20] := by native_decide
example : julcMapCountKey redeemerEntries spendingPurpose = 2 := by
  native_decide
example : julcMapContainsKey redeemerEntries votingPurpose = true := by
  native_decide
example : julcMapLookupFirst redeemerEntries votingPurpose =
    some (Data.I 90) := by native_decide
example : findRedeemer spendingPurpose redeemerEntries =
    julcMapLookupFirst redeemerEntries spendingPurpose := by native_decide
example : CardanoLedgerApi.V2.findDatum "datum" datumEntries =
    julcMapLookupFirst datumEntries "datum" := by native_decide

example : (IsData.fromData (Data.Constr 0 []) :
    Option CardanoLedgerApi.V2.OutputDatum) =
    some .NoOutputDatum := by rfl
example : (IsData.fromData (Data.Constr 1 [Data.B "hash"]) :
    Option CardanoLedgerApi.V2.OutputDatum) =
    some (.OutputDatumHash "hash") := by rfl
example : (IsData.fromData (Data.Constr 2 [Data.I 7]) :
    Option CardanoLedgerApi.V2.OutputDatum) =
    some (.OutputDatum (Data.I 7)) := by rfl
example : (IsData.fromData (Data.Constr 2 []) :
    Option CardanoLedgerApi.V2.OutputDatum) = none := by rfl
example : (IsData.fromData (Data.Constr 1 [Data.I 7]) :
    Option CardanoLedgerApi.V2.OutputDatum) = none := by rfl

def txInfo (inputs : List TxInInfo)
    (outputs : List CardanoLedgerApi.V2.TxOut) : TxInfo :=
  ⟨inputs, [], outputs, 1, [], [], [], Data.Constr 0 [], [], [], [],
    "tx", [], [], Data.Constr 1 [], Data.Constr 1 []⟩
def spendingContext (inputs : List TxInInfo)
    (outputs : List CardanoLedgerApi.V2.TxOut) : ScriptContext :=
  ⟨txInfo inputs outputs, Data.Constr 0 [], .SpendingScript ref0 none⟩

example : findOwnInput (spendingContext
    [firstInput, duplicateInput] []) = some firstInput := by native_decide
example : findOwnInput (spendingContext [publicInput] []) = none := by
  native_decide
example : (julcContinuingOutputs (spendingContext [firstInput]
    [outputAt stakedScriptAddress (Data.I 2),
     outputAt scriptAddress (Data.I 3)])).items =
    [outputAt scriptAddress (Data.I 3)] := by native_decide
example : (julcContinuingOutputs (spendingContext [publicInput]
    [outputAt scriptAddress (Data.I 3)])).items = [] := by native_decide

end JulcGenerated.LedgerContextSemanticsTests
