package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Conway-era governance ledger types:
 * Rational, ProtocolVersion, GovernanceActionId, Vote, DRep, Voter,
 * StakingCredential, Delegatee, TxCert, GovernanceAction, ProposalProcedure,
 * Committee, ScriptPurpose.
 */
class GovernanceTypeTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    // --- PlutusData builders for governance types ---

    static PlutusData pubKeyCredential(byte[] hash) {
        return PlutusData.constr(0, PlutusData.bytes(hash));
    }

    static PlutusData rational(long num, long den) {
        return PlutusData.constr(0, PlutusData.integer(num), PlutusData.integer(den));
    }

    static PlutusData protocolVersion(long major, long minor) {
        return PlutusData.constr(0, PlutusData.integer(major), PlutusData.integer(minor));
    }

    static PlutusData governanceActionId(byte[] txId, long ix) {
        return PlutusData.constr(0, PlutusData.bytes(txId), PlutusData.integer(ix));
    }

    static PlutusData optionalSome(PlutusData value) {
        return PlutusData.constr(0, value);
    }

    static PlutusData optionalNone() {
        return PlutusData.constr(1);
    }

    /** Build an "always" valid range interval. */
    static PlutusData alwaysInterval() {
        var negInf = PlutusData.constr(0);
        var posInf = PlutusData.constr(2);
        var trueVal = PlutusData.constr(1);
        var lowerBound = PlutusData.constr(0, negInf, trueVal);
        var upperBound = PlutusData.constr(0, posInf, trueVal);
        return PlutusData.constr(0, lowerBound, upperBound);
    }

    /** Build a minimal TxInfo with all 16 fields. */
    static PlutusData buildTxInfo() {
        return PlutusData.constr(0,
                PlutusData.list(),                    // 0: inputs
                PlutusData.list(),                    // 1: referenceInputs
                PlutusData.list(),                    // 2: outputs
                PlutusData.integer(2000000),          // 3: fee
                PlutusData.map(),                     // 4: mint
                PlutusData.list(),                    // 5: certificates
                PlutusData.map(),                     // 6: withdrawals
                alwaysInterval(),                     // 7: validRange
                PlutusData.list(),                    // 8: signatories
                PlutusData.map(),                     // 9: redeemers
                PlutusData.map(),                     // 10: datums
                PlutusData.bytes(new byte[32]),        // 11: id
                PlutusData.map(),                     // 12: votes
                PlutusData.list(),                    // 13: proposalProcedures
                PlutusData.constr(1),                 // 14: currentTreasuryAmount (None)
                PlutusData.constr(1)                  // 15: treasuryDonation (None)
        );
    }

    /** MintingScript = Constr(0, [policyId]) */
    static PlutusData mintingScriptInfo() {
        return PlutusData.constr(0, PlutusData.bytes(new byte[28]));
    }

    /**
     * Build ScriptContext = Constr(0, [txInfo, redeemer, scriptInfo]).
     * For 2-param validators: redeemer is the typed first param,
     * scriptInfo is MintingScript by default.
     */
    static PlutusData buildCtx(PlutusData redeemer) {
        return PlutusData.constr(0, buildTxInfo(), redeemer, mintingScriptInfo());
    }

    static PlutusData buildCtx(PlutusData redeemer, PlutusData scriptInfo) {
        return PlutusData.constr(0, buildTxInfo(), redeemer, scriptInfo);
    }

    static PlutusData buildCtxWithTxInfo(PlutusData txInfo, PlutusData redeemer, PlutusData scriptInfo) {
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    // =======================================================================
    // Tier 1: Simple Record Field Access
    // =======================================================================

    @Nested
    class SimpleRecordAccess {

        @Test
        void rationalFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Rational r, ScriptContext ctx) {
                            BigInteger num = r.numerator();
                            BigInteger den = r.denominator();
                            return num == 3 && den == 4;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();
            var ctx = buildCtx(rational(3, 4));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Rational 3/4 fields should match. Got: " + result);
        }

        @Test
        void protocolVersionFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ProtocolVersion pv, ScriptContext ctx) {
                            BigInteger maj = pv.major();
                            BigInteger min = pv.minor();
                            return maj == 10 && min == 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();
            var ctx = buildCtx(protocolVersion(10, 0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "ProtocolVersion 10.0 should match. Got: " + result);
        }

        @Test
        void governanceActionIdFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(GovernanceActionId gaid, ScriptContext ctx) {
                            byte[] txId = gaid.txId();
                            BigInteger ix = gaid.govActionIx();
                            return ix == 42;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();
            var ctx = buildCtx(governanceActionId(new byte[32], 42));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "GovernanceActionId ix=42 should match. Got: " + result);
        }
    }

    // =======================================================================
    // Tier 2: Simple Switch on SumTypes
    // =======================================================================

    @Nested
    class SimpleSwitchTests {

        @Test
        void voteSwitchAllVariants() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Vote vote, ScriptContext ctx) {
                            BigInteger result = switch (vote) {
                                case VoteNo v -> 0;
                                case VoteYes v -> 1;
                                case Abstain v -> 2;
                            };
                            return result == 1;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // VoteYes = Constr(1, [])
            var ctx = buildCtx(PlutusData.constr(1));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "VoteYes should return 1. Got: " + result);
        }

        @Test
        void voteNoSwitch() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Vote vote, ScriptContext ctx) {
                            BigInteger result = switch (vote) {
                                case VoteNo v -> 0;
                                case VoteYes v -> 1;
                                case Abstain v -> 2;
                            };
                            return result == 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // VoteNo = Constr(0, [])
            var ctx = buildCtx(PlutusData.constr(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "VoteNo should return 0. Got: " + result);
        }

        @Test
        void dRepSwitchWithFieldAccess() {
            var source = """
                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(DRep drep, ScriptContext ctx) {
                            boolean isAlwaysAbstain = switch (drep) {
                                case DRepCredential dc -> false;
                                case AlwaysAbstain aa -> true;
                                case AlwaysNoConfidence anc -> false;
                            };
                            return isAlwaysAbstain;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // AlwaysAbstain = Constr(1, [])
            var ctx = buildCtx(PlutusData.constr(1));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "AlwaysAbstain should match. Got: " + result);
        }
    }

    // =======================================================================
    // Tier 3: Switch with Field Access on Variants
    // =======================================================================

    @Nested
    class SwitchWithFieldAccess {

        @Test
        void voterSwitchExtractCredential() {
            var source = """
                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Voter voter, ScriptContext ctx) {
                            PlutusData result = switch (voter) {
                                case CommitteeVoter cv -> cv.credential();
                                case DRepVoter dv -> dv.credential();
                                case StakePoolVoter spv -> spv.pubKeyHash();
                            };
                            return result == result;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var result = compiler.compile(source);
            assertFalse(result.hasErrors(), "Voter switch should compile: " + result);
        }

        @Test
        void voterCommitteeVoterEval() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Voter voter, ScriptContext ctx) {
                            BigInteger tag = switch (voter) {
                                case CommitteeVoter cv -> 0;
                                case DRepVoter dv -> 1;
                                case StakePoolVoter spv -> 2;
                            };
                            return tag == 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // CommitteeVoter = Constr(0, [PubKeyCredential(Constr(0, [hash]))])
            var cred = pubKeyCredential(new byte[]{1, 2, 3});
            var ctx = buildCtx(PlutusData.constr(0, cred));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "CommitteeVoter should match tag 0. Got: " + result);
        }

        @Test
        void stakingCredentialSwitch() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(StakingCredential sc, ScriptContext ctx) {
                            BigInteger result = switch (sc) {
                                case StakingHash sh -> 0;
                                case StakingPtr sp -> sp.slot();
                            };
                            return result == 100;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // StakingPtr = Constr(1, [IData(100), IData(0), IData(0)])
            var stakingPtr = PlutusData.constr(1,
                    PlutusData.integer(100), PlutusData.integer(0), PlutusData.integer(0));
            var ctx = buildCtx(stakingPtr);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "StakingPtr slot=100 should match. Got: " + result);
        }

        @Test
        void delegateeSwitchPoolId() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Delegatee d, ScriptContext ctx) {
                            BigInteger result = switch (d) {
                                case Stake s -> 0;
                                case Delegatee.Vote v -> 1;
                                case StakeVote sv -> 2;
                            };
                            return result == 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // Stake = Constr(0, [BData(poolId)])
            var stake = PlutusData.constr(0, PlutusData.bytes(new byte[]{1, 2, 3}));
            var ctx = buildCtx(stake);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Stake should match tag 0. Got: " + result);
        }
    }

    // =======================================================================
    // Tier 4: Complex Switch — TxCert (11 variants)
    // =======================================================================

    @Nested
    class TxCertSwitchTests {

        @Test
        void txCertSwitchAllVariants() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(TxCert cert, ScriptContext ctx) {
                            BigInteger tag = switch (cert) {
                                case RegStaking rs -> 0;
                                case UnRegStaking urs -> 1;
                                case DelegStaking ds -> 2;
                                case RegDeleg rd -> 3;
                                case RegDRep rdr -> 4;
                                case UpdateDRep udr -> 5;
                                case UnRegDRep urdr -> 6;
                                case PoolRegister pr -> 7;
                                case PoolRetire ptr -> 8;
                                case AuthHotCommittee ahc -> 9;
                                case ResignColdCommittee rcc -> 10;
                            };
                            return tag == 7;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // PoolRegister = Constr(7, [BData(poolId), BData(poolVfr)])
            var poolRegister = PlutusData.constr(7,
                    PlutusData.bytes(new byte[]{1}), PlutusData.bytes(new byte[]{2}));
            var ctx = buildCtx(poolRegister);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "PoolRegister should match tag 7. Got: " + result);
        }

        @Test
        void txCertRegStakingFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(TxCert cert, ScriptContext ctx) {
                            BigInteger tag = switch (cert) {
                                case RegStaking rs -> {
                                    var dep = rs.deposit();
                                    yield 0;
                                }
                                case UnRegStaking urs -> 1;
                                case DelegStaking ds -> 2;
                                case RegDeleg rd -> 3;
                                case RegDRep rdr -> 4;
                                case UpdateDRep udr -> 5;
                                case UnRegDRep urdr -> 6;
                                case PoolRegister pr -> 7;
                                case PoolRetire ptr -> 8;
                                case AuthHotCommittee ahc -> 9;
                                case ResignColdCommittee rcc -> 10;
                            };
                            return tag == 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // RegStaking = Constr(0, [PubKeyCredential(hash), Some(IData(deposit))])
            var cred = pubKeyCredential(new byte[]{1, 2, 3});
            var deposit = optionalSome(PlutusData.integer(2000000));
            var regStaking = PlutusData.constr(0, cred, deposit);
            var ctx = buildCtx(regStaking);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "RegStaking should match tag 0 with field access. Got: " + result);
        }

        @Test
        void txCertPoolRetireFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(TxCert cert, ScriptContext ctx) {
                            BigInteger epochVal = switch (cert) {
                                case RegStaking rs -> 0;
                                case UnRegStaking urs -> 0;
                                case DelegStaking ds -> 0;
                                case RegDeleg rd -> 0;
                                case RegDRep rdr -> 0;
                                case UpdateDRep udr -> 0;
                                case UnRegDRep urdr -> 0;
                                case PoolRegister pr -> 0;
                                case PoolRetire ptr -> ptr.epoch();
                                case AuthHotCommittee ahc -> 0;
                                case ResignColdCommittee rcc -> 0;
                            };
                            return epochVal == 500;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // PoolRetire = Constr(8, [BData(pubKeyHash), IData(epoch)])
            var poolRetire = PlutusData.constr(8,
                    PlutusData.bytes(new byte[]{1, 2, 3}), PlutusData.integer(500));
            var ctx = buildCtx(poolRetire);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "PoolRetire epoch=500 should match. Got: " + result);
        }
    }

    // =======================================================================
    // GovernanceAction Switch Tests
    // =======================================================================

    @Nested
    class GovernanceActionTests {

        @Test
        void governanceActionSwitchAllVariants() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(GovernanceAction ga, ScriptContext ctx) {
                            BigInteger tag = switch (ga) {
                                case ParameterChange pc -> 0;
                                case HardForkInitiation hf -> 1;
                                case TreasuryWithdrawals tw -> 2;
                                case NoConfidence nc -> 3;
                                case UpdateCommittee uc -> 4;
                                case NewConstitution nc2 -> 5;
                                case InfoAction ia -> 6;
                            };
                            return tag == 6;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // InfoAction = Constr(6, [])
            var ctx = buildCtx(PlutusData.constr(6));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "InfoAction should match tag 6. Got: " + result);
        }

        @Test
        void hardForkInitiationFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(GovernanceAction ga, ScriptContext ctx) {
                            BigInteger result = switch (ga) {
                                case ParameterChange pc -> 0;
                                case HardForkInitiation hf -> {
                                    ProtocolVersion pv = hf.protocolVersion();
                                    yield pv.major();
                                }
                                case TreasuryWithdrawals tw -> 0;
                                case NoConfidence nc -> 0;
                                case UpdateCommittee uc -> 0;
                                case NewConstitution nc2 -> 0;
                                case InfoAction ia -> 0;
                            };
                            return result == 10;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // HardForkInitiation = Constr(1, [None, Constr(0, [IData(10), IData(0)])])
            var hfi = PlutusData.constr(1, optionalNone(), protocolVersion(10, 0));
            var ctx = buildCtx(hfi);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "HardForkInitiation major=10 should match. Got: " + result);
        }
    }

    // =======================================================================
    // ScriptPurpose Switch Tests
    // =======================================================================

    @Nested
    class ScriptPurposeTests {

        @Test
        void scriptPurposeSwitchAllVariants() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ScriptPurpose sp, ScriptContext ctx) {
                            BigInteger tag = switch (sp) {
                                case Minting m -> 0;
                                case Spending s -> 1;
                                case Rewarding r -> 2;
                                case Certifying c -> 3;
                                case Voting v -> 4;
                                case Proposing p -> 5;
                            };
                            return tag == 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // Minting = Constr(0, [BData(policyId)])
            var ctx = buildCtx(PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Minting should match tag 0. Got: " + result);
        }

        @Test
        void scriptPurposeSpendingFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ScriptPurpose sp, ScriptContext ctx) {
                            BigInteger result = switch (sp) {
                                case Minting m -> 0;
                                case Spending s -> {
                                    TxOutRef ref = s.txOutRef();
                                    yield ref.index();
                                }
                                case Rewarding r -> 0;
                                case Certifying c -> 0;
                                case Voting v -> 0;
                                case Proposing p -> 0;
                            };
                            return result == 3;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // Spending = Constr(1, [TxOutRef(Constr(0, [BData(txId), IData(3)]))])
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(3));
            var spending = PlutusData.constr(1, txOutRef);
            var ctx = buildCtx(spending);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Spending.txOutRef.index() should be 3. Got: " + result);
        }
    }

    // =======================================================================
    // Composite record tests
    // =======================================================================

    @Nested
    class CompositeRecordTests {

        @Test
        void proposalProcedureFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ProposalProcedure pp, ScriptContext ctx) {
                            BigInteger dep = pp.deposit();
                            return dep == 500;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // ProposalProcedure = Constr(0, [IData(deposit), Credential, GovernanceAction])
            var cred = pubKeyCredential(new byte[]{1, 2, 3});
            var infoAction = PlutusData.constr(6);  // InfoAction
            var pp = PlutusData.constr(0, PlutusData.integer(500), cred, infoAction);
            var ctx = buildCtx(pp);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "ProposalProcedure deposit=500 should match. Got: " + result);
        }

        @Test
        void committeeFieldAccess() {
            var source = """
                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Committee c, ScriptContext ctx) {
                            Rational q = c.quorum();
                            return q.numerator() == 2 && q.denominator() == 3;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // Committee = Constr(0, [MapData(members), Rational(2, 3)])
            var quorum = rational(2, 3);
            var committee = PlutusData.constr(0, PlutusData.map(), quorum);
            var ctx = buildCtx(committee);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Committee quorum 2/3 should match. Got: " + result);
        }
    }

    // =======================================================================
    // ScriptInfo with governance types
    // =======================================================================

    @Nested
    class ScriptInfoGovernanceTests {

        @Test
        void scriptInfoCertifyingFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            ScriptInfo info = ctx.scriptInfo();
                            BigInteger result = switch (info) {
                                case MintingScript ms -> 0;
                                case SpendingScript ss -> 0;
                                case RewardingScript rs -> 0;
                                case CertifyingScript cs -> {
                                    BigInteger idx = cs.index();
                                    yield idx;
                                }
                                case VotingScript vs -> 0;
                                case ProposingScript ps -> 0;
                            };
                            return result == 5;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // CertifyingScript = Constr(3, [IData(5), TxCert])
            var cert = PlutusData.constr(5, pubKeyCredential(new byte[]{1}));
            var scriptInfo = PlutusData.constr(3, PlutusData.integer(5), cert);
            var ctx = buildCtxWithTxInfo(buildTxInfo(), PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "CertifyingScript index=5 should match. Got: " + result);
        }

        @Test
        void scriptInfoVotingFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            ScriptInfo info = ctx.scriptInfo();
                            BigInteger tag = switch (info) {
                                case MintingScript ms -> 0;
                                case SpendingScript ss -> 1;
                                case RewardingScript rs -> 2;
                                case CertifyingScript cs -> 3;
                                case VotingScript vs -> 4;
                                case ProposingScript ps -> 5;
                            };
                            return tag == 4;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // VotingScript = Constr(4, [Voter])
            var voter = PlutusData.constr(1, pubKeyCredential(new byte[]{1, 2}));
            var scriptInfo = PlutusData.constr(4, voter);
            var ctx = buildCtxWithTxInfo(buildTxInfo(), PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "VotingScript should match tag 4. Got: " + result);
        }
    }

    // =======================================================================
    // TxInfo governance fields
    // =======================================================================

    @Nested
    class TxInfoGovernanceFieldTests {

        @Test
        void txInfoCertificatesIsEmpty() {
            var source = """
                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var certs = txInfo.certificates();
                            return certs.isEmpty();
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var ctx = buildCtx(PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Empty certificates list should be empty. Got: " + result);
        }

        @Test
        void txInfoProposalProceduresIsEmpty() {
            var source = """
                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var proposals = txInfo.proposalProcedures();
                            return proposals.isEmpty();
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var ctx = buildCtx(PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Empty proposals list should be empty. Got: " + result);
        }
    }

    // =======================================================================
    // Compilation-only tests (verify no compilation errors)
    // =======================================================================

    @Nested
    class CompilationTests {

        @Test
        void allGovernanceTypesCompile() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return true;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var result = compiler.compile(source);
            assertFalse(result.hasErrors(), "Basic compilation should succeed: " + result);
        }

        @Test
        void governanceActionNestedFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ProposalProcedure pp, ScriptContext ctx) {
                            GovernanceAction ga = pp.governanceAction();
                            BigInteger tag = switch (ga) {
                                case ParameterChange pc -> 0;
                                case HardForkInitiation hf -> 1;
                                case TreasuryWithdrawals tw -> 2;
                                case NoConfidence nc -> 3;
                                case UpdateCommittee uc -> 4;
                                case NewConstitution nc2 -> 5;
                                case InfoAction ia -> 6;
                            };
                            return tag >= 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var result = compiler.compile(source);
            assertFalse(result.hasErrors(),
                    "ProposalProcedure -> GovernanceAction switch should compile: " + result);
        }

        @Test
        void scriptPurposeCertifyingNestedAccess() {
            var source = """
                    import java.math.BigInteger;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ScriptPurpose sp, ScriptContext ctx) {
                            BigInteger result = switch (sp) {
                                case Minting m -> 0;
                                case Spending s -> 0;
                                case Rewarding r -> 0;
                                case Certifying c -> {
                                    TxCert cert = c.cert();
                                    BigInteger certTag = switch (cert) {
                                        case RegStaking rs -> 0;
                                        case UnRegStaking urs -> 1;
                                        case DelegStaking ds -> 2;
                                        case RegDeleg rd -> 3;
                                        case RegDRep rdr -> 4;
                                        case UpdateDRep udr -> 5;
                                        case UnRegDRep urdr -> 6;
                                        case PoolRegister pr -> 7;
                                        case PoolRetire ptr -> 8;
                                        case AuthHotCommittee ahc -> 9;
                                        case ResignColdCommittee rcc -> 10;
                                    };
                                    yield certTag;
                                }
                                case Voting v -> 0;
                                case Proposing p -> 0;
                            };
                            return result >= 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var result = compiler.compile(source);
            assertFalse(result.hasErrors(),
                    "Nested ScriptPurpose -> TxCert switch should compile: " + result);
        }
    }
}
