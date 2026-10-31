package org.julclang.compiler;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.JulcVm;
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
        vm = CompilerTestVm.pv11();
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
                    import org.julclang.ledger.Rational;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Rational r, ScriptContext ctx) {
                            BigInteger num = r.numerator();
                            BigInteger den = r.denominator();
                            return num.equals(BigInteger.valueOf(3)) && den.equals(BigInteger.valueOf(4));
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
                    import org.julclang.ledger.ProtocolVersion;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ProtocolVersion pv, ScriptContext ctx) {
                            BigInteger maj = pv.major();
                            BigInteger min = pv.minor();
                            return maj.equals(BigInteger.TEN) && min.equals(BigInteger.ZERO);
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
                    import org.julclang.core.PlutusData;
                    import org.julclang.ledger.GovernanceActionId;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(GovernanceActionId gaid, ScriptContext ctx) {
                            byte[] txId = PlutusData.cast(gaid.txId(), byte[].class);
                            BigInteger ix = gaid.govActionIx();
                            return ix.equals(BigInteger.valueOf(42));
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
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.Vote;
                    import org.julclang.ledger.Vote.Abstain;
                    import org.julclang.ledger.Vote.VoteNo;
                    import org.julclang.ledger.Vote.VoteYes;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Vote vote, ScriptContext ctx) {
                            BigInteger result = switch (vote) {
                                case VoteNo v -> BigInteger.ZERO;
                                case VoteYes v -> BigInteger.ONE;
                                case Abstain v -> BigInteger.TWO;
                            };
                            return result.equals(BigInteger.ONE);
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
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.Vote;
                    import org.julclang.ledger.Vote.Abstain;
                    import org.julclang.ledger.Vote.VoteNo;
                    import org.julclang.ledger.Vote.VoteYes;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Vote vote, ScriptContext ctx) {
                            BigInteger result = switch (vote) {
                                case VoteNo v -> BigInteger.ZERO;
                                case VoteYes v -> BigInteger.ONE;
                                case Abstain v -> BigInteger.TWO;
                            };
                            return result.equals(BigInteger.ZERO);
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
                    import org.julclang.ledger.DRep;
                    import org.julclang.ledger.DRep.AlwaysAbstain;
                    import org.julclang.ledger.DRep.AlwaysNoConfidence;
                    import org.julclang.ledger.DRep.DRepCredential;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;
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
                    import org.julclang.core.PlutusData;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.Voter;
                    import org.julclang.ledger.Voter.CommitteeVoter;
                    import org.julclang.ledger.Voter.DRepVoter;
                    import org.julclang.ledger.Voter.StakePoolVoter;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;
                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Voter voter, ScriptContext ctx) {
                            PlutusData result = switch (voter) {
                                case CommitteeVoter cv -> PlutusData.cast(cv.credential(), PlutusData.class);
                                case DRepVoter dv -> PlutusData.cast(dv.credential(), PlutusData.class);
                                case StakePoolVoter spv -> PlutusData.cast(spv.pubKeyHash(), PlutusData.class);
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
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.Voter;
                    import org.julclang.ledger.Voter.CommitteeVoter;
                    import org.julclang.ledger.Voter.DRepVoter;
                    import org.julclang.ledger.Voter.StakePoolVoter;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Voter voter, ScriptContext ctx) {
                            BigInteger tag = switch (voter) {
                                case CommitteeVoter cv -> BigInteger.ZERO;
                                case DRepVoter dv -> BigInteger.ONE;
                                case StakePoolVoter spv -> BigInteger.TWO;
                            };
                            return tag.equals(BigInteger.ZERO);
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
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.StakingCredential;
                    import org.julclang.ledger.StakingCredential.StakingHash;
                    import org.julclang.ledger.StakingCredential.StakingPtr;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(StakingCredential sc, ScriptContext ctx) {
                            BigInteger result = switch (sc) {
                                case StakingHash sh -> BigInteger.ZERO;
                                case StakingPtr sp -> sp.slot();
                            };
                            return result.equals(BigInteger.valueOf(100));
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
                    import org.julclang.ledger.Delegatee;
                    import org.julclang.ledger.Delegatee.Stake;
                    import org.julclang.ledger.Delegatee.StakeVote;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Delegatee d, ScriptContext ctx) {
                            BigInteger result = switch (d) {
                                case Stake s -> BigInteger.ZERO;
                                case Delegatee.Vote v -> BigInteger.ONE;
                                case StakeVote sv -> BigInteger.TWO;
                            };
                            return result.equals(BigInteger.ZERO);
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
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.TxCert;
                    import org.julclang.ledger.TxCert.AuthHotCommittee;
                    import org.julclang.ledger.TxCert.DelegStaking;
                    import org.julclang.ledger.TxCert.PoolRegister;
                    import org.julclang.ledger.TxCert.PoolRetire;
                    import org.julclang.ledger.TxCert.RegDRep;
                    import org.julclang.ledger.TxCert.RegDeleg;
                    import org.julclang.ledger.TxCert.RegStaking;
                    import org.julclang.ledger.TxCert.ResignColdCommittee;
                    import org.julclang.ledger.TxCert.UnRegDRep;
                    import org.julclang.ledger.TxCert.UnRegStaking;
                    import org.julclang.ledger.TxCert.UpdateDRep;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(TxCert cert, ScriptContext ctx) {
                            BigInteger tag = switch (cert) {
                                case RegStaking rs -> BigInteger.ZERO;
                                case UnRegStaking urs -> BigInteger.ONE;
                                case DelegStaking ds -> BigInteger.TWO;
                                case RegDeleg rd -> BigInteger.valueOf(3);
                                case RegDRep rdr -> BigInteger.valueOf(4);
                                case UpdateDRep udr -> BigInteger.valueOf(5);
                                case UnRegDRep urdr -> BigInteger.valueOf(6);
                                case PoolRegister pr -> BigInteger.valueOf(7);
                                case PoolRetire ptr -> BigInteger.valueOf(8);
                                case AuthHotCommittee ahc -> BigInteger.valueOf(9);
                                case ResignColdCommittee rcc -> BigInteger.TEN;
                            };
                            return tag.equals(BigInteger.valueOf(7));
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
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.TxCert;
                    import org.julclang.ledger.TxCert.AuthHotCommittee;
                    import org.julclang.ledger.TxCert.DelegStaking;
                    import org.julclang.ledger.TxCert.PoolRegister;
                    import org.julclang.ledger.TxCert.PoolRetire;
                    import org.julclang.ledger.TxCert.RegDRep;
                    import org.julclang.ledger.TxCert.RegDeleg;
                    import org.julclang.ledger.TxCert.RegStaking;
                    import org.julclang.ledger.TxCert.ResignColdCommittee;
                    import org.julclang.ledger.TxCert.UnRegDRep;
                    import org.julclang.ledger.TxCert.UnRegStaking;
                    import org.julclang.ledger.TxCert.UpdateDRep;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(TxCert cert, ScriptContext ctx) {
                            BigInteger tag = switch (cert) {
                                case RegStaking rs -> {
                                    var dep = rs.deposit();
                                    yield BigInteger.ZERO;
                                }
                                case UnRegStaking urs -> BigInteger.ONE;
                                case DelegStaking ds -> BigInteger.TWO;
                                case RegDeleg rd -> BigInteger.valueOf(3);
                                case RegDRep rdr -> BigInteger.valueOf(4);
                                case UpdateDRep udr -> BigInteger.valueOf(5);
                                case UnRegDRep urdr -> BigInteger.valueOf(6);
                                case PoolRegister pr -> BigInteger.valueOf(7);
                                case PoolRetire ptr -> BigInteger.valueOf(8);
                                case AuthHotCommittee ahc -> BigInteger.valueOf(9);
                                case ResignColdCommittee rcc -> BigInteger.TEN;
                            };
                            return tag.equals(BigInteger.ZERO);
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
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.TxCert;
                    import org.julclang.ledger.TxCert.AuthHotCommittee;
                    import org.julclang.ledger.TxCert.DelegStaking;
                    import org.julclang.ledger.TxCert.PoolRegister;
                    import org.julclang.ledger.TxCert.PoolRetire;
                    import org.julclang.ledger.TxCert.RegDRep;
                    import org.julclang.ledger.TxCert.RegDeleg;
                    import org.julclang.ledger.TxCert.RegStaking;
                    import org.julclang.ledger.TxCert.ResignColdCommittee;
                    import org.julclang.ledger.TxCert.UnRegDRep;
                    import org.julclang.ledger.TxCert.UnRegStaking;
                    import org.julclang.ledger.TxCert.UpdateDRep;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(TxCert cert, ScriptContext ctx) {
                            BigInteger epochVal = switch (cert) {
                                case RegStaking rs -> BigInteger.ZERO;
                                case UnRegStaking urs -> BigInteger.ZERO;
                                case DelegStaking ds -> BigInteger.ZERO;
                                case RegDeleg rd -> BigInteger.ZERO;
                                case RegDRep rdr -> BigInteger.ZERO;
                                case UpdateDRep udr -> BigInteger.ZERO;
                                case UnRegDRep urdr -> BigInteger.ZERO;
                                case PoolRegister pr -> BigInteger.ZERO;
                                case PoolRetire ptr -> ptr.epoch();
                                case AuthHotCommittee ahc -> BigInteger.ZERO;
                                case ResignColdCommittee rcc -> BigInteger.ZERO;
                            };
                            return epochVal.equals(BigInteger.valueOf(500));
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
                    import org.julclang.ledger.GovernanceAction;
                    import org.julclang.ledger.GovernanceAction.HardForkInitiation;
                    import org.julclang.ledger.GovernanceAction.InfoAction;
                    import org.julclang.ledger.GovernanceAction.NewConstitution;
                    import org.julclang.ledger.GovernanceAction.NoConfidence;
                    import org.julclang.ledger.GovernanceAction.ParameterChange;
                    import org.julclang.ledger.GovernanceAction.TreasuryWithdrawals;
                    import org.julclang.ledger.GovernanceAction.UpdateCommittee;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(GovernanceAction ga, ScriptContext ctx) {
                            BigInteger tag = switch (ga) {
                                case ParameterChange pc -> BigInteger.ZERO;
                                case HardForkInitiation hf -> BigInteger.ONE;
                                case TreasuryWithdrawals tw -> BigInteger.TWO;
                                case NoConfidence nc -> BigInteger.valueOf(3);
                                case UpdateCommittee uc -> BigInteger.valueOf(4);
                                case NewConstitution nc2 -> BigInteger.valueOf(5);
                                case InfoAction ia -> BigInteger.valueOf(6);
                            };
                            return tag.equals(BigInteger.valueOf(6));
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
                    import org.julclang.ledger.GovernanceAction;
                    import org.julclang.ledger.GovernanceAction.HardForkInitiation;
                    import org.julclang.ledger.GovernanceAction.InfoAction;
                    import org.julclang.ledger.GovernanceAction.NewConstitution;
                    import org.julclang.ledger.GovernanceAction.NoConfidence;
                    import org.julclang.ledger.GovernanceAction.ParameterChange;
                    import org.julclang.ledger.GovernanceAction.TreasuryWithdrawals;
                    import org.julclang.ledger.GovernanceAction.UpdateCommittee;
                    import org.julclang.ledger.ProtocolVersion;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(GovernanceAction ga, ScriptContext ctx) {
                            BigInteger result = switch (ga) {
                                case ParameterChange pc -> BigInteger.ZERO;
                                case HardForkInitiation hf -> {
                                    ProtocolVersion pv = hf.protocolVersion();
                                    yield pv.major();
                                }
                                case TreasuryWithdrawals tw -> BigInteger.ZERO;
                                case NoConfidence nc -> BigInteger.ZERO;
                                case UpdateCommittee uc -> BigInteger.ZERO;
                                case NewConstitution nc2 -> BigInteger.ZERO;
                                case InfoAction ia -> BigInteger.ZERO;
                            };
                            return result.equals(BigInteger.TEN);
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

        @Test
        void newConstitutionGuardrailsScriptFieldAccess() {
            // plutus-ledger-api V3: NewConstitution = Constr(5, [Maybe GovernanceActionId, Constitution]),
            // Constitution = Constr(0, [Maybe ScriptHash])
            var withScript = PlutusData.constr(5, optionalNone(),
                    PlutusData.constr(0, optionalSome(PlutusData.bytes(new byte[28]))));
            var withoutScript = PlutusData.constr(5, optionalNone(), PlutusData.constr(0, optionalNone()));

            var present = compileNewConstitutionCheck(
                    "c.script().isPresent() && Builtins.lengthOfByteString(PlutusData.cast(c.script().get(), byte[].class)) == 28");
            var result = vm.evaluateWithArgs(present, List.of(buildCtx(withScript)));
            assertTrue(result.isSuccess(), "Guardrails script should be present. Got: " + result);

            // Succeeds, so the absent case is read as Nothing rather than failing on the layout
            var absent = compileNewConstitutionCheck("!c.script().isPresent()");
            result = vm.evaluateWithArgs(absent, List.of(buildCtx(withoutScript)));
            assertTrue(result.isSuccess(), "Guardrails script should be absent. Got: " + result);
        }

        @Test
        void typedNewConstitutionBoundaryAcceptsOnlyTheLedgerLayout() {
            // The typed-parameter check validates the Constitution wrapper: ledger-shaped data is
            // accepted and the bare Maybe ScriptHash julc used to expect is rejected
            var source = """
                    import org.julclang.ledger.GovernanceAction;
                    import org.julclang.ledger.GovernanceAction.NewConstitution;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;
                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(GovernanceAction ga, ScriptContext ctx) {
                            return switch (ga) {
                                case NewConstitution nc -> true;
                                default -> false;
                            };
                        }
                    }
                    """;
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source).program();
            var hash = optionalSome(PlutusData.bytes(new byte[28]));

            for (var ledger : List.of(
                    PlutusData.constr(5, optionalNone(), PlutusData.constr(0, hash)),
                    PlutusData.constr(5, optionalNone(), PlutusData.constr(0, optionalNone())))) {
                var result = vm.evaluateWithArgs(program, List.of(buildCtx(ledger)));
                assertTrue(result.isSuccess(), "Ledger layout should be accepted. Got: " + result);
            }
            for (var bare : List.of(
                    PlutusData.constr(5, optionalNone(), hash),
                    PlutusData.constr(5, optionalNone(), optionalNone()))) {
                var result = vm.evaluateWithArgs(program, List.of(buildCtx(bare)));
                assertFalse(result.isSuccess(), "Bare Maybe ScriptHash should be rejected. Got: " + result);
            }
        }

        private Program compileNewConstitutionCheck(String check) {
            var source = """
                    import org.julclang.core.PlutusData;
                    import org.julclang.stdlib.Builtins;
                    import org.julclang.ledger.Constitution;
                    import org.julclang.ledger.GovernanceAction;
                    import org.julclang.ledger.GovernanceAction.HardForkInitiation;
                    import org.julclang.ledger.GovernanceAction.InfoAction;
                    import org.julclang.ledger.GovernanceAction.NewConstitution;
                    import org.julclang.ledger.GovernanceAction.NoConfidence;
                    import org.julclang.ledger.GovernanceAction.ParameterChange;
                    import org.julclang.ledger.GovernanceAction.TreasuryWithdrawals;
                    import org.julclang.ledger.GovernanceAction.UpdateCommittee;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(GovernanceAction ga, ScriptContext ctx) {
                            return switch (ga) {
                                case ParameterChange pc -> false;
                                case HardForkInitiation hf -> false;
                                case TreasuryWithdrawals tw -> false;
                                case NoConfidence nc -> false;
                                case UpdateCommittee uc -> false;
                                case NewConstitution nc2 -> {
                                    Constitution c = nc2.constitution();
                                    yield %s;
                                }
                                case InfoAction ia -> false;
                            };
                        }
                    }
                    """.formatted(check);
            return new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source).program();
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
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.ScriptPurpose;
                    import org.julclang.ledger.ScriptPurpose.Certifying;
                    import org.julclang.ledger.ScriptPurpose.Minting;
                    import org.julclang.ledger.ScriptPurpose.Proposing;
                    import org.julclang.ledger.ScriptPurpose.Rewarding;
                    import org.julclang.ledger.ScriptPurpose.Spending;
                    import org.julclang.ledger.ScriptPurpose.Voting;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ScriptPurpose sp, ScriptContext ctx) {
                            BigInteger tag = switch (sp) {
                                case Minting m -> BigInteger.ZERO;
                                case Spending s -> BigInteger.ONE;
                                case Rewarding r -> BigInteger.TWO;
                                case Certifying c -> BigInteger.valueOf(3);
                                case Voting v -> BigInteger.valueOf(4);
                                case Proposing p -> BigInteger.valueOf(5);
                            };
                            return tag.equals(BigInteger.ZERO);
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
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.ScriptPurpose;
                    import org.julclang.ledger.ScriptPurpose.Certifying;
                    import org.julclang.ledger.ScriptPurpose.Minting;
                    import org.julclang.ledger.ScriptPurpose.Proposing;
                    import org.julclang.ledger.ScriptPurpose.Rewarding;
                    import org.julclang.ledger.ScriptPurpose.Spending;
                    import org.julclang.ledger.ScriptPurpose.Voting;
                    import org.julclang.ledger.TxOutRef;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ScriptPurpose sp, ScriptContext ctx) {
                            BigInteger result = switch (sp) {
                                case Minting m -> BigInteger.ZERO;
                                case Spending s -> {
                                    TxOutRef ref = s.txOutRef();
                                    yield ref.index();
                                }
                                case Rewarding r -> BigInteger.ZERO;
                                case Certifying c -> BigInteger.ZERO;
                                case Voting v -> BigInteger.ZERO;
                                case Proposing p -> BigInteger.ZERO;
                            };
                            return result.equals(BigInteger.valueOf(3));
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
                    import org.julclang.ledger.ProposalProcedure;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ProposalProcedure pp, ScriptContext ctx) {
                            BigInteger dep = pp.deposit();
                            return dep.equals(BigInteger.valueOf(500));
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
                    import org.julclang.ledger.Committee;
                    import org.julclang.ledger.Rational;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;
                    import java.math.BigInteger;
                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Committee c, ScriptContext ctx) {
                            Rational q = c.quorum();
                            return q.numerator().equals(BigInteger.TWO) && q.denominator().equals(BigInteger.valueOf(3));
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
                    import org.julclang.core.PlutusData;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.ScriptInfo;
                    import org.julclang.ledger.ScriptInfo.CertifyingScript;
                    import org.julclang.ledger.ScriptInfo.MintingScript;
                    import org.julclang.ledger.ScriptInfo.ProposingScript;
                    import org.julclang.ledger.ScriptInfo.RewardingScript;
                    import org.julclang.ledger.ScriptInfo.SpendingScript;
                    import org.julclang.ledger.ScriptInfo.VotingScript;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            ScriptInfo info = ctx.scriptInfo();
                            BigInteger result = switch (info) {
                                case MintingScript ms -> BigInteger.ZERO;
                                case SpendingScript ss -> BigInteger.ZERO;
                                case RewardingScript rs -> BigInteger.ZERO;
                                case CertifyingScript cs -> {
                                    BigInteger idx = cs.index();
                                    yield idx;
                                }
                                case VotingScript vs -> BigInteger.ZERO;
                                case ProposingScript ps -> BigInteger.ZERO;
                            };
                            return result.equals(BigInteger.valueOf(5));
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
                    import org.julclang.core.PlutusData;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.ScriptInfo;
                    import org.julclang.ledger.ScriptInfo.CertifyingScript;
                    import org.julclang.ledger.ScriptInfo.MintingScript;
                    import org.julclang.ledger.ScriptInfo.ProposingScript;
                    import org.julclang.ledger.ScriptInfo.RewardingScript;
                    import org.julclang.ledger.ScriptInfo.SpendingScript;
                    import org.julclang.ledger.ScriptInfo.VotingScript;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            ScriptInfo info = ctx.scriptInfo();
                            BigInteger tag = switch (info) {
                                case MintingScript ms -> BigInteger.ZERO;
                                case SpendingScript ss -> BigInteger.ONE;
                                case RewardingScript rs -> BigInteger.TWO;
                                case CertifyingScript cs -> BigInteger.valueOf(3);
                                case VotingScript vs -> BigInteger.valueOf(4);
                                case ProposingScript ps -> BigInteger.valueOf(5);
                            };
                            return tag.equals(BigInteger.valueOf(4));
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
                    import org.julclang.core.PlutusData;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.TxInfo;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;
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
                    import org.julclang.core.PlutusData;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.TxInfo;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;
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
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;
                    import org.julclang.core.PlutusData;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

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
                    import org.julclang.ledger.GovernanceAction;
                    import org.julclang.ledger.GovernanceAction.HardForkInitiation;
                    import org.julclang.ledger.GovernanceAction.InfoAction;
                    import org.julclang.ledger.GovernanceAction.NewConstitution;
                    import org.julclang.ledger.GovernanceAction.NoConfidence;
                    import org.julclang.ledger.GovernanceAction.ParameterChange;
                    import org.julclang.ledger.GovernanceAction.TreasuryWithdrawals;
                    import org.julclang.ledger.GovernanceAction.UpdateCommittee;
                    import org.julclang.ledger.ProposalProcedure;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ProposalProcedure pp, ScriptContext ctx) {
                            GovernanceAction ga = pp.governanceAction();
                            BigInteger tag = switch (ga) {
                                case ParameterChange pc -> BigInteger.ZERO;
                                case HardForkInitiation hf -> BigInteger.ONE;
                                case TreasuryWithdrawals tw -> BigInteger.TWO;
                                case NoConfidence nc -> BigInteger.valueOf(3);
                                case UpdateCommittee uc -> BigInteger.valueOf(4);
                                case NewConstitution nc2 -> BigInteger.valueOf(5);
                                case InfoAction ia -> BigInteger.valueOf(6);
                            };
                            return tag.compareTo(BigInteger.ZERO) >= 0;
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
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.ledger.ScriptPurpose;
                    import org.julclang.ledger.ScriptPurpose.Certifying;
                    import org.julclang.ledger.ScriptPurpose.Minting;
                    import org.julclang.ledger.ScriptPurpose.Proposing;
                    import org.julclang.ledger.ScriptPurpose.Rewarding;
                    import org.julclang.ledger.ScriptPurpose.Spending;
                    import org.julclang.ledger.ScriptPurpose.Voting;
                    import org.julclang.ledger.TxCert;
                    import org.julclang.ledger.TxCert.AuthHotCommittee;
                    import org.julclang.ledger.TxCert.DelegStaking;
                    import org.julclang.ledger.TxCert.PoolRegister;
                    import org.julclang.ledger.TxCert.PoolRetire;
                    import org.julclang.ledger.TxCert.RegDRep;
                    import org.julclang.ledger.TxCert.RegDeleg;
                    import org.julclang.ledger.TxCert.RegStaking;
                    import org.julclang.ledger.TxCert.ResignColdCommittee;
                    import org.julclang.ledger.TxCert.UnRegDRep;
                    import org.julclang.ledger.TxCert.UnRegStaking;
                    import org.julclang.ledger.TxCert.UpdateDRep;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(ScriptPurpose sp, ScriptContext ctx) {
                            BigInteger result = switch (sp) {
                                case Minting m -> BigInteger.ZERO;
                                case Spending s -> BigInteger.ZERO;
                                case Rewarding r -> BigInteger.ZERO;
                                case Certifying c -> {
                                    TxCert cert = c.cert();
                                    BigInteger certTag = switch (cert) {
                                        case RegStaking rs -> BigInteger.ZERO;
                                        case UnRegStaking urs -> BigInteger.ONE;
                                        case DelegStaking ds -> BigInteger.TWO;
                                        case RegDeleg rd -> BigInteger.valueOf(3);
                                        case RegDRep rdr -> BigInteger.valueOf(4);
                                        case UpdateDRep udr -> BigInteger.valueOf(5);
                                        case UnRegDRep urdr -> BigInteger.valueOf(6);
                                        case PoolRegister pr -> BigInteger.valueOf(7);
                                        case PoolRetire ptr -> BigInteger.valueOf(8);
                                        case AuthHotCommittee ahc -> BigInteger.valueOf(9);
                                        case ResignColdCommittee rcc -> BigInteger.TEN;
                                    };
                                    yield certTag;
                                }
                                case Voting v -> BigInteger.ZERO;
                                case Proposing p -> BigInteger.ZERO;
                            };
                            return result.compareTo(BigInteger.ZERO) >= 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var result = compiler.compile(source);
            assertFalse(result.hasErrors(),
                    "Nested ScriptPurpose -> TxCert switch should compile: " + result);
        }
    }

    // =======================================================================
    // A member type of the validator class shadows the bundled ledger type
    // with the same simple name, as javac binds it (JLS 6.4.1).
    // =======================================================================

    @Nested
    class MemberTypeShadowing {

        private Program compileWithNestedRecord(String typeName, String packageLine) {
            return compileWithNestedRecord(typeName, packageLine, "c.value().equals(BigInteger.valueOf(42))");
        }

        private Program compileWithNestedRecord(String typeName, String packageLine, String check) {
            var source = """
                    %s
                    import java.math.BigInteger;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        record %s(BigInteger value) {}

                        @Entrypoint
                        static boolean validate(%s c, ScriptContext ctx) {
                            return %s;
                        }
                    }
                    """.formatted(packageLine, typeName, typeName, check);
            return new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source).program();
        }

        private void assertBindsNestedRecord(Program program) {
            var nested = vm.evaluateWithArgs(program,
                    List.of(buildCtx(PlutusData.constr(0, PlutusData.integer(42)))));
            assertTrue(nested.isSuccess(), "Nested record payload should be accepted. Got: " + nested);
            var ledgerShaped = vm.evaluateWithArgs(program,
                    List.of(buildCtx(PlutusData.constr(0, optionalNone()))));
            assertFalse(ledgerShaped.isSuccess(), "Ledger-shaped payload should be rejected. Got: " + ledgerShaped);
        }

        @Test
        void nestedConstitutionBindsToNestedRecord() {
            assertBindsNestedRecord(compileWithNestedRecord("Constitution", ""));
        }

        @Test
        void unusedNestedConstitutionParameterValidatesNestedSchema() {
            // The typed boundary alone decides acceptance: no member access forces the binding
            assertBindsNestedRecord(compileWithNestedRecord("Constitution", "", "true"));
        }

        @Test
        void nestedCommitteeBindsToNestedRecord() {
            assertBindsNestedRecord(compileWithNestedRecord("Committee", ""));
        }

        @Test
        void nestedConstitutionInPackageBindsToNestedRecord() {
            assertBindsNestedRecord(compileWithNestedRecord("Constitution", "package com.example;"));
        }

        @Test
        void qualifiedTypeNestedInPlainClassBindsToThatType() {
            var source = """
                    import java.math.BigInteger;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    @MintingValidator
                    class TestValidator {
                        static class Inner {
                            record Constitution(BigInteger value) {}
                        }

                        @Entrypoint
                        static boolean validate(Inner.Constitution c, ScriptContext ctx) {
                            return %s;
                        }
                    }
                    """;
            for (var check : List.of("c.value().equals(BigInteger.valueOf(42))", "true")) {
                assertBindsNestedRecord(new JulcCompiler(StdlibRegistry.defaultRegistry())
                        .compile(source.formatted(check)).program());
            }
        }

        @Test
        void typeNestedInAnotherTopLevelClassDoesNotCaptureTheName() {
            // javac: Helper.Committee is not in scope in TestValidator, so Committee is the ledger type
            var source = """
                    import java.math.BigInteger;
                    import org.julclang.ledger.Committee;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;

                    class Helper {
                        record Committee(BigInteger value) {}
                    }

                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Committee c, ScriptContext ctx) {
                            return true;
                        }
                    }
                    """;
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source).program();
            var ledger = vm.evaluateWithArgs(program,
                    List.of(buildCtx(PlutusData.constr(0, PlutusData.map(), rational(2, 3)))));
            assertTrue(ledger.isSuccess(), "Ledger Committee payload should be accepted. Got: " + ledger);
            var helper = vm.evaluateWithArgs(program,
                    List.of(buildCtx(PlutusData.constr(0, PlutusData.integer(42)))));
            assertFalse(helper.isSuccess(), "Helper.Committee payload should be rejected. Got: " + helper);
        }

        @Test
        void withoutNestedTypeConstitutionBindsToLedgerType() {
            var source = """
                    import org.julclang.ledger.Constitution;
                    import org.julclang.ledger.ScriptContext;
                    import org.julclang.stdlib.annotation.Entrypoint;
                    import org.julclang.stdlib.annotation.MintingValidator;
                    @MintingValidator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(Constitution c, ScriptContext ctx) {
                            return !c.script().isPresent();
                        }
                    }
                    """;
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source).program();
            var ledger = vm.evaluateWithArgs(program, List.of(buildCtx(PlutusData.constr(0, optionalNone()))));
            assertTrue(ledger.isSuccess(), "Ledger Constitution payload should be accepted. Got: " + ledger);
            var integer = vm.evaluateWithArgs(program,
                    List.of(buildCtx(PlutusData.constr(0, PlutusData.integer(42)))));
            assertFalse(integer.isSuccess(), "Integer payload should be rejected. Got: " + integer);
        }
    }
}
