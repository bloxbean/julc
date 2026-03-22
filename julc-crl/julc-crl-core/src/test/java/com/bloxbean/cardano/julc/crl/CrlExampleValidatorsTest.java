package com.bloxbean.cardano.julc.crl;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ScriptContextTestBuilder;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for CRL example validators.
 * Each test: CRL source -> compile -> apply params -> build context -> VM evaluate.
 */
class CrlExampleValidatorsTest {

    static JulcVm vm;

    static final byte[] PKH1 = new byte[28];
    static final byte[] PKH2 = new byte[28];
    static final byte[] PKH3 = new byte[28];
    static final byte[] PKH4 = new byte[28];

    static {
        Arrays.fill(PKH1, (byte) 0x01);
        Arrays.fill(PKH2, (byte) 0x02);
        Arrays.fill(PKH3, (byte) 0x03);
        Arrays.fill(PKH4, (byte) 0x04);
    }

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void assertEvalSuccess(Program program, PlutusData ctx) {
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertTrue(result.isSuccess(), () -> "Expected success but got: " + result);
    }

    private void assertEvalFailure(Program program, PlutusData ctx) {
        var result = vm.evaluateWithArgs(program, List.of(ctx));
        assertFalse(result.isSuccess(), () -> "Expected failure but got: " + result);
    }

    private TxOutRef defaultRef() {
        return new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
    }

    // Spending validators require a datum in ScriptInfo (even if unused)
    // to avoid HeadList on empty Optional during wrapper extraction.
    private static final PlutusData DUMMY_DATUM = PlutusData.constr(0);

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── 1. SimpleTransfer ────────────────────────────────────────

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SimpleTransferTests {

        private Program program;

        @BeforeAll
        void compileOnce() {
            program = CrlTestSupport.compileCrl(CrlTestSupport.loadCrl("SimpleTransfer.crl"))
                    .applyParams(PlutusData.bytes(PKH1));
        }

        @Test
        void correctSigner_succeeds() {
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .signer(PKH1)
                    .buildPlutusData();
            assertEvalSuccess(program, ctx);
        }

        @Test
        void wrongSigner_fails() {
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .signer(PKH2)
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }

        @Test
        void noSigner_fails() {
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }
    }

    // ── 2. Vesting ───────────────────────────────────────────────

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class VestingTests {

        private Program program;

        @BeforeAll
        void compileOnce() {
            program = CrlTestSupport.compileCrl(CrlTestSupport.loadCrl("Vesting.crl"));
        }

        PlutusData vestingDatum(byte[] owner, byte[] beneficiary, long deadline) {
            return PlutusData.constr(0,
                    PlutusData.bytes(owner),
                    PlutusData.bytes(beneficiary),
                    PlutusData.integer(deadline));
        }

        @Test
        void owner_succeeds() {
            var datum = vestingDatum(PKH1, PKH2, 1000);
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), datum)
                    .signer(PKH1)
                    .buildPlutusData();
            assertEvalSuccess(program, ctx);
        }

        @Test
        void beneficiaryAfterDeadline_succeeds() {
            var datum = vestingDatum(PKH1, PKH2, 1000);
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), datum)
                    .signer(PKH2)
                    .validRange(Interval.after(BigInteger.valueOf(1000)))
                    .buildPlutusData();
            assertEvalSuccess(program, ctx);
        }

        @Test
        void beneficiaryBeforeDeadline_fails() {
            var datum = vestingDatum(PKH1, PKH2, 1000);
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), datum)
                    .signer(PKH2)
                    .validRange(Interval.before(BigInteger.valueOf(999)))
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }

        @Test
        void unknownSigner_fails() {
            var datum = vestingDatum(PKH1, PKH2, 1000);
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), datum)
                    .signer(PKH3)
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }
    }

    // ── 3. MultiSigTreasury ──────────────────────────────────────

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MultiSigTreasuryTests {

        private Program program;

        @BeforeAll
        void compileOnce() {
            program = CrlTestSupport.compileCrl(CrlTestSupport.loadCrl("MultiSigTreasury.crl"));
        }

        PlutusData treasuryDatum(byte[] s1, byte[] s2) {
            return PlutusData.constr(0,
                    PlutusData.bytes(s1),
                    PlutusData.bytes(s2));
        }

        @Test
        void bothSigners_succeeds() {
            var datum = treasuryDatum(PKH1, PKH2);
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), datum)
                    .signer(PKH1)
                    .signer(PKH2)
                    .buildPlutusData();
            assertEvalSuccess(program, ctx);
        }

        @Test
        void onlySigner1_fails() {
            var datum = treasuryDatum(PKH1, PKH2);
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), datum)
                    .signer(PKH1)
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }

        @Test
        void onlySigner2_fails() {
            var datum = treasuryDatum(PKH1, PKH2);
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), datum)
                    .signer(PKH2)
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }

        @Test
        void noSigners_fails() {
            var datum = treasuryDatum(PKH1, PKH2);
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), datum)
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }
    }

    // ── 4. MultiSigMinting ───────────────────────────────────────

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MultiSigMintingTests {

        private Program program;

        @BeforeAll
        void compileOnce() {
            program = CrlTestSupport.compileCrl(CrlTestSupport.loadCrl("MultiSigMinting.crl"))
                    .applyParams(
                            PlutusData.bytes(PKH1),  // authority
                            PlutusData.bytes(PKH2),  // owner
                            PlutusData.bytes(PKH3),  // cosigner1
                            PlutusData.bytes(PKH4)); // cosigner2
        }

        PlutusData mintCtx(PlutusData redeemer, byte[]... signers) {
            var builder = ScriptContextTestBuilder.minting(new PolicyId(new byte[28]))
                    .redeemer(redeemer);
            for (var s : signers) {
                builder.signer(s);
            }
            return builder.buildPlutusData();
        }

        @Test
        void authoritySigned_succeeds() {
            var ctx = mintCtx(PlutusData.constr(0), PKH1); // MintByAuthority, signed by authority
            assertEvalSuccess(program, ctx);
        }

        @Test
        void authorityNotSigned_fails() {
            var ctx = mintCtx(PlutusData.constr(0), PKH2); // MintByAuthority, wrong signer
            assertEvalFailure(program, ctx);
        }

        @Test
        void burnByOwner_succeeds() {
            var ctx = mintCtx(PlutusData.constr(1), PKH2); // BurnByOwner, signed by owner
            assertEvalSuccess(program, ctx);
        }

        @Test
        void multiSigBoth_succeeds() {
            var ctx = mintCtx(PlutusData.constr(2), PKH3, PKH4); // MintByMultiSig, both cosigners
            assertEvalSuccess(program, ctx);
        }

        @Test
        void multiSigOnlyOne_fails() {
            var ctx = mintCtx(PlutusData.constr(2), PKH3); // MintByMultiSig, only cosigner1
            assertEvalFailure(program, ctx);
        }
    }

    // ── 5. HTLC ─────────────────────────────────────────────────

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class HtlcTests {

        static final byte[] SECRET = "secret".getBytes(StandardCharsets.UTF_8);
        final byte[] SECRET_HASH = sha256(SECRET);
        static final long EXPIRATION = 5000;

        private Program program;

        @BeforeAll
        void compileOnce() {
            program = CrlTestSupport.compileCrl(CrlTestSupport.loadCrl("HTLC.crl"))
                    .applyParams(
                            PlutusData.bytes(SECRET_HASH),
                            PlutusData.integer(EXPIRATION),
                            PlutusData.bytes(PKH1)); // owner
        }

        @Test
        void correctGuess_succeeds() {
            var redeemer = PlutusData.constr(0, PlutusData.bytes(SECRET));
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .redeemer(redeemer)
                    .buildPlutusData();
            assertEvalSuccess(program, ctx);
        }

        @Test
        void wrongGuess_fails() {
            var redeemer = PlutusData.constr(0, PlutusData.bytes("wrong".getBytes()));
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .redeemer(redeemer)
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }

        @Test
        void ownerAfterExpiry_succeeds() {
            var redeemer = PlutusData.constr(1);
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .redeemer(redeemer)
                    .signer(PKH1)
                    .validRange(Interval.after(BigInteger.valueOf(EXPIRATION)))
                    .buildPlutusData();
            assertEvalSuccess(program, ctx);
        }

        @Test
        void ownerBeforeExpiry_fails() {
            var redeemer = PlutusData.constr(1);
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .redeemer(redeemer)
                    .signer(PKH1)
                    .validRange(Interval.before(BigInteger.valueOf(EXPIRATION - 1)))
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }

        @Test
        void nonOwnerWithdraw_fails() {
            var redeemer = PlutusData.constr(1);
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .redeemer(redeemer)
                    .signer(PKH2)
                    .validRange(Interval.after(BigInteger.valueOf(EXPIRATION)))
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }
    }

    // ── 6. TimeLock ──────────────────────────────────────────────

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TimeLockTests {

        private Program program;

        @BeforeAll
        void compileOnce() {
            program = CrlTestSupport.compileCrl(CrlTestSupport.loadCrl("TimeLock.crl"))
                    .applyParams(
                            PlutusData.bytes(PKH1),       // owner
                            PlutusData.integer(5000));     // lockTime
        }

        @Test
        void ownerAfterLockTime_succeeds() {
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .signer(PKH1)
                    .validRange(Interval.after(BigInteger.valueOf(5000)))
                    .buildPlutusData();
            assertEvalSuccess(program, ctx);
        }

        @Test
        void ownerBeforeLockTime_fails() {
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .signer(PKH1)
                    .validRange(Interval.before(BigInteger.valueOf(4999)))
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }

        @Test
        void wrongSignerAfterLockTime_fails() {
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .signer(PKH2)
                    .validRange(Interval.after(BigInteger.valueOf(5000)))
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }

        @Test
        void noSigner_fails() {
            var ctx = ScriptContextTestBuilder.spending(defaultRef(), DUMMY_DATUM)
                    .validRange(Interval.after(BigInteger.valueOf(5000)))
                    .buildPlutusData();
            assertEvalFailure(program, ctx);
        }
    }
}
