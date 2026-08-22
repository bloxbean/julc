/- Kernel-reduced controls for schema-8 raw and extensional Value meanings. -/
import SecurityProperty

namespace JulcGenerated.ValueAlgebraSemanticsTests

open CardanoLedgerApi.V3
open JulcGenerated.UserProperty
open PlutusCore.Data (Data)

def policyA : CardanoLedgerApi.V2.CurrencySymbol := "policy-a"
def policyB : CardanoLedgerApi.V2.CurrencySymbol := "policy-b"
def tokenA : CardanoLedgerApi.V2.TokenName := "token-a"
def tokenB : CardanoLedgerApi.V2.TokenName := "token-b"

def duplicateValue : CardanoLedgerApi.V2.Value :=
  [(Data.B policyA, Data.Map [(Data.B tokenA, Data.I 2),
                             (Data.B tokenA, Data.I 3)]),
   (Data.B policyA, Data.Map [(Data.B tokenA, Data.I (-1))])]
def summedValue : CardanoLedgerApi.V2.Value :=
  [(Data.B policyA, Data.Map [(Data.B tokenA, Data.I 4)])]
def reorderedLeft : CardanoLedgerApi.V2.Value :=
  [(Data.B policyA, Data.Map [(Data.B tokenA, Data.I 1)]),
   (Data.B policyB, Data.Map [(Data.B tokenB, Data.I 2)])]
def reorderedRight : CardanoLedgerApi.V2.Value :=
  [(Data.B policyB, Data.Map [(Data.B tokenB, Data.I 2)]),
   (Data.B policyA, Data.Map [(Data.B tokenA, Data.I 1)])]
def zeroSum : CardanoLedgerApi.V2.Value :=
  [(Data.B policyA, Data.Map [(Data.B tokenA, Data.I 2),
                             (Data.B tokenA, Data.I (-2))])]
def malformedPolicy : CardanoLedgerApi.V2.Value :=
  [(Data.I 1, Data.Map [])]
def malformedTokenMap : CardanoLedgerApi.V2.Value :=
  [(Data.B policyA, Data.I 1)]
def malformedTokenName : CardanoLedgerApi.V2.Value :=
  [(Data.B policyA, Data.Map [(Data.I 1, Data.I 2)])]
def malformedQuantity : CardanoLedgerApi.V2.Value :=
  [(Data.B policyA, Data.Map [(Data.B tokenA, Data.B "bad")])]
def explicitZero : CardanoLedgerApi.V2.Value :=
  [(Data.B policyA, Data.Map [(Data.B tokenA, Data.I 0)])]

example : CardanoLedgerApi.V2.valueOf policyA tokenA duplicateValue = 2 := by
  native_decide
example : CardanoLedgerApi.V2.valueOf policyA tokenA malformedPolicy = 0 := by
  native_decide
example : CardanoLedgerApi.V2.valueOf policyA tokenA malformedTokenMap = 0 := by
  native_decide
example : CardanoLedgerApi.V2.valueOf policyA tokenA malformedTokenName = 0 := by
  native_decide
example : CardanoLedgerApi.V2.valueOf policyA tokenA malformedQuantity = 0 := by
  native_decide
example : julcValueQuantitySumStrict policyA tokenA duplicateValue = some 4 := by
  native_decide
example : julcValueQuantitySumStrict policyB tokenB duplicateValue = none := by
  native_decide
example : julcValueQuantitySumStrict policyA tokenA explicitZero = some 0 := by
  native_decide
example : julcValueQuantitySumStrict policyA tokenA malformedPolicy = none := by
  native_decide
example : julcValueQuantitySumStrict policyA tokenA malformedTokenMap = none := by
  native_decide
example : julcValueQuantitySumStrict policyA tokenA malformedTokenName = none := by
  native_decide
example : julcValueQuantitySumStrict policyA tokenA malformedQuantity = none := by
  native_decide

example : reorderedLeft != reorderedRight := by native_decide
example : julcValueExtensionalEq reorderedLeft reorderedRight = true := by
  native_decide
example : julcValueExtensionalEq duplicateValue summedValue = true := by
  native_decide
example : julcValueExtensionalEq zeroSum [] = true := by native_decide
example : julcValueExtensionalEq malformedPolicy [] = false := by native_decide
example : julcValuePointwiseLe [] summedValue = true := by native_decide
example : julcValuePointwiseLt [] summedValue = true := by native_decide

example : julcValueAddStrict
    (CardanoLedgerApi.V2.singleton policyA tokenA 2)
    (CardanoLedgerApi.V2.singleton policyA tokenA 3) =
    some [(Data.B policyA, Data.Map [(Data.B tokenA, Data.I 2)]),
          (Data.B policyA, Data.Map [(Data.B tokenA, Data.I 3)])] := by
  native_decide
example : julcValueSingletonStrict policyA tokenA 2 =
    some (CardanoLedgerApi.V2.singleton policyA tokenA 2) := by
  native_decide
example : julcValueNegateStrict summedValue =
    some [(Data.B policyA, Data.Map [(Data.B tokenA, Data.I (-4))])] := by
  native_decide
example : julcValueScaleStrict 0 summedValue =
    some [(Data.B policyA, Data.Map [(Data.B tokenA, Data.I 0)])] := by
  native_decide
example : julcValueAddStrict malformedPolicy [] = none := by native_decide
example : CardanoLedgerApi.V2.merge
    (CardanoLedgerApi.V2.singleton policyA tokenA 2)
    (CardanoLedgerApi.V2.singleton policyA tokenA (-2)) = [] := by
  native_decide
example : CardanoLedgerApi.V2.merge
    (CardanoLedgerApi.V2.singleton policyB tokenB 2)
    (CardanoLedgerApi.V2.singleton policyA tokenA 1) =
    [(Data.B policyA, Data.Map [(Data.B tokenA, Data.I 1)]),
     (Data.B policyB, Data.Map [(Data.B tokenB, Data.I 2)])] := by
  native_decide
example : CardanoLedgerApi.V2.add policyA tokenA (-4) summedValue =
    [(Data.B policyA, Data.Map [])] := by
  native_decide

def misplacedAda : CardanoLedgerApi.V2.Value :=
  [(Data.B policyA, Data.Map [(Data.B tokenA, Data.I 1)]),
   (Data.B "", Data.Map [(Data.B "", Data.I 9)])]
example : CardanoLedgerApi.V2.lovelaceOf misplacedAda = 0 := by rfl
example : CardanoLedgerApi.V2.lovelaceOf
    ((Data.B "", Data.Map [(Data.B "", Data.I 9)]) :: misplacedAda) = 9 := by
  rfl

def fullAddress : CardanoLedgerApi.V2.Address :=
  ⟨.ScriptCredential "script", none⟩
def stakedSamePaymentCredential : CardanoLedgerApi.V2.Address :=
  ⟨.ScriptCredential "script", some (.StakingPtr 1 2 3)⟩
def otherAddress : CardanoLedgerApi.V2.Address :=
  ⟨.PubKeyCredential "other", none⟩
def valueOutput (address : CardanoLedgerApi.V2.Address) (quantity : Int) :
    CardanoLedgerApi.V2.TxOut :=
  ⟨address, CardanoLedgerApi.V2.singleton policyA tokenA quantity,
    .NoOutputDatum, none⟩
def fullOutput := valueOutput fullAddress 1
def stakedOutput := valueOutput stakedSamePaymentCredential 2
def otherOutput := valueOutput otherAddress 4
def valueInput (index quantity : Int) : TxInInfo :=
  ⟨⟨"tx", index⟩, valueOutput fullAddress quantity⟩

example : (List.filter (fun output =>
    output.txOutAddress == fullAddress)
    [fullOutput, stakedOutput, otherOutput]).length = 1 := by
  native_decide
example : (List.filter (fun output =>
    output.txOutAddress.addressCredential ==
      fullAddress.addressCredential)
    [fullOutput, stakedOutput, otherOutput]).length = 2 := by
  native_decide
example : CardanoLedgerApi.V2.valueOf policyA tokenA
    (julcAggregateOutputValues [fullOutput, stakedOutput, otherOutput]) = 7 := by
  native_decide
example : CardanoLedgerApi.V2.valueOf policyA tokenA
    (julcAggregateInputValues [valueInput 0 1, valueInput 0 2]) = 3 := by
  native_decide
example : CardanoLedgerApi.V1.Contexts.validTxOutValue
    (CardanoLedgerApi.V2.lovelaceValue 1) = true := by native_decide
example : CardanoLedgerApi.V1.Contexts.validTxOutValue
    (CardanoLedgerApi.V2.singleton policyA tokenA 1) = false := by
  native_decide
example : CardanoLedgerApi.V3.Contexts.validMintValue
    (CardanoLedgerApi.V2.singleton policyA tokenA 1) = true := by
  native_decide
example : CardanoLedgerApi.V3.Contexts.validMintValue
    (CardanoLedgerApi.V2.lovelaceValue 1) = false := by native_decide

def valueTxInfo (inputs : List TxInInfo)
    (outputs : List CardanoLedgerApi.V2.TxOut)
    (mint : MintValue) (fee : Int) : TxInfo :=
  ⟨inputs, [], outputs, fee, mint, [], [], Data.Constr 0 [], [], [], [],
    "tx", [], [], Data.Constr 1 [], Data.Constr 1 []⟩
def valueContext (inputs : List TxInInfo)
    (outputs : List CardanoLedgerApi.V2.TxOut)
    (mint : MintValue) (fee : Int) : ScriptContext :=
  ⟨valueTxInfo inputs outputs mint fee, Data.Constr 0 [],
    .SpendingScript ⟨"tx", 0⟩ none⟩
example : CardanoLedgerApi.V3.Contexts.isBalanced (valueContext
    [valueInput 0 1] [valueOutput fullAddress 1] [] 0) = true := by
  native_decide
example : CardanoLedgerApi.V3.Contexts.isBalanced (valueContext
    [valueInput 0 1] [valueOutput fullAddress 2] [] 0) = false := by
  native_decide

end JulcGenerated.ValueAlgebraSemanticsTests
