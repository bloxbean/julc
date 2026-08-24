/- Kernel-reduced controls for schema-9 governance transaction data. -/
import SecurityProperty

namespace JulcGenerated.GovernanceSemanticsTests

open CardanoLedgerApi.V3
open CardanoLedgerApi.IsData.Class
open JulcGenerated.UserProperty
open PlutusCore.Data (Data)

def committeeCredential : CardanoLedgerApi.V2.Credential :=
  .PubKeyCredential "committee"
def drepCredential : CardanoLedgerApi.V2.Credential :=
  .ScriptCredential "drep"
def committeeVoter : Voter := .CommitteeVoter committeeCredential
def drepVoter : Voter := .DRepVoter drepCredential
def poolVoter : Voter := .StakePoolVoter "pool"
def actionId : GovernanceActionId := ⟨"tx", 2⟩
def otherActionId : GovernanceActionId := ⟨"other", 3⟩
def innerVotes : GovernanceVoteMap :=
  [(actionId, .VoteNo), (actionId, .VoteYes),
   (otherActionId, .Abstain)]
def votes : VoterMap :=
  [(poolVoter, innerVotes),
   (poolVoter, [(actionId, .Abstain)])]

example : IsData.toData (α := Vote) .VoteNo = Data.Constr 0 [] := by rfl
example : IsData.toData (α := Vote) .VoteYes = Data.Constr 1 [] := by rfl
example : IsData.toData (α := Vote) .Abstain = Data.Constr 2 [] := by rfl
example : (IsData.fromData (α := Vote) (Data.Constr 1 [Data.I 0])) = none := by rfl
example : (IsData.fromData (α := Vote) (Data.Constr 3 [])) = none := by rfl
example : IsData.toData committeeVoter =
    Data.Constr 0 [Data.Constr 0 [Data.B "committee"]] := by rfl
example : IsData.toData drepVoter =
    Data.Constr 1 [Data.Constr 1 [Data.B "drep"]] := by rfl
example : IsData.toData poolVoter = Data.Constr 2 [Data.B "pool"] := by rfl
def decodeVoter (data : Data) : Option Voter := IsData.fromData data
def shortenVoterData (voter : Voter) : Data :=
  match IsData.toData voter with
  | Data.Constr tag fields => Data.Constr tag fields.dropLast
  | data => data
def trailVoterData (voter : Voter) : Data :=
  match IsData.toData voter with
  | Data.Constr tag fields => Data.Constr tag (fields ++ [Data.I 0])
  | data => data
def corruptVoterPayload (voter : Voter) : Data :=
  match IsData.toData voter with
  | Data.Constr tag (_ :: rest) => Data.Constr tag (Data.I 0 :: rest)
  | data => data
example : [decodeVoter (IsData.toData committeeVoter),
    decodeVoter (IsData.toData drepVoter),
    decodeVoter (IsData.toData poolVoter)] =
    [some committeeVoter, some drepVoter, some poolVoter] := by native_decide
example : [decodeVoter (shortenVoterData committeeVoter),
    decodeVoter (shortenVoterData drepVoter),
    decodeVoter (shortenVoterData poolVoter)] =
    [none, none, none] := by native_decide
example : [decodeVoter (trailVoterData committeeVoter),
    decodeVoter (trailVoterData drepVoter),
    decodeVoter (trailVoterData poolVoter)] =
    [none, none, none] := by native_decide
example : [decodeVoter (corruptVoterPayload committeeVoter),
    decodeVoter (corruptVoterPayload drepVoter),
    decodeVoter (corruptVoterPayload poolVoter)] =
    [none, none, none] := by native_decide
example : decodeVoter (Data.Constr 3 [Data.B "unknown"]) = none := by rfl
example : IsData.toData actionId = Data.Constr 0 [Data.B "tx", Data.I 2] := by rfl
def protocolVersion : ProtocolVersion := ⟨11, 1⟩
example : IsData.toData protocolVersion =
    Data.Constr 0 [Data.I 11, Data.I 1] := by rfl
example : CardanoLedgerApi.V3.Contexts.isKnownVoter poolVoter votes = true := by
  native_decide
example : CardanoLedgerApi.V3.Contexts.isKnownVoter drepVoter votes = false := by
  native_decide
example : votes.length = 2 := by native_decide
example : votes =
    [(poolVoter, innerVotes),
     (poolVoter, [(actionId, .Abstain)])] := by rfl
example : julcMapLookupFirst votes poolVoter = some innerVotes := by
  native_decide
example : julcMapLookupAll votes poolVoter =
    [innerVotes, [(actionId, .Abstain)]] := by native_decide
example : julcMapCountKey votes poolVoter = 2 := by native_decide
example : julcMapLookupFirst innerVotes actionId = some .VoteNo := by
  native_decide
example : julcMapLookupAll innerVotes actionId =
    [.VoteNo, .VoteYes] := by native_decide
example : julcMapCountKey innerVotes actionId = 2 := by native_decide
example : (IsData.fromData (α := GovernanceVoteMap)
    (Data.Map [(Data.I 0, Data.Constr 0 [])])) = none := by rfl
example : (IsData.fromData (α := VoterMap)
    (Data.Map [(IsData.toData poolVoter, Data.List [])])) = none := by rfl

def optionalPrior : Option GovernanceActionId := some actionId
def constitutionScript : Option CardanoLedgerApi.V2.ScriptHash :=
  some "constitution"
def withdrawalCredential : CardanoLedgerApi.V2.Credential :=
  .PubKeyCredential "withdrawal"
def committeeCold : CardanoLedgerApi.V2.Credential :=
  .ScriptCredential "cold"
def committeeNew : CardanoLedgerApi.V2.Credential :=
  .PubKeyCredential "new"
def action0 : GovernanceAction := .ParameterChange optionalPrior
  (Data.Map [(Data.I 1, Data.I 10)]) constitutionScript
def action1 : GovernanceAction :=
  .HardForkInitiation optionalPrior ⟨11, 0⟩
def action2 : GovernanceAction := .TreasuryWithdrawals
  [(withdrawalCredential, 5)] constitutionScript
def action3 : GovernanceAction := .NoConfidence optionalPrior
def action4 : GovernanceAction := .UpdateCommittee optionalPrior
  [committeeCold] [(committeeNew, 100)]
  (Data.Constr 0 [Data.I 1, Data.I 2])
def action5 : GovernanceAction :=
  .NewConstitution optionalPrior constitutionScript
def action6 : GovernanceAction := .InfoAction

def actionTagAndArity (action : GovernanceAction) : Option (Int × Nat) :=
  match IsData.toData action with
  | Data.Constr tag fields => some (tag, fields.length)
  | _ => none
example : [actionTagAndArity action0, actionTagAndArity action1,
    actionTagAndArity action2, actionTagAndArity action3,
    actionTagAndArity action4, actionTagAndArity action5,
    actionTagAndArity action6] =
    [some (0, 3), some (1, 2), some (2, 2), some (3, 1),
     some (4, 4), some (5, 2), some (6, 0)] := by native_decide

def decodeAction (data : Data) : Option GovernanceAction :=
  IsData.fromData data
def shortenActionData (action : GovernanceAction) : Data :=
  match IsData.toData action with
  | Data.Constr tag fields => Data.Constr tag fields.dropLast
  | data => data
def trailActionData (action : GovernanceAction) : Data :=
  match IsData.toData action with
  | Data.Constr tag fields => Data.Constr tag (fields ++ [Data.I 0])
  | data => data
def corruptFirstActionPayload (action : GovernanceAction) : Data :=
  match IsData.toData action with
  | Data.Constr tag (_ :: rest) => Data.Constr tag (Data.I 0 :: rest)
  | data => data

example : [decodeAction (IsData.toData action0),
    decodeAction (IsData.toData action1),
    decodeAction (IsData.toData action2),
    decodeAction (IsData.toData action3),
    decodeAction (IsData.toData action4),
    decodeAction (IsData.toData action5),
    decodeAction (IsData.toData action6)] =
    [some action0, some action1, some action2, some action3,
     some action4, some action5, some action6] := by native_decide
/- InfoAction has arity zero, so there is no shorter encoding. -/
example : [decodeAction (shortenActionData action0),
    decodeAction (shortenActionData action1),
    decodeAction (shortenActionData action2),
    decodeAction (shortenActionData action3),
    decodeAction (shortenActionData action4),
    decodeAction (shortenActionData action5)] =
    [none, none, none, none, none, none] := by native_decide
example : [decodeAction (trailActionData action0),
    decodeAction (trailActionData action1),
    decodeAction (trailActionData action2),
    decodeAction (trailActionData action3),
    decodeAction (trailActionData action4),
    decodeAction (trailActionData action5),
    decodeAction (trailActionData action6)] =
    [none, none, none, none, none, none, none] := by native_decide
example : [decodeAction (corruptFirstActionPayload action0),
    decodeAction (corruptFirstActionPayload action1),
    decodeAction (corruptFirstActionPayload action2),
    decodeAction (corruptFirstActionPayload action3),
    decodeAction (corruptFirstActionPayload action4),
    decodeAction (corruptFirstActionPayload action5)] =
    [none, none, none, none, none, none] := by native_decide
example : decodeAction (Data.Constr 7 []) = none := by rfl
example : (match action1 with
    | .HardForkInitiation (some prior) version =>
        prior == actionId && version == (⟨11, 0⟩ : ProtocolVersion)
    | _ => false) = true := by native_decide

def proposal : ProposalProcedure :=
  ⟨10, .PubKeyCredential "return", IsData.toData action1⟩
def decodeProposal (data : Data) : Option ProposalProcedure :=
  IsData.fromData data
example : decodeProposal (IsData.toData proposal) = some proposal := by
  native_decide
example : decodeProposal (Data.Constr 1
    [Data.I 10, Data.Constr 0 [Data.B "return"],
     IsData.toData action1]) = none := by rfl
example : decodeProposal (Data.Constr 0
    [Data.I 10, Data.Constr 0 [Data.B "return"]]) = none := by rfl
example : decodeProposal (Data.Constr 0
    [Data.I 10, Data.Constr 0 [Data.B "return"],
     IsData.toData action1, Data.I 0]) = none := by rfl
example : decodeProposal (Data.Constr 0
    [Data.B "deposit", Data.Constr 0 [Data.B "return"],
     IsData.toData action1]) = none := by rfl
example : decodeProposal (Data.Constr 0
    [Data.I 10, Data.I 0, IsData.toData action1]) = none := by rfl
example : (IsData.fromData proposal.ppGovernanceAction :
    Option GovernanceAction) = some action1 := by native_decide
example : CardanoLedgerApi.V3.Contexts.isKnownProposal
    proposal (-1) [proposal] = false := by native_decide
example : CardanoLedgerApi.V3.Contexts.isKnownProposal
    proposal 0 [proposal, proposal] = true := by native_decide
example : CardanoLedgerApi.V3.Contexts.isKnownProposal
    proposal 2 [proposal, proposal] = false := by native_decide

end JulcGenerated.GovernanceSemanticsTests
