/- Generated from admitted canonical typed DSL IR; do not edit. -/
import CardanoLedgerApi.V3
import GeneratedSchemas

namespace JulcGenerated.UserProperty

open CardanoLedgerApi.IsData.Class
open CardanoLedgerApi.Recursors
open CardanoLedgerApi.V3
open PlutusCore.Data (Data)
open PlutusCore.ByteString (ByteString)
open JulcGenerated.Schemas

def selectedPurpose (ctx : ScriptContext) : Bool :=
  match ctx.scriptContextScriptInfo with
  | .SpendingScript .. => true
  | _ => false

/- Schema-4 collection semantics preserve order and duplicates. -/
def julcStructuralEq [IsData α] (left right : α) : Bool :=
  IsData.toData left == IsData.toData right

def julcListContains [IsData α] (items : List α) (value : α) : Bool :=
  items.any (fun candidate => julcStructuralEq candidate value)

def julcListCount (predicate : α → Bool) : List α → Int
  | [] => 0
  | value :: rest =>
      (if predicate value then 1 else 0) + julcListCount predicate rest

def julcListAt (items : List α) (index : Int) : Option α :=
  if index < 0 then none else items.get? index.toNat

def julcMapContainsKey [IsData κ]
    (entries : List (κ × υ)) (key : κ) : Bool :=
  entries.any (fun entry => julcStructuralEq entry.1 key)

def julcMapCountKey [IsData κ]
    (entries : List (κ × υ)) (key : κ) : Int :=
  julcListCount (fun entry => julcStructuralEq entry.1 key) entries

def julcMapLookupFirst [IsData κ]
    (entries : List (κ × υ)) (key : κ) : Option υ :=
  match entries.find? (fun entry => julcStructuralEq entry.1 key) with
  | some entry => some entry.2
  | none => none

def julcMapLookupAll [IsData κ]
    (entries : List (κ × υ)) (key : κ) : List υ :=
  entries.filterMap (fun entry =>
    if julcStructuralEq entry.1 key then some entry.2 else none)

/-- Outputs at the complete address of the first resolved own input.
    Missing own input yields an empty ordered result. -/
def julcContinuingOutputs (ctx : ScriptContext) :
    JulcList CardanoLedgerApi.V2.TxOut :=
  match findOwnInput ctx with
  | some own =>
      ⟨List.filter (fun output : CardanoLedgerApi.V2.TxOut =>
        output.txOutAddress ==
          own.txInInfoResolved.txOutAddress)
        ctx.scriptContextTxInfo.txInfoOutputs⟩
  | none => ⟨[]⟩

/- Schema-6 authorization is set-like only within these relations. -/
def julcAuthorizationContains
    (key : CardanoLedgerApi.V2.PubKeyHash)
    (keys : List CardanoLedgerApi.V2.PubKeyHash) : Bool :=
  keys.any (fun candidate => candidate == key)

def julcDistinctKeyHashes :
    List CardanoLedgerApi.V2.PubKeyHash →
      List CardanoLedgerApi.V2.PubKeyHash
  | [] => []
  | key :: rest =>
      let distinctRest := julcDistinctKeyHashes rest
      if julcAuthorizationContains key distinctRest then
        distinctRest
      else
        key :: distinctRest

def julcSignedAuthorityCount
    (authorities signers : List CardanoLedgerApi.V2.PubKeyHash) : Int :=
  julcListCount
    (fun authority => julcAuthorizationContains authority signers)
    (julcDistinctKeyHashes authorities)

def julcAnySigned
    (authorities signers : List CardanoLedgerApi.V2.PubKeyHash) : Bool :=
  julcSignedAuthorityCount authorities signers >= 1

def julcAllSigned
    (authorities signers : List CardanoLedgerApi.V2.PubKeyHash) : Bool :=
  (julcDistinctKeyHashes authorities).all
    (fun authority => julcAuthorizationContains authority signers)

def julcNoneSigned
    (authorities signers : List CardanoLedgerApi.V2.PubKeyHash) : Bool :=
  julcSignedAuthorityCount authorities signers == 0

def julcAtLeastSigned (threshold : Int)
    (authorities signers : List CardanoLedgerApi.V2.PubKeyHash) : Bool :=
  julcSignedAuthorityCount authorities signers >= threshold

def julcExactlySigned (threshold : Int)
    (authorities signers : List CardanoLedgerApi.V2.PubKeyHash) : Bool :=
  julcSignedAuthorityCount authorities signers == threshold

def julcNoUnexpectedSigners
    (authorities signers : List CardanoLedgerApi.V2.PubKeyHash) : Bool :=
  signers.all
    (fun signer => julcAuthorizationContains signer authorities)

def julcExactSignerSet
    (authorities signers : List CardanoLedgerApi.V2.PubKeyHash) : Bool :=
  julcAllSigned authorities signers &&
    julcNoUnexpectedSigners authorities signers

/--
Solver-compatible necessary ledger conditions. Balance, voter-map, and
optional treasury checks are omitted because pinned Blaster cannot
translate them in this theorem premise. This predicate therefore admits
a superset of ledger-valid contexts; proving over it is stronger. The
separate kernel bridge proves inclusion of the pinned V3 domain.
-/
def blasterValidTxInfo (ctx : ScriptContext) : Bool :=
  CardanoLedgerApi.V3.Contexts.validInputs ctx &&
  CardanoLedgerApi.V3.Contexts.validReferenceInputs ctx &&
  CardanoLedgerApi.V3.Contexts.validOutputs
      ctx.scriptContextTxInfo.txInfoOutputs &&
  ctx.scriptContextTxInfo.txInfoFee > 0 &&
  CardanoLedgerApi.V3.Contexts.validMintValue
      ctx.scriptContextTxInfo.txInfoMint &&
  CardanoLedgerApi.V3.Contexts.validWithdrawals
      ctx.scriptContextTxInfo.txInfoWdrl &&
  CardanoLedgerApi.V2.validTxRange
      ctx.scriptContextTxInfo.txInfoValidRange &&
  CardanoLedgerApi.V2.validSigners
      ctx.scriptContextTxInfo.txInfoSignatories &&
  CardanoLedgerApi.V3.Contexts.validRedeemerMap
      ctx.scriptContextTxInfo.txInfoRedeemers &&
  CardanoLedgerApi.V2.validDatumMap
      ctx.scriptContextTxInfo.txInfoData

def blasterValidSpendingContext (ctx : ScriptContext) : Bool :=
  match ctx.scriptContextScriptInfo with
  | .SpendingScript .. =>
      CardanoLedgerApi.V3.Contexts.validScriptInfo ctx &&
        blasterValidTxInfo ctx
  | _ => false

def dslGuarantee_authorization_vulnerable_threshold (ctx : ScriptContext) : Bool :=
  julcExactlySigned 2 ([("\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41\x41" : ByteString), ("\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42\x42" : ByteString), ("\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43\x43" : ByteString)]) ctx.scriptContextTxInfo.txInfoSignatories

def dslProperty_authorization_vulnerable_threshold (ctx : ScriptContext) : Prop :=
  dslGuarantee_authorization_vulnerable_threshold ctx = true

end JulcGenerated.UserProperty
