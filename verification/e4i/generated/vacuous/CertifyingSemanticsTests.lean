/- Executable controls for pinned V3 certificate kinds and indexing. -/
import SecurityProperty

namespace JulcGenerated.CertifyingSemanticsTests

open CardanoLedgerApi.IsData.Class
open CardanoLedgerApi.V3
open PlutusCore.Data (Data)

def scriptCredential : CardanoLedgerApi.V2.Credential :=
  .ScriptCredential "script"
def keyHash : CardanoLedgerApi.V2.PubKeyHash := "key"
def otherKeyHash : CardanoLedgerApi.V2.PubKeyHash := "other"
def drepCredential : CardanoLedgerApi.V2.Credential :=
  .PubKeyCredential "drep"
def credentialDRep : CardanoLedgerApi.V3.TxCert.DRep :=
  .DRep drepCredential
def abstainDRep : CardanoLedgerApi.V3.TxCert.DRep :=
  .DRepAlwaysAbstain
def noConfidenceDRep : CardanoLedgerApi.V3.TxCert.DRep :=
  .DRepAlwaysNoConfidence
def stakeDelegatee : CardanoLedgerApi.V3.TxCert.Delegatee :=
  .DelegStake keyHash
def voteDelegatee : CardanoLedgerApi.V3.TxCert.Delegatee :=
  .DelegVote credentialDRep
def stakeVoteDelegatee : CardanoLedgerApi.V3.TxCert.Delegatee :=
  .DelegStakeVote keyHash abstainDRep

def cert0 : TxCert := .TxCertRegStaking scriptCredential (some 1)
def cert1 : TxCert := .TxCertUnRegStaking scriptCredential none
def cert2 : TxCert :=
  .TxCertDelegStaking scriptCredential voteDelegatee
def cert3 : TxCert :=
  .TxCertRegDeleg scriptCredential stakeVoteDelegatee 3
def cert4 : TxCert := .TxCertRegDRep scriptCredential 1
def cert5 : TxCert := .TxCertUpdateDRep scriptCredential
def cert6 : TxCert := .TxCertUnRegDRep scriptCredential 1
def cert7 : TxCert := .TxCertPoolRegister keyHash otherKeyHash
def cert8 : TxCert := .TxCertPoolRetire keyHash 8
def cert9 : TxCert :=
  .TxCertAuthHotCommittee scriptCredential drepCredential
def cert10 : TxCert := .TxCertResignColdCommittee scriptCredential

def constructorTag : TxCert → Int
  | .TxCertRegStaking .. => 0
  | .TxCertUnRegStaking .. => 1
  | .TxCertDelegStaking .. => 2
  | .TxCertRegDeleg .. => 3
  | .TxCertRegDRep .. => 4
  | .TxCertUpdateDRep .. => 5
  | .TxCertUnRegDRep .. => 6
  | .TxCertPoolRegister .. => 7
  | .TxCertPoolRetire .. => 8
  | .TxCertAuthHotCommittee .. => 9
  | .TxCertResignColdCommittee .. => 10

example : [constructorTag cert0, constructorTag cert1,
    constructorTag cert2, constructorTag cert3,
    constructorTag cert4, constructorTag cert5,
    constructorTag cert6, constructorTag cert7,
    constructorTag cert8, constructorTag cert9,
    constructorTag cert10] =
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10] := by native_decide

def encodedTagAndArity (cert : TxCert) : Option (Int × Nat) :=
  match IsData.toData cert with
  | Data.Constr tag fields => some (tag, fields.length)
  | _ => none

example : [encodedTagAndArity cert0, encodedTagAndArity cert1,
    encodedTagAndArity cert2, encodedTagAndArity cert3,
    encodedTagAndArity cert4, encodedTagAndArity cert5,
    encodedTagAndArity cert6, encodedTagAndArity cert7,
    encodedTagAndArity cert8, encodedTagAndArity cert9,
    encodedTagAndArity cert10] =
    [some (0, 2), some (1, 2), some (2, 2), some (3, 3),
     some (4, 2), some (5, 1), some (6, 2), some (7, 2),
     some (8, 2), some (9, 2), some (10, 1)] := by native_decide

def decodeCert (data : Data) : Option TxCert :=
  IsData.fromData data

def shortenCertificateData (cert : TxCert) : Data :=
  match IsData.toData cert with
  | Data.Constr tag fields => Data.Constr tag fields.dropLast
  | data => data

def trailCertificateData (cert : TxCert) : Data :=
  match IsData.toData cert with
  | Data.Constr tag fields => Data.Constr tag (fields ++ [Data.I 0])
  | data => data

def corruptFirstCertificatePayload (cert : TxCert) : Data :=
  match IsData.toData cert with
  | Data.Constr tag (_ :: rest) => Data.Constr tag (Data.I 0 :: rest)
  | data => data

/- Every pinned constructor round-trips and rejects short, trailing,
   or wrong-kind first payloads. The first payload is a Credential or
   byte string in all eleven constructors, never an Integer. -/
example : [decodeCert (IsData.toData cert0),
    decodeCert (IsData.toData cert1), decodeCert (IsData.toData cert2),
    decodeCert (IsData.toData cert3), decodeCert (IsData.toData cert4),
    decodeCert (IsData.toData cert5), decodeCert (IsData.toData cert6),
    decodeCert (IsData.toData cert7), decodeCert (IsData.toData cert8),
    decodeCert (IsData.toData cert9), decodeCert (IsData.toData cert10)] =
    [some cert0, some cert1, some cert2, some cert3, some cert4,
     some cert5, some cert6, some cert7, some cert8, some cert9,
     some cert10] := by native_decide
example : [decodeCert (shortenCertificateData cert0),
    decodeCert (shortenCertificateData cert1),
    decodeCert (shortenCertificateData cert2),
    decodeCert (shortenCertificateData cert3),
    decodeCert (shortenCertificateData cert4),
    decodeCert (shortenCertificateData cert5),
    decodeCert (shortenCertificateData cert6),
    decodeCert (shortenCertificateData cert7),
    decodeCert (shortenCertificateData cert8),
    decodeCert (shortenCertificateData cert9),
    decodeCert (shortenCertificateData cert10)] =
    [none, none, none, none, none, none, none, none, none, none,
     none] := by native_decide
example : [decodeCert (trailCertificateData cert0),
    decodeCert (trailCertificateData cert1),
    decodeCert (trailCertificateData cert2),
    decodeCert (trailCertificateData cert3),
    decodeCert (trailCertificateData cert4),
    decodeCert (trailCertificateData cert5),
    decodeCert (trailCertificateData cert6),
    decodeCert (trailCertificateData cert7),
    decodeCert (trailCertificateData cert8),
    decodeCert (trailCertificateData cert9),
    decodeCert (trailCertificateData cert10)] =
    [none, none, none, none, none, none, none, none, none, none,
     none] := by native_decide
example : [decodeCert (corruptFirstCertificatePayload cert0),
    decodeCert (corruptFirstCertificatePayload cert1),
    decodeCert (corruptFirstCertificatePayload cert2),
    decodeCert (corruptFirstCertificatePayload cert3),
    decodeCert (corruptFirstCertificatePayload cert4),
    decodeCert (corruptFirstCertificatePayload cert5),
    decodeCert (corruptFirstCertificatePayload cert6),
    decodeCert (corruptFirstCertificatePayload cert7),
    decodeCert (corruptFirstCertificatePayload cert8),
    decodeCert (corruptFirstCertificatePayload cert9),
    decodeCert (corruptFirstCertificatePayload cert10)] =
    [none, none, none, none, none, none, none, none, none, none,
     none] := by native_decide
example : decodeCert (Data.Constr 11 []) = none := by rfl

def regStakingZero : TxCert :=
  .TxCertRegStaking scriptCredential (some 0)
def regStakingNone : TxCert :=
  .TxCertRegStaking scriptCredential none
example : decodeCert (IsData.toData regStakingZero) =
    some regStakingZero := by native_decide
example : decodeCert (IsData.toData regStakingNone) =
    some regStakingNone := by native_decide
example : IsData.toData regStakingZero !=
    IsData.toData regStakingNone := by native_decide
example : decodeCert (Data.Constr 4
    [IsData.toData scriptCredential,
     Data.Constr 0 [Data.I 1]]) = none := by native_decide

/- Payload positions are checked independently from constructor kind. -/
example : (match cert3 with
    | .TxCertRegDeleg credential target deposit =>
        credential == scriptCredential &&
        target == stakeVoteDelegatee && deposit == 3
    | _ => false) = true := by native_decide
example : (match cert7 with
    | .TxCertPoolRegister pool vrf =>
        pool == keyHash && vrf == otherKeyHash
    | _ => false) = true := by native_decide
example : (match cert9 with
    | .TxCertAuthHotCommittee cold hot =>
        cold == scriptCredential && hot == drepCredential
    | _ => false) = true := by native_decide

/- Nested Delegatee and DRep tags, payloads, and strict arities. -/
example : (IsData.fromData (α := CardanoLedgerApi.V3.TxCert.DRep)
    (IsData.toData credentialDRep)) = some credentialDRep := by native_decide
example : (IsData.fromData (α := CardanoLedgerApi.V3.TxCert.DRep)
    (IsData.toData abstainDRep)) = some abstainDRep := by native_decide
example : (IsData.fromData (α := CardanoLedgerApi.V3.TxCert.DRep)
    (IsData.toData noConfidenceDRep)) = some noConfidenceDRep := by
  native_decide
example : (IsData.fromData (α := CardanoLedgerApi.V3.TxCert.Delegatee)
    (IsData.toData stakeDelegatee)) = some stakeDelegatee := by
  native_decide
example : (IsData.fromData (α := CardanoLedgerApi.V3.TxCert.Delegatee)
    (IsData.toData voteDelegatee)) = some voteDelegatee := by
  native_decide
example : (IsData.fromData (α := CardanoLedgerApi.V3.TxCert.Delegatee)
    (IsData.toData stakeVoteDelegatee)) = some stakeVoteDelegatee := by
  native_decide
example : (IsData.fromData
    (α := CardanoLedgerApi.V3.TxCert.DRep)
    (Data.Constr 1 [Data.I 0])) = none := by rfl
example : (IsData.fromData
    (α := CardanoLedgerApi.V3.TxCert.Delegatee)
    (Data.Constr 2 [Data.B "key"])) = none := by rfl

example : Contexts.isKnownCertificate cert5 0 [cert5] = true := by
  native_decide
example : Contexts.isKnownCertificate cert5 (-1) [cert5] = false := by
  native_decide
example : Contexts.isKnownCertificate cert5 1 [cert5] = false := by
  native_decide
example : Contexts.isKnownCertificate cert5 0 [cert4, cert5] = false := by
  native_decide
example : Contexts.isKnownCertificate cert5 1 [cert4, cert5] = true := by
  native_decide
example : Contexts.isKnownCertificate cert5 0 [cert5, cert5] = true := by
  native_decide
example : Contexts.isKnownCertificate cert5 1 [cert5, cert5] = true := by
  native_decide

example : (IsData.fromData (α := TxCert)
    (IsData.toData cert5)) = some cert5 := by native_decide
example : (IsData.fromData (α := TxCert)
    (Data.Constr 11 [])) = none := by rfl
example : (IsData.fromData (α := TxCert)
    (Data.Constr 5 [])) = none := by rfl
example : (IsData.fromData (α := TxCert)
    (Data.Constr 5 [Data.I 0])) = none := by rfl
example : (IsData.fromData (α := TxCert)
    (Data.Constr 8 [Data.B "key", Data.I 8, Data.I 9])) = none := by rfl
example : (IsData.fromData (α := TxCert)
    (Data.Constr 7 [Data.I 0, Data.B "vrf"])) = none := by rfl

end JulcGenerated.CertifyingSemanticsTests
