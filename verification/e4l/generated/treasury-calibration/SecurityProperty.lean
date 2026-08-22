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

/- Schema-9 preserves both levels of the list-backed voter map. -/
def julcVoterMap (votes : VoterMap) :
    JulcMap Voter (JulcMap GovernanceActionId Vote) :=
  ⟨votes.map (fun entry => (entry.1, ⟨entry.2⟩))⟩

def julcIsKnownVoter (voter : Voter)
    (votes : JulcMap Voter (JulcMap GovernanceActionId Vote)) : Bool :=
  votes.entries.any (fun entry => entry.1 == voter)

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

/- ADR-027 reviewed raw-data adapters. Raw Data never escapes these APIs. -/
def julcDecodeValidity (raw : Data) :
    Option CardanoLedgerApi.V1.Time.POSIXTimeRange :=
  IsData.fromData raw

def julcValidityDecoderValid (raw : Data) : Bool :=
  (julcDecodeValidity raw).isSome

def julcValidityCanonical (raw : Data) : Bool :=
  match julcDecodeValidity raw with
  | some range => IsData.toData range == raw
  | none => false

def julcValidityIsEmpty (raw : Data) : Bool :=
  match julcDecodeValidity raw with
  | some range => CardanoLedgerApi.V1.Time.isEmpty range
  | none => false

def julcValidityContains (time : Int) (raw : Data) : Bool :=
  match julcDecodeValidity raw with
  | some range => CardanoLedgerApi.V1.Time.contains time range
  | none => false

def julcValidityIncludes (outerRaw innerRaw : Data) : Bool :=
  match julcDecodeValidity outerRaw, julcDecodeValidity innerRaw with
  | some outer, some inner => CardanoLedgerApi.V1.Time.includes outer inner
  | _, _ => false

def julcValidityEntirelyBefore (time : Int) (raw : Data) : Bool :=
  match julcDecodeValidity raw with
  | some range => CardanoLedgerApi.V1.Time.isEntirelyBefore time range
  | none => false

def julcValidityEntirelyAfter (time : Int) (raw : Data) : Bool :=
  match julcDecodeValidity raw with
  | some range => CardanoLedgerApi.V1.Time.isEntirelyAfter time range
  | none => false

inductive JulcTreasuryState where
  | malformed : JulcTreasuryState
  | absent : JulcTreasuryState
  | present : Int → JulcTreasuryState
deriving Repr, DecidableEq

def julcDecodeTreasury : Data → JulcTreasuryState
  | Data.Constr 0 [Data.I amount] => .present amount
  | Data.Constr 1 [] => .absent
  | _ => .malformed

def julcTreasuryWellFormed (raw : Data) : Bool :=
  match julcDecodeTreasury raw with
  | .present _ | .absent => true
  | .malformed => false

def julcTreasuryAbsent (raw : Data) : Bool :=
  match julcDecodeTreasury raw with
  | .absent => true
  | _ => false

def julcTreasuryMalformed (raw : Data) : Bool :=
  match julcDecodeTreasury raw with
  | .malformed => true
  | _ => false

def julcChangedParameterIds (raw : Data) : Option (List Int) :=
  let rec decode : List (Data × Data) → Option (List Int)
    | [] => some []
    | (Data.I key, _) :: rest =>
        match decode rest with
        | some keys => some (key :: keys)
        | none => none
    | _ => none
  match raw with
  | Data.Map entries => decode entries
  | _ => none

def julcStrictlyAscending : List Int → Bool
  | [] | [_] => true
  | first :: second :: rest =>
      first < second && julcStrictlyAscending (second :: rest)

def julcChangedParametersWellFormed (raw : Data) : Bool :=
  (julcChangedParameterIds raw).isSome

def julcChangedParametersNonEmpty (raw : Data) : Bool :=
  match julcChangedParameterIds raw with
  | some (_ :: _) => true
  | _ => false

def julcChangedParametersStrictlyAscendingUnique (raw : Data) : Bool :=
  match julcChangedParameterIds raw with
  | some keys => !keys.isEmpty && julcStrictlyAscending keys
  | none => false

def julcChangedParametersContainsId (raw : Data) (id : Int) : Bool :=
  match julcChangedParameterIds raw with
  | some keys => keys.contains id
  | none => false

def julcChangedParametersCountId (raw : Data) (id : Int) : Int :=
  match julcChangedParameterIds raw with
  | some keys => keys.foldl (fun count key =>
      if key == id then count + 1 else count) 0
  | none => 0

def julcNormalizeQuorum (numerator denominator : Int) : Int × Int :=
  let divisor : Int := Int.ofNat (Int.gcd numerator denominator)
  let sign : Int := if denominator < 0 then -1 else 1
  (numerator * sign / divisor, denominator * sign / divisor)

def julcDecodeQuorum : Data → Option (Int × Int)
  | Data.Constr 0 [Data.I numerator, Data.I denominator] =>
      if denominator == 0 then none
      else some (julcNormalizeQuorum numerator denominator)
  | _ => none

def julcQuorumDecoderValid (raw : Data) : Bool :=
  (julcDecodeQuorum raw).isSome

def julcQuorumCanonical (raw : Data) : Bool :=
  match julcDecodeQuorum raw with
  | some (numerator, denominator) =>
      Data.Constr 0 [Data.I numerator, Data.I denominator] == raw
  | none => false

def julcQuorumUnitInterval (raw : Data) : Bool :=
  match julcDecodeQuorum raw with
  | some (numerator, denominator) =>
      0 <= numerator && numerator <= denominator
  | none => false

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

def dslGuarantee_reviewed_treasury_calibration (ctx : ScriptContext) : Bool :=
  (julcTreasuryAbsent ((ctx).scriptContextTxInfo).txInfoTreasuryDonation && (match julcDecodeTreasury ((ctx).scriptContextTxInfo).txInfoCurrentTreasuryAmount with | .present v0 => (v0 == 100) | _ => false))

def dslProperty_reviewed_treasury_calibration (ctx : ScriptContext) : Prop :=
  dslGuarantee_reviewed_treasury_calibration ctx = true

end JulcGenerated.UserProperty
