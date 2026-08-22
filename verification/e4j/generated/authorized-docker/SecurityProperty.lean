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

/- Schema-8 Value operations keep raw and extensional meanings separate. -/
def julcValueQuantitySumStrictPresence (policy token : ByteString)
    (value : CardanoLedgerApi.V2.Value) : Option (Int × Bool) :=
  let rec sumTokens (entries : List (Data × Data)) (acc : Int)
      (found : Bool) : Option (Int × Bool) :=
    match entries with
    | [] => some (acc, found)
    | (Data.B actualToken, Data.I quantity) :: rest =>
        let isMatch := actualToken == token
        sumTokens rest (if isMatch then acc + quantity else acc)
          (found || isMatch)
    | _ => none
  let rec visit (entries : CardanoLedgerApi.V2.Value)
      (acc : Int) (found : Bool) : Option (Int × Bool) :=
    match entries with
    | [] => some (acc, found)
    | (Data.B actualPolicy, Data.Map tokens) :: rest =>
        match sumTokens tokens 0 false with
        | none => none
        | some (quantity, tokenFound) =>
            let isMatch := actualPolicy == policy
            visit rest
              (if isMatch then acc + quantity else acc)
              (found || (isMatch && tokenFound))
    | _ => none
  visit value 0 false

def julcValueQuantitySumStrict (policy token : ByteString)
    (value : CardanoLedgerApi.V2.Value) : Option Int :=
  match julcValueQuantitySumStrictPresence policy token value with
  | some (quantity, true) => some quantity
  | some (_, false) => none
  | none => none

def julcValueQuantitySumStrictOrZero (policy token : ByteString)
    (value : CardanoLedgerApi.V2.Value) : Option Int :=
  match julcValueQuantitySumStrictPresence policy token value with
  | some (quantity, _) => some quantity
  | none => none

def julcValueSupportStrict (value : CardanoLedgerApi.V2.Value) :
    Option (List (ByteString × ByteString)) :=
  let rec tokenKeys (policy : ByteString) (entries : List (Data × Data))
      (acc : List (ByteString × ByteString)) :=
    match entries with
    | [] => some acc
    | (Data.B token, Data.I _) :: rest =>
        tokenKeys policy rest ((policy, token) :: acc)
    | _ => none
  let rec visit (entries : CardanoLedgerApi.V2.Value)
      (acc : List (ByteString × ByteString)) :=
    match entries with
    | [] => some acc
    | (Data.B policy, Data.Map tokens) :: rest =>
        match tokenKeys policy tokens acc with
        | some keys => visit rest keys
        | none => none
    | _ => none
  visit value []

def julcValueExtensionalEq (left right : CardanoLedgerApi.V2.Value) : Bool :=
  match julcValueSupportStrict left, julcValueSupportStrict right with
  | some leftKeys, some rightKeys =>
      (leftKeys ++ rightKeys).all (fun key =>
        julcValueQuantitySumStrictOrZero key.1 key.2 left ==
          julcValueQuantitySumStrictOrZero key.1 key.2 right)
  | _, _ => false

def julcValuePointwiseLe (left right : CardanoLedgerApi.V2.Value) : Bool :=
  match julcValueSupportStrict left, julcValueSupportStrict right with
  | some leftKeys, some rightKeys =>
      (leftKeys ++ rightKeys).all (fun key =>
        match julcValueQuantitySumStrictOrZero key.1 key.2 left,
            julcValueQuantitySumStrictOrZero key.1 key.2 right with
        | some l, some r => l <= r
        | _, _ => false)
  | _, _ => false

def julcValuePointwiseLt (left right : CardanoLedgerApi.V2.Value) : Bool :=
  julcValuePointwiseLe left right && !julcValueExtensionalEq left right

def julcValueMapStrict (factor : Int)
    (value : CardanoLedgerApi.V2.Value) :
    Option CardanoLedgerApi.V2.Value :=
  let rec mapTokens : List (Data × Data) → Option (List (Data × Data))
    | [] => some []
    | (Data.B token, Data.I quantity) :: rest =>
        match mapTokens rest with
        | some mapped => some ((Data.B token, Data.I (factor * quantity)) :: mapped)
        | none => none
    | _ => none
  let rec visit : CardanoLedgerApi.V2.Value →
      Option CardanoLedgerApi.V2.Value
    | [] => some []
    | (Data.B policy, Data.Map tokens) :: rest =>
        match mapTokens tokens, visit rest with
        | some mappedTokens, some mappedRest =>
            some ((Data.B policy, Data.Map mappedTokens) :: mappedRest)
        | _, _ => none
    | _ => none
  visit value

def julcValueSingletonStrict (policy token : ByteString) (quantity : Int) :
    Option CardanoLedgerApi.V2.Value :=
  some (CardanoLedgerApi.V2.singleton policy token quantity)

def julcValueValidateStrict (value : CardanoLedgerApi.V2.Value) :
    Option CardanoLedgerApi.V2.Value :=
  match julcValueSupportStrict value with
  | some _ => some value
  | none => none

def julcValueAddStrict (left right : CardanoLedgerApi.V2.Value) :
    Option CardanoLedgerApi.V2.Value :=
  match julcValueSupportStrict left, julcValueSupportStrict right with
  | some _, some _ => some (left ++ right)
  | _, _ => none

def julcValueNegateStrict (value : CardanoLedgerApi.V2.Value) :
    Option CardanoLedgerApi.V2.Value := julcValueMapStrict (-1) value

def julcValueScaleStrict (factor : Int)
    (value : CardanoLedgerApi.V2.Value) :
    Option CardanoLedgerApi.V2.Value := julcValueMapStrict factor value

def julcAggregateInputValues (inputs : List TxInInfo) :
    CardanoLedgerApi.V2.Value :=
  inputs.foldl (fun acc input =>
    CardanoLedgerApi.V2.merge input.txInInfoResolved.txOutValue acc) []

def julcAggregateOutputValues (outputs : List CardanoLedgerApi.V2.TxOut) :
    CardanoLedgerApi.V2.Value :=
  outputs.foldl (fun acc output =>
    CardanoLedgerApi.V2.merge output.txOutValue acc) []

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

def dslGuarantee_value_first_match_payment (ctx : ScriptContext) : Bool :=
  (match (julcListAt (((⟨((ctx).scriptContextTxInfo).txInfoOutputs⟩ : JulcList CardanoLedgerApi.V2.TxOut)).items) (0)) with | some v0 => (valueOf (("\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31\x31" : ByteString)) (("\x74\x6f\x6b\x65\x6e" : ByteString)) ((v0).txOutValue) >= 10) | none => false)

def dslProperty_value_first_match_payment (ctx : ScriptContext) : Prop :=
  dslGuarantee_value_first_match_payment ctx = true

end JulcGenerated.UserProperty
