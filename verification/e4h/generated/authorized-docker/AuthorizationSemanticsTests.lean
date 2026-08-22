/- Kernel-reduced controls for schema-6 distinct authorization meanings. -/
import SecurityProperty

namespace JulcGenerated.AuthorizationSemanticsTests

open CardanoLedgerApi.V3
open JulcGenerated.UserProperty

def keyA : CardanoLedgerApi.V2.PubKeyHash := "aaaaaaaaaaaaaaaaaaaaaaaaaaaa"
def keyB : CardanoLedgerApi.V2.PubKeyHash := "bbbbbbbbbbbbbbbbbbbbbbbbbbbb"
def keyC : CardanoLedgerApi.V2.PubKeyHash := "cccccccccccccccccccccccccccc"
def outsider : CardanoLedgerApi.V2.PubKeyHash :=
  "xxxxxxxxxxxxxxxxxxxxxxxxxxxx"

example : julcAnySigned [keyA, keyB] [keyB] = true := by decide
example : julcAnySigned [keyA, keyB] [] = false := by decide
example : julcAllSigned [keyA, keyB] [keyB, keyA] = true := by decide
example : julcAllSigned [keyA, keyB] [keyA] = false := by decide
example : julcAllSigned [] [outsider] = true := by decide
example : julcNoneSigned [keyA, keyB] [keyC] = true := by decide
example : julcNoneSigned [keyA, keyB] [keyB] = false := by decide

example : julcAtLeastSigned 0 [keyA] [] = true := by decide
example : julcAtLeastSigned 2 [keyA, keyB, keyC] [keyA] = false := by
  decide
example : julcAtLeastSigned 2 [keyA, keyB, keyC] [keyB, keyA] = true := by
  decide
example : julcExactlySigned 2 [keyA, keyB, keyC]
    [keyB, keyA] = true := by decide
example : julcExactlySigned 2 [keyA, keyB, keyC]
    [keyA, keyB, keyC] = false := by decide

example : julcExactlySigned 2 [keyA, keyA, keyB]
    [keyA, keyA, keyB] = true := by decide
example : julcExactlySigned 2 [keyA, keyB]
    [keyA, keyA] = false := by decide
example : julcAtLeastSigned 2 [keyA, keyB]
    [keyB, keyA] = julcAtLeastSigned 2 [keyB, keyA]
      [keyA, keyB] := by decide

example : julcNoUnexpectedSigners [keyA, keyB]
    [keyB, keyA, keyA] = true := by decide
example : julcNoUnexpectedSigners [keyA, keyB]
    [keyA, outsider] = false := by decide
example : julcExactSignerSet [keyA, keyB]
    [keyB, keyA, keyA] = true := by decide
example : julcExactlySigned 2 [keyA, keyB]
    [keyA, keyB, outsider] = true := by decide
example : (julcExactlySigned 2 [keyA, keyB]
    [keyA, keyB, outsider] &&
    julcNoUnexpectedSigners [keyA, keyB]
      [keyA, keyB, outsider]) = false := by decide

example : julcAnySigned [keyA] [keyB, keyA] =
    List.elem keyA [keyB, keyA] := by decide

end JulcGenerated.AuthorizationSemanticsTests
