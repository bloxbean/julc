package com.bloxbean.cardano.julc.examples;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the "debug-first" developer experience from Milestone 8.
 * <p>
 * Tests call validator logic DIRECTLY as plain Java method calls.
 * This enables setting breakpoints and stepping through with IntelliJ debugger.
 * <p>
 * The same validators also compile to UPLC for on-chain deployment (tested in VestingValidatorTest).
 */
class DebugVestingTest extends ContractTest {

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // --- Vesting validator: plain Java implementation ---

    /**
     * A vesting validator that checks:
     * 1. The transaction is signed by the beneficiary
     * 2. The current time is past the deadline
     *
     * This is the EXACT logic that would go into a @Validator class.
     * It's written here as a regular static method for direct debugging.
     */
    static boolean vestingValidate(byte[] beneficiary, BigInteger deadline,
                                   ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean isSigned = ContextsLib.signedBy(txInfo, beneficiary);
        boolean isPast = IntervalLib.contains(txInfo.validRange(), deadline);
        return isSigned && isPast;
    }

    // Helper to build a ScriptContext with ledger-api types
    private ScriptContext buildCtx(byte[][] signatories, Interval validRange) {
        var sigs = JulcList.of(Arrays.stream(signatories).map(PubKeyHash::new).toArray(PubKeyHash[]::new));
        var txInfo = new TxInfo(
                JulcList.of(),              // inputs
                JulcList.of(),              // referenceInputs
                JulcList.of(),              // outputs
                BigInteger.valueOf(200000), // fee
                Value.zero(),               // mint
                JulcList.of(),              // certificates
                JulcMap.empty(),            // withdrawals
                validRange,                 // validRange
                sigs,                       // signatories
                JulcMap.empty(),            // redeemers
                JulcMap.empty(),            // datums
                new TxId(new byte[32]),     // id
                JulcMap.empty(),            // votes
                JulcList.of(),              // proposalProcedures
                Optional.empty(),           // currentTreasuryAmount
                Optional.empty()            // treasuryDonation
        );
        var dummyRef = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
        return new ScriptContext(txInfo, PlutusData.UNIT,
                new ScriptInfo.SpendingScript(dummyRef, Optional.empty()));
    }

    // --- Direct Java debugging tests ---

    @Nested
    class DirectJavaTests {

        @Test
        void beneficiaryCanUnlock() {
            var pkh = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                    11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                    21, 22, 23, 24, 25, 26, 27, 28};
            var deadline = BigInteger.valueOf(1000);

            // Build context: signed by beneficiary, valid range contains the deadline
            // validRange = [500, +inf) contains deadline 1000
            var ctx = buildCtx(
                    new byte[][]{pkh},
                    Interval.after(BigInteger.valueOf(500)));

            // SET BREAKPOINT HERE — step into vestingValidate!
            boolean result = vestingValidate(pkh, deadline, ctx);
            assertTrue(result, "Beneficiary should be able to unlock past deadline");
        }

        @Test
        void wrongSignerCannotUnlock() {
            var beneficiary = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                    11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                    21, 22, 23, 24, 25, 26, 27, 28};
            var attacker = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
                    89, 88, 87, 86, 85, 84, 83, 82, 81, 80,
                    79, 78, 77, 76, 75, 74, 73, 72};
            var deadline = BigInteger.valueOf(1000);

            var ctx = buildCtx(
                    new byte[][]{attacker},
                    Interval.after(BigInteger.valueOf(500)));

            boolean result = vestingValidate(beneficiary, deadline, ctx);
            assertFalse(result, "Wrong signer should not be able to unlock");
        }

        @Test
        void cannotUnlockBeforeDeadline() {
            var pkh = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                    11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                    21, 22, 23, 24, 25, 26, 27, 28};
            var deadline = BigInteger.valueOf(5000);

            // Time is before the deadline
            var ctx = buildCtx(
                    new byte[][]{pkh},
                    Interval.before(BigInteger.valueOf(3000)));

            boolean result = vestingValidate(pkh, deadline, ctx);
            assertFalse(result, "Cannot unlock before deadline");
        }
    }

    // --- Value + IntervalLib direct tests ---

    @Nested
    class StdlibDirectTests {

        @Test
        void valueOperationsWork() {
            var value = Value.lovelace(BigInteger.valueOf(10_000_000));
            BigInteger lovelace = value.getLovelace();
            assertEquals(BigInteger.valueOf(10_000_000), lovelace);
        }

        @Test
        void nativeAssetLookupWorks() {
            var policyId = new byte[28]; // PolicyId must be 0 or 28 bytes
            policyId[0] = 1; policyId[1] = 2; policyId[2] = 3;
            var tokenName = new byte[]{65, 66, 67}; // "ABC"
            var value = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(new PolicyId(policyId), new TokenName(tokenName),
                            BigInteger.valueOf(100)));

            assertEquals(BigInteger.valueOf(2_000_000), value.getLovelace());
            assertEquals(BigInteger.valueOf(100),
                    value.getAsset(new PolicyId(policyId), new TokenName(tokenName)));
        }

        @Test
        void intervalContainsWorks() {
            var interval = Interval.between(
                    BigInteger.valueOf(1000),
                    BigInteger.valueOf(2000));
            assertTrue(IntervalLib.contains(interval, BigInteger.valueOf(1500)));
            assertFalse(IntervalLib.contains(interval, BigInteger.valueOf(500)));
            assertFalse(IntervalLib.contains(interval, BigInteger.valueOf(3000)));
        }
    }

    // --- Multi-file UPLC compilation test ---

    @Nested
    class UplcCompilationTests {

        static final String MATH_LIB = """
                import java.math.BigInteger;

                class MathUtils {
                    static BigInteger max(BigInteger a, BigInteger b) {
                        if (a > b) { return a; } else { return b; }
                    }
                }
                """;

        static final String VALIDATOR_WITH_LIB = """
                import java.math.BigInteger;

                @Validator
                class AuctionValidator {
                    @Entrypoint
                    static boolean validate(BigInteger redeemer, BigInteger ctx) {
                        BigInteger bid = 100;
                        BigInteger minBid = 50;
                        return MathUtils.max(bid, minBid) == 100;
                    }
                }
                """;

        @Test
        void multiFileValidatorCompilesAndEvaluates() {
            var program = compile(VALIDATOR_WITH_LIB, MATH_LIB);
            var ctx = PlutusData.constr(0,
                    PlutusData.integer(0),
                    PlutusData.integer(0),
                    PlutusData.integer(0));
            assertValidates(program, ctx);
        }
    }
}
