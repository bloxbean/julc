package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LedgerTypesTest {

    // --- Test helpers ---

    static byte[] bytes28() { var b = new byte[28]; Arrays.fill(b, (byte) 0xAA); return b; }
    static byte[] bytes28b() { var b = new byte[28]; Arrays.fill(b, (byte) 0xBB); return b; }
    static byte[] bytes32() { var b = new byte[32]; Arrays.fill(b, (byte) 0xCC); return b; }
    static byte[] bytes32b() { var b = new byte[32]; Arrays.fill(b, (byte) 0xDD); return b; }
    static PubKeyHash pkh() { return new PubKeyHash(bytes28()); }
    static PubKeyHash pkh2() { return new PubKeyHash(bytes28b()); }
    static ScriptHash sh() { return new ScriptHash(bytes28()); }
    static TxId txId() { return new TxId(bytes32()); }
    static DatumHash dh() { return new DatumHash(bytes32()); }
    static PolicyId pid() { return new PolicyId(bytes28()); }
    static TokenName tn() { return new TokenName("token".getBytes()); }

    // --- Credential ---

    @Nested
    class CredentialTests {
        @Test
        void pubKeyCredentialRoundTrip() {
            var c = new Credential.PubKeyCredential(pkh());
            assertEquals(c, Credential.fromPlutusData(c.toPlutusData()));
        }

        @Test
        void scriptCredentialRoundTrip() {
            var c = new Credential.ScriptCredential(sh());
            assertEquals(c, Credential.fromPlutusData(c.toPlutusData()));
        }

        @Test
        void pubKeyCredentialTag() {
            var data = new Credential.PubKeyCredential(pkh()).toPlutusData();
            assertInstanceOf(PlutusData.ConstrData.class, data);
            assertEquals(0, ((PlutusData.ConstrData) data).tag());
        }

        @Test
        void scriptCredentialTag() {
            var data = new Credential.ScriptCredential(sh()).toPlutusData();
            assertInstanceOf(PlutusData.ConstrData.class, data);
            assertEquals(1, ((PlutusData.ConstrData) data).tag());
        }

        @Test
        void invalidTag() {
            assertThrows(IllegalArgumentException.class,
                    () -> Credential.fromPlutusData(new PlutusData.ConstrData(2, List.of())));
        }
    }

    // --- StakingCredential ---

    @Nested
    class StakingCredentialTests {
        @Test
        void stakingHashRoundTrip() {
            var sc = new StakingCredential.StakingHash(new Credential.PubKeyCredential(pkh()));
            assertEquals(sc, StakingCredential.fromPlutusData(sc.toPlutusData()));
        }

        @Test
        void stakingPtrRoundTrip() {
            var sc = new StakingCredential.StakingPtr(BigInteger.ONE, BigInteger.TWO, BigInteger.TEN);
            assertEquals(sc, StakingCredential.fromPlutusData(sc.toPlutusData()));
        }
    }

    // --- Address ---

    @Nested
    class AddressTests {
        @Test
        void withStakingRoundTrip() {
            var addr = new Address(
                    new Credential.PubKeyCredential(pkh()),
                    Optional.of(new StakingCredential.StakingHash(new Credential.PubKeyCredential(pkh()))));
            assertEquals(addr, Address.fromPlutusData(addr.toPlutusData()));
        }

        @Test
        void withoutStakingRoundTrip() {
            var addr = new Address(new Credential.ScriptCredential(sh()), Optional.empty());
            assertEquals(addr, Address.fromPlutusData(addr.toPlutusData()));
        }

        @Test
        void encodingStructure() {
            var addr = new Address(new Credential.PubKeyCredential(pkh()), Optional.empty());
            var data = addr.toPlutusData();
            assertInstanceOf(PlutusData.ConstrData.class, data);
            var constr = (PlutusData.ConstrData) data;
            assertEquals(0, constr.tag());
            assertEquals(2, constr.fields().size());
        }
    }

    // --- TxOutRef ---

    @Nested
    class TxOutRefTests {
        @Test
        void roundTrip() {
            var ref = new TxOutRef(txId(), BigInteger.valueOf(3));
            assertEquals(ref, TxOutRef.fromPlutusData(ref.toPlutusData()));
        }

        @Test
        void zeroIndex() {
            var ref = new TxOutRef(txId(), BigInteger.ZERO);
            assertEquals(ref, TxOutRef.fromPlutusData(ref.toPlutusData()));
        }
    }

    // --- Value ---

    @Nested
    class ValueTests {
        @Test
        void zeroValue() {
            var v = Value.zero();
            assertTrue(v.inner().isEmpty());
        }

        @Test
        void lovelaceOnly() {
            var v = Value.lovelace(BigInteger.valueOf(2_000_000));
            assertEquals(BigInteger.valueOf(2_000_000), v.lovelaceOf());
        }

        @Test
        void singleton() {
            var v = Value.singleton(pid(), tn(), BigInteger.TEN);
            assertEquals(BigInteger.TEN, v.assetOf(pid(), tn()));
            assertEquals(BigInteger.ZERO, v.lovelaceOf());
        }

        @Test
        void merge() {
            var a = Value.lovelace(BigInteger.valueOf(100));
            var b = Value.lovelace(BigInteger.valueOf(200));
            var merged = a.merge(b);
            assertEquals(BigInteger.valueOf(300), merged.lovelaceOf());
        }

        @Test
        void mergeMultiAsset() {
            var a = Value.singleton(pid(), tn(), BigInteger.valueOf(5));
            var b = Value.singleton(pid(), tn(), BigInteger.valueOf(3));
            assertEquals(BigInteger.valueOf(8), a.merge(b).assetOf(pid(), tn()));
        }

        @Test
        void roundTrip() {
            var v = Value.lovelace(BigInteger.valueOf(1_500_000))
                    .merge(Value.singleton(pid(), tn(), BigInteger.valueOf(42)));
            assertEquals(v, Value.fromPlutusData(v.toPlutusData()));
        }

        @Test
        void zeroRoundTrip() {
            var v = Value.zero();
            assertEquals(v, Value.fromPlutusData(v.toPlutusData()));
        }

        @Test
        void encodingIsMap() {
            var data = Value.lovelace(BigInteger.ONE).toPlutusData();
            assertInstanceOf(PlutusData.MapData.class, data);
        }
    }

    // --- OutputDatum ---

    @Nested
    class OutputDatumTests {
        @Test
        void noOutputDatumRoundTrip() {
            var d = new OutputDatum.NoOutputDatum();
            assertEquals(d, OutputDatum.fromPlutusData(d.toPlutusData()));
        }

        @Test
        void outputDatumHashRoundTrip() {
            var d = new OutputDatum.OutputDatumHash(dh());
            assertEquals(d, OutputDatum.fromPlutusData(d.toPlutusData()));
        }

        @Test
        void outputDatumInlineRoundTrip() {
            var d = new OutputDatum.OutputDatumInline(PlutusData.integer(42));
            assertEquals(d, OutputDatum.fromPlutusData(d.toPlutusData()));
        }

        @Test
        void constrTags() {
            assertEquals(0, ((PlutusData.ConstrData) new OutputDatum.NoOutputDatum().toPlutusData()).tag());
            assertEquals(1, ((PlutusData.ConstrData) new OutputDatum.OutputDatumHash(dh()).toPlutusData()).tag());
            assertEquals(2, ((PlutusData.ConstrData) new OutputDatum.OutputDatumInline(PlutusData.integer(1)).toPlutusData()).tag());
        }
    }

    // --- TxOut ---

    @Nested
    class TxOutTests {
        @Test
        void roundTrip() {
            var out = new TxOut(
                    new Address(new Credential.PubKeyCredential(pkh()), Optional.empty()),
                    Value.lovelace(BigInteger.valueOf(2_000_000)),
                    new OutputDatum.NoOutputDatum(),
                    Optional.empty());
            assertEquals(out, TxOut.fromPlutusData(out.toPlutusData()));
        }

        @Test
        void withDatumAndRefScript() {
            var out = new TxOut(
                    new Address(new Credential.ScriptCredential(sh()), Optional.empty()),
                    Value.lovelace(BigInteger.ONE),
                    new OutputDatum.OutputDatumInline(PlutusData.integer(99)),
                    Optional.of(sh()));
            assertEquals(out, TxOut.fromPlutusData(out.toPlutusData()));
        }
    }

    // --- TxInInfo ---

    @Nested
    class TxInInfoTests {
        @Test
        void roundTrip() {
            var txIn = new TxInInfo(
                    new TxOutRef(txId(), BigInteger.ZERO),
                    new TxOut(
                            new Address(new Credential.PubKeyCredential(pkh()), Optional.empty()),
                            Value.lovelace(BigInteger.valueOf(5_000_000)),
                            new OutputDatum.NoOutputDatum(),
                            Optional.empty()));
            assertEquals(txIn, TxInInfo.fromPlutusData(txIn.toPlutusData()));
        }
    }

    // --- Interval ---

    @Nested
    class IntervalTests {
        @Test
        void alwaysRoundTrip() {
            var i = Interval.always();
            assertEquals(i, Interval.fromPlutusData(i.toPlutusData()));
        }

        @Test
        void neverRoundTrip() {
            var i = Interval.never();
            assertEquals(i, Interval.fromPlutusData(i.toPlutusData()));
        }

        @Test
        void afterRoundTrip() {
            var i = Interval.after(BigInteger.valueOf(1000));
            assertEquals(i, Interval.fromPlutusData(i.toPlutusData()));
        }

        @Test
        void beforeRoundTrip() {
            var i = Interval.before(BigInteger.valueOf(2000));
            assertEquals(i, Interval.fromPlutusData(i.toPlutusData()));
        }

        @Test
        void betweenRoundTrip() {
            var i = Interval.between(BigInteger.valueOf(100), BigInteger.valueOf(200));
            assertEquals(i, Interval.fromPlutusData(i.toPlutusData()));
        }

        @Test
        void alwaysStructure() {
            var i = Interval.always();
            assertInstanceOf(IntervalBoundType.NegInf.class, i.from().boundType());
            assertInstanceOf(IntervalBoundType.PosInf.class, i.to().boundType());
        }

        @Test
        void finiteRoundTrip() {
            var bound = new IntervalBound(new IntervalBoundType.Finite(BigInteger.valueOf(42)), false);
            assertEquals(bound, IntervalBound.fromPlutusData(bound.toPlutusData()));
        }

        @Test
        void intervalBoundTypeNegInf() {
            var t = new IntervalBoundType.NegInf();
            assertEquals(t, IntervalBoundType.fromPlutusData(t.toPlutusData()));
        }

        @Test
        void intervalBoundTypePosInf() {
            var t = new IntervalBoundType.PosInf();
            assertEquals(t, IntervalBoundType.fromPlutusData(t.toPlutusData()));
        }

        @Test
        void intervalBoundTypeFinite() {
            var t = new IntervalBoundType.Finite(BigInteger.valueOf(999));
            assertEquals(t, IntervalBoundType.fromPlutusData(t.toPlutusData()));
        }
    }

    // --- Governance Types ---

    @Nested
    class GovernanceTests {
        @Test
        void dRepCredentialRoundTrip() {
            var d = new DRep.DRepCredential(new Credential.PubKeyCredential(pkh()));
            assertEquals(d, DRep.fromPlutusData(d.toPlutusData()));
        }

        @Test
        void alwaysAbstainRoundTrip() {
            var d = new DRep.AlwaysAbstain();
            assertEquals(d, DRep.fromPlutusData(d.toPlutusData()));
        }

        @Test
        void alwaysNoConfidenceRoundTrip() {
            var d = new DRep.AlwaysNoConfidence();
            assertEquals(d, DRep.fromPlutusData(d.toPlutusData()));
        }

        @Test
        void delegateeStakeRoundTrip() {
            var d = new Delegatee.Stake(pkh());
            assertEquals(d, Delegatee.fromPlutusData(d.toPlutusData()));
        }

        @Test
        void delegateeVoteRoundTrip() {
            var d = new Delegatee.Vote(new DRep.AlwaysAbstain());
            assertEquals(d, Delegatee.fromPlutusData(d.toPlutusData()));
        }

        @Test
        void delegateeStakeVoteRoundTrip() {
            var d = new Delegatee.StakeVote(pkh(), new DRep.AlwaysNoConfidence());
            assertEquals(d, Delegatee.fromPlutusData(d.toPlutusData()));
        }

        @Test
        void voteNoRoundTrip() {
            assertEquals(new Vote.VoteNo(), Vote.fromPlutusData(new Vote.VoteNo().toPlutusData()));
        }

        @Test
        void voteYesRoundTrip() {
            assertEquals(new Vote.VoteYes(), Vote.fromPlutusData(new Vote.VoteYes().toPlutusData()));
        }

        @Test
        void abstainRoundTrip() {
            assertEquals(new Vote.Abstain(), Vote.fromPlutusData(new Vote.Abstain().toPlutusData()));
        }

        @Test
        void committeeVoterRoundTrip() {
            var v = new Voter.CommitteeVoter(new Credential.PubKeyCredential(pkh()));
            assertEquals(v, Voter.fromPlutusData(v.toPlutusData()));
        }

        @Test
        void dRepVoterRoundTrip() {
            var v = new Voter.DRepVoter(new Credential.ScriptCredential(sh()));
            assertEquals(v, Voter.fromPlutusData(v.toPlutusData()));
        }

        @Test
        void stakePoolVoterRoundTrip() {
            var v = new Voter.StakePoolVoter(pkh());
            assertEquals(v, Voter.fromPlutusData(v.toPlutusData()));
        }

        @Test
        void governanceActionIdRoundTrip() {
            var gai = new GovernanceActionId(txId(), BigInteger.valueOf(5));
            assertEquals(gai, GovernanceActionId.fromPlutusData(gai.toPlutusData()));
        }

        @Test
        void rationalRoundTrip() {
            var r = new Rational(BigInteger.valueOf(2), BigInteger.valueOf(3));
            assertEquals(r, Rational.fromPlutusData(r.toPlutusData()));
        }

        @Test
        void protocolVersionRoundTrip() {
            var pv = new ProtocolVersion(BigInteger.valueOf(9), BigInteger.ZERO);
            assertEquals(pv, ProtocolVersion.fromPlutusData(pv.toPlutusData()));
        }

        @Test
        void committeeRoundTrip() {
            var c = new Committee(
                    JulcMap.of(new Credential.PubKeyCredential(pkh()), BigInteger.valueOf(100)),
                    new Rational(BigInteger.TWO, BigInteger.valueOf(3)));
            assertEquals(c, Committee.fromPlutusData(c.toPlutusData()));
        }
    }

    // --- TxCert ---

    @Nested
    class TxCertTests {
        @Test
        void regStakingRoundTrip() {
            var cert = new TxCert.RegStaking(new Credential.PubKeyCredential(pkh()), Optional.of(BigInteger.valueOf(2_000_000)));
            assertEquals(cert, TxCert.fromPlutusData(cert.toPlutusData()));
        }

        @Test
        void unRegStakingRoundTrip() {
            var cert = new TxCert.UnRegStaking(new Credential.PubKeyCredential(pkh()), Optional.empty());
            assertEquals(cert, TxCert.fromPlutusData(cert.toPlutusData()));
        }

        @Test
        void delegStakingRoundTrip() {
            var cert = new TxCert.DelegStaking(new Credential.PubKeyCredential(pkh()), new Delegatee.Stake(pkh()));
            assertEquals(cert, TxCert.fromPlutusData(cert.toPlutusData()));
        }

        @Test
        void regDelegRoundTrip() {
            var cert = new TxCert.RegDeleg(new Credential.PubKeyCredential(pkh()),
                    new Delegatee.Vote(new DRep.AlwaysAbstain()), BigInteger.valueOf(500_000));
            assertEquals(cert, TxCert.fromPlutusData(cert.toPlutusData()));
        }

        @Test
        void regDRepRoundTrip() {
            var cert = new TxCert.RegDRep(new Credential.PubKeyCredential(pkh()), BigInteger.valueOf(500_000_000));
            assertEquals(cert, TxCert.fromPlutusData(cert.toPlutusData()));
        }

        @Test
        void updateDRepRoundTrip() {
            var cert = new TxCert.UpdateDRep(new Credential.ScriptCredential(sh()));
            assertEquals(cert, TxCert.fromPlutusData(cert.toPlutusData()));
        }

        @Test
        void unRegDRepRoundTrip() {
            var cert = new TxCert.UnRegDRep(new Credential.PubKeyCredential(pkh()), BigInteger.valueOf(500_000_000));
            assertEquals(cert, TxCert.fromPlutusData(cert.toPlutusData()));
        }

        @Test
        void poolRegisterRoundTrip() {
            var cert = new TxCert.PoolRegister(pkh(), pkh2());
            assertEquals(cert, TxCert.fromPlutusData(cert.toPlutusData()));
        }

        @Test
        void poolRetireRoundTrip() {
            var cert = new TxCert.PoolRetire(pkh(), BigInteger.valueOf(200));
            assertEquals(cert, TxCert.fromPlutusData(cert.toPlutusData()));
        }

        @Test
        void authHotCommitteeRoundTrip() {
            var cert = new TxCert.AuthHotCommittee(
                    new Credential.PubKeyCredential(pkh()),
                    new Credential.PubKeyCredential(pkh2()));
            assertEquals(cert, TxCert.fromPlutusData(cert.toPlutusData()));
        }

        @Test
        void resignColdCommitteeRoundTrip() {
            var cert = new TxCert.ResignColdCommittee(new Credential.PubKeyCredential(pkh()));
            assertEquals(cert, TxCert.fromPlutusData(cert.toPlutusData()));
        }

        @Test
        void allTagsDistinct() {
            // Verify each constructor maps to its expected Constr tag
            assertEquals(0, ((PlutusData.ConstrData) new TxCert.RegStaking(new Credential.PubKeyCredential(pkh()), Optional.empty()).toPlutusData()).tag());
            assertEquals(1, ((PlutusData.ConstrData) new TxCert.UnRegStaking(new Credential.PubKeyCredential(pkh()), Optional.empty()).toPlutusData()).tag());
            assertEquals(2, ((PlutusData.ConstrData) new TxCert.DelegStaking(new Credential.PubKeyCredential(pkh()), new Delegatee.Stake(pkh())).toPlutusData()).tag());
            assertEquals(7, ((PlutusData.ConstrData) new TxCert.PoolRegister(pkh(), pkh()).toPlutusData()).tag());
            assertEquals(10, ((PlutusData.ConstrData) new TxCert.ResignColdCommittee(new Credential.PubKeyCredential(pkh())).toPlutusData()).tag());
        }
    }

    // --- GovernanceAction ---

    @Nested
    class GovernanceActionTests {
        @Test
        void parameterChangeRoundTrip() {
            var ga = new GovernanceAction.ParameterChange(Optional.empty(), PlutusData.integer(1), Optional.empty());
            assertEquals(ga, GovernanceAction.fromPlutusData(ga.toPlutusData()));
        }

        @Test
        void hardForkInitiationRoundTrip() {
            var ga = new GovernanceAction.HardForkInitiation(
                    Optional.of(new GovernanceActionId(txId(), BigInteger.ZERO)),
                    new ProtocolVersion(BigInteger.valueOf(10), BigInteger.ZERO));
            assertEquals(ga, GovernanceAction.fromPlutusData(ga.toPlutusData()));
        }

        @Test
        void treasuryWithdrawalsRoundTrip() {
            var ga = new GovernanceAction.TreasuryWithdrawals(
                    JulcMap.of(new Credential.PubKeyCredential(pkh()), BigInteger.valueOf(1_000_000)),
                    Optional.empty());
            assertEquals(ga, GovernanceAction.fromPlutusData(ga.toPlutusData()));
        }

        @Test
        void noConfidenceRoundTrip() {
            var ga = new GovernanceAction.NoConfidence(Optional.empty());
            assertEquals(ga, GovernanceAction.fromPlutusData(ga.toPlutusData()));
        }

        @Test
        void updateCommitteeRoundTrip() {
            var ga = new GovernanceAction.UpdateCommittee(
                    Optional.empty(),
                    JulcList.of(new Credential.PubKeyCredential(pkh())),
                    JulcMap.empty(),
                    new Rational(BigInteger.TWO, BigInteger.valueOf(3)));
            assertEquals(ga, GovernanceAction.fromPlutusData(ga.toPlutusData()));
        }

        @Test
        void newConstitutionRoundTrip() {
            var ga = new GovernanceAction.NewConstitution(Optional.empty(), Optional.of(sh()));
            assertEquals(ga, GovernanceAction.fromPlutusData(ga.toPlutusData()));
        }

        @Test
        void infoActionRoundTrip() {
            var ga = new GovernanceAction.InfoAction();
            assertEquals(ga, GovernanceAction.fromPlutusData(ga.toPlutusData()));
        }

        @Test
        void proposalProcedureRoundTrip() {
            var pp = new ProposalProcedure(
                    BigInteger.valueOf(500_000_000),
                    new Credential.PubKeyCredential(pkh()),
                    new GovernanceAction.InfoAction());
            assertEquals(pp, ProposalProcedure.fromPlutusData(pp.toPlutusData()));
        }
    }

    // --- ScriptPurpose ---

    @Nested
    class ScriptPurposeTests {
        @Test
        void mintingRoundTrip() {
            var sp = new ScriptPurpose.Minting(pid());
            assertEquals(sp, ScriptPurpose.fromPlutusData(sp.toPlutusData()));
        }

        @Test
        void spendingRoundTrip() {
            var sp = new ScriptPurpose.Spending(new TxOutRef(txId(), BigInteger.ZERO));
            assertEquals(sp, ScriptPurpose.fromPlutusData(sp.toPlutusData()));
        }

        @Test
        void rewardingRoundTrip() {
            var sp = new ScriptPurpose.Rewarding(new Credential.PubKeyCredential(pkh()));
            assertEquals(sp, ScriptPurpose.fromPlutusData(sp.toPlutusData()));
        }

        @Test
        void certifyingRoundTrip() {
            var sp = new ScriptPurpose.Certifying(BigInteger.ZERO,
                    new TxCert.UpdateDRep(new Credential.PubKeyCredential(pkh())));
            assertEquals(sp, ScriptPurpose.fromPlutusData(sp.toPlutusData()));
        }

        @Test
        void votingRoundTrip() {
            var sp = new ScriptPurpose.Voting(new Voter.DRepVoter(new Credential.PubKeyCredential(pkh())));
            assertEquals(sp, ScriptPurpose.fromPlutusData(sp.toPlutusData()));
        }

        @Test
        void proposingRoundTrip() {
            var sp = new ScriptPurpose.Proposing(BigInteger.ONE,
                    new ProposalProcedure(BigInteger.ZERO, new Credential.PubKeyCredential(pkh()),
                            new GovernanceAction.InfoAction()));
            assertEquals(sp, ScriptPurpose.fromPlutusData(sp.toPlutusData()));
        }
    }

    // --- ScriptInfo ---

    @Nested
    class ScriptInfoTests {
        @Test
        void mintingScriptRoundTrip() {
            var si = new ScriptInfo.MintingScript(pid());
            assertEquals(si, ScriptInfo.fromPlutusData(si.toPlutusData()));
        }

        @Test
        void spendingScriptWithDatumRoundTrip() {
            var si = new ScriptInfo.SpendingScript(
                    new TxOutRef(txId(), BigInteger.ZERO),
                    Optional.of(PlutusData.integer(42)));
            assertEquals(si, ScriptInfo.fromPlutusData(si.toPlutusData()));
        }

        @Test
        void spendingScriptNoDatumRoundTrip() {
            var si = new ScriptInfo.SpendingScript(new TxOutRef(txId(), BigInteger.ONE), Optional.empty());
            assertEquals(si, ScriptInfo.fromPlutusData(si.toPlutusData()));
        }

        @Test
        void rewardingScriptRoundTrip() {
            var si = new ScriptInfo.RewardingScript(new Credential.PubKeyCredential(pkh()));
            assertEquals(si, ScriptInfo.fromPlutusData(si.toPlutusData()));
        }

        @Test
        void certifyingScriptRoundTrip() {
            var si = new ScriptInfo.CertifyingScript(BigInteger.ZERO,
                    new TxCert.RegDRep(new Credential.PubKeyCredential(pkh()), BigInteger.valueOf(500)));
            assertEquals(si, ScriptInfo.fromPlutusData(si.toPlutusData()));
        }

        @Test
        void votingScriptRoundTrip() {
            var si = new ScriptInfo.VotingScript(new Voter.StakePoolVoter(pkh()));
            assertEquals(si, ScriptInfo.fromPlutusData(si.toPlutusData()));
        }

        @Test
        void proposingScriptRoundTrip() {
            var si = new ScriptInfo.ProposingScript(BigInteger.TWO,
                    new ProposalProcedure(BigInteger.ONE, new Credential.PubKeyCredential(pkh()),
                            new GovernanceAction.InfoAction()));
            assertEquals(si, ScriptInfo.fromPlutusData(si.toPlutusData()));
        }
    }

    // --- TxInfo ---

    @Nested
    class TxInfoTests {
        TxInfo minimalTxInfo() {
            return new TxInfo(
                    JulcList.of(), JulcList.of(), JulcList.of(),
                    BigInteger.valueOf(200_000), Value.zero(), JulcList.of(),
                    JulcMap.empty(), Interval.always(), JulcList.of(),
                    JulcMap.empty(), JulcMap.empty(), txId(),
                    JulcMap.empty(), JulcList.of(), Optional.empty(), Optional.empty());
        }

        @Test
        void minimalRoundTrip() {
            var ti = minimalTxInfo();
            assertEquals(ti, TxInfo.fromPlutusData(ti.toPlutusData()));
        }

        @Test
        void withInputsAndOutputsRoundTrip() {
            var addr = new Address(new Credential.PubKeyCredential(pkh()), Optional.empty());
            var out = new TxOut(addr, Value.lovelace(BigInteger.valueOf(5_000_000)),
                    new OutputDatum.NoOutputDatum(), Optional.empty());
            var input = new TxInInfo(new TxOutRef(txId(), BigInteger.ZERO), out);
            var ti = new TxInfo(
                    JulcList.of(input), JulcList.of(), JulcList.of(out),
                    BigInteger.valueOf(200_000), Value.zero(), JulcList.of(),
                    JulcMap.empty(), Interval.always(), JulcList.of(pkh()),
                    JulcMap.empty(), JulcMap.empty(), txId(),
                    JulcMap.empty(), JulcList.of(), Optional.empty(), Optional.empty());
            assertEquals(ti, TxInfo.fromPlutusData(ti.toPlutusData()));
        }

        @Test
        void with16FieldsRoundTrip() {
            var addr = new Address(new Credential.PubKeyCredential(pkh()), Optional.empty());
            var out = new TxOut(addr, Value.lovelace(BigInteger.valueOf(2_000_000)),
                    new OutputDatum.NoOutputDatum(), Optional.empty());
            var input = new TxInInfo(new TxOutRef(txId(), BigInteger.ZERO), out);
            var voter = new Voter.DRepVoter(new Credential.PubKeyCredential(pkh()));
            var gaId = new GovernanceActionId(txId(), BigInteger.ZERO);
            JulcMap<Voter, JulcMap<GovernanceActionId, Vote>> votes = JulcMap.of(voter, JulcMap.of(gaId, (Vote) new Vote.VoteYes()));

            var ti = new TxInfo(
                    JulcList.of(input),
                    JulcList.of(),
                    JulcList.of(out),
                    BigInteger.valueOf(200_000),
                    Value.zero(),
                    JulcList.of(new TxCert.RegDRep(new Credential.PubKeyCredential(pkh()), BigInteger.ONE)),
                    JulcMap.empty(),
                    Interval.between(BigInteger.valueOf(1000), BigInteger.valueOf(2000)),
                    JulcList.of(pkh()),
                    JulcMap.empty(),
                    JulcMap.empty(),
                    txId(),
                    votes,
                    JulcList.of(),
                    Optional.of(BigInteger.valueOf(1_000_000_000)),
                    Optional.of(BigInteger.ZERO));
            assertEquals(ti, TxInfo.fromPlutusData(ti.toPlutusData()));
        }

        @Test
        void encodingIs16FieldConstr() {
            var data = minimalTxInfo().toPlutusData();
            assertInstanceOf(PlutusData.ConstrData.class, data);
            assertEquals(16, ((PlutusData.ConstrData) data).fields().size());
        }
    }

    // --- ScriptContext ---

    @Nested
    class ScriptContextTests {
        @Test
        void spendingContextRoundTrip() {
            var ti = new TxInfo(
                    JulcList.of(), JulcList.of(), JulcList.of(),
                    BigInteger.ZERO, Value.zero(), JulcList.of(),
                    JulcMap.empty(), Interval.always(), JulcList.of(),
                    JulcMap.empty(), JulcMap.empty(), txId(),
                    JulcMap.empty(), JulcList.of(), Optional.empty(), Optional.empty());
            var ctx = new ScriptContext(ti, PlutusData.UNIT,
                    new ScriptInfo.SpendingScript(new TxOutRef(txId(), BigInteger.ZERO), Optional.empty()));
            assertEquals(ctx, ScriptContext.fromPlutusData(ctx.toPlutusData()));
        }

        @Test
        void mintingContextRoundTrip() {
            var ti = new TxInfo(
                    JulcList.of(), JulcList.of(), JulcList.of(),
                    BigInteger.ZERO, Value.zero(), JulcList.of(),
                    JulcMap.empty(), Interval.always(), JulcList.of(),
                    JulcMap.empty(), JulcMap.empty(), txId(),
                    JulcMap.empty(), JulcList.of(), Optional.empty(), Optional.empty());
            var ctx = new ScriptContext(ti, PlutusData.integer(42),
                    new ScriptInfo.MintingScript(pid()));
            assertEquals(ctx, ScriptContext.fromPlutusData(ctx.toPlutusData()));
        }

        @Test
        void encodingStructure() {
            var ti = new TxInfo(
                    JulcList.of(), JulcList.of(), JulcList.of(),
                    BigInteger.ZERO, Value.zero(), JulcList.of(),
                    JulcMap.empty(), Interval.always(), JulcList.of(),
                    JulcMap.empty(), JulcMap.empty(), txId(),
                    JulcMap.empty(), JulcList.of(), Optional.empty(), Optional.empty());
            var ctx = new ScriptContext(ti, PlutusData.UNIT,
                    new ScriptInfo.MintingScript(pid()));
            var data = ctx.toPlutusData();
            assertInstanceOf(PlutusData.ConstrData.class, data);
            var constr = (PlutusData.ConstrData) data;
            assertEquals(0, constr.tag());
            assertEquals(3, constr.fields().size());
        }
    }

    // --- ScriptContextBuilder ---

    @Nested
    class ScriptContextBuilderTests {
        @Test
        void spendingMinimal() {
            var ctx = ScriptContextBuilder.spending(new TxOutRef(txId(), BigInteger.ZERO)).build();
            assertNotNull(ctx);
            assertInstanceOf(ScriptInfo.SpendingScript.class, ctx.scriptInfo());
        }

        @Test
        void mintingMinimal() {
            var ctx = ScriptContextBuilder.minting(pid()).build();
            assertNotNull(ctx);
            assertInstanceOf(ScriptInfo.MintingScript.class, ctx.scriptInfo());
        }

        @Test
        void withInputsAndSigners() {
            var addr = new Address(new Credential.PubKeyCredential(pkh()), Optional.empty());
            var out = new TxOut(addr, Value.lovelace(BigInteger.valueOf(5_000_000)),
                    new OutputDatum.NoOutputDatum(), Optional.empty());
            var ctx = ScriptContextBuilder.spending(new TxOutRef(txId(), BigInteger.ZERO))
                    .input(new TxInInfo(new TxOutRef(txId(), BigInteger.ZERO), out))
                    .output(out)
                    .signer(pkh())
                    .fee(BigInteger.valueOf(200_000))
                    .validRange(Interval.after(BigInteger.valueOf(1000)))
                    .build();
            assertEquals(1, ctx.txInfo().inputs().size());
            assertEquals(1, ctx.txInfo().outputs().size());
            assertEquals(1, ctx.txInfo().signatories().size());
            assertEquals(BigInteger.valueOf(200_000), ctx.txInfo().fee());
        }

        @Test
        void withRedeemer() {
            var ctx = ScriptContextBuilder.minting(pid())
                    .redeemer(PlutusData.integer(42))
                    .build();
            assertEquals(PlutusData.integer(42), ctx.redeemer());
        }

        @Test
        void spendingWithDatum() {
            var ctx = ScriptContextBuilder.spending(
                    new TxOutRef(txId(), BigInteger.ZERO),
                    PlutusData.integer(99)).build();
            var si = (ScriptInfo.SpendingScript) ctx.scriptInfo();
            assertTrue(si.datum().isPresent());
            assertEquals(PlutusData.integer(99), si.datum().get());
        }

        @Test
        void builderContextRoundTrip() {
            var ctx = ScriptContextBuilder.spending(new TxOutRef(txId(), BigInteger.ZERO))
                    .signer(pkh())
                    .validRange(Interval.always())
                    .txId(txId())
                    .build();
            assertEquals(ctx, ScriptContext.fromPlutusData(ctx.toPlutusData()));
        }

        @Test
        void withTreasury() {
            var ctx = ScriptContextBuilder.minting(pid())
                    .currentTreasuryAmount(BigInteger.valueOf(1_000_000_000))
                    .treasuryDonation(BigInteger.ZERO)
                    .build();
            assertTrue(ctx.txInfo().currentTreasuryAmount().isPresent());
            assertEquals(BigInteger.valueOf(1_000_000_000), ctx.txInfo().currentTreasuryAmount().get());
        }
    }
}
