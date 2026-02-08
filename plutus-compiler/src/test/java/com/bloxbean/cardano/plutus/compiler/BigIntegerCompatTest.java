package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.core.*;
import com.bloxbean.cardano.plutus.vm.PlutusVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BigInteger compatibility — ensures validator code that uses
 * BigInteger.valueOf(), BigInteger.ZERO/ONE/TWO/TEN, and .equals()
 * compiles and evaluates correctly under both javac and PlutusCompiler.
 */
class BigIntegerCompatTest {

    static PlutusVm vm;

    @BeforeAll
    static void setUp() {
        vm = PlutusVm.create();
    }

    /**
     * Build a minimal V3 ScriptContext for testing.
     */
    static PlutusData buildScriptContext(PlutusData txInfo, PlutusData redeemer, PlutusData scriptInfo) {
        return PlutusData.constr(0, txInfo, redeemer, scriptInfo);
    }

    static PlutusData buildTxInfo(PlutusData[] signatories, PlutusData validRange) {
        return PlutusData.constr(0,
                PlutusData.list(),                                     // 0: inputs
                PlutusData.list(),                                     // 1: referenceInputs
                PlutusData.list(),                                     // 2: outputs
                PlutusData.integer(2000000),                           // 3: fee
                PlutusData.map(),                                      // 4: mint
                PlutusData.list(),                                     // 5: certificates
                PlutusData.map(),                                      // 6: withdrawals
                validRange,                                            // 7: validRange
                PlutusData.list(signatories),                          // 8: signatories
                PlutusData.map(),                                      // 9: redeemers
                PlutusData.map(),                                      // 10: datums
                PlutusData.bytes(new byte[32]),                        // 11: txId
                PlutusData.map(),                                      // 12: votes
                PlutusData.list(),                                     // 13: proposalProcedures
                PlutusData.constr(1),                                  // 14: currentTreasuryAmount (None)
                PlutusData.constr(1)                                   // 15: treasuryDonation (None)
        );
    }

    static PlutusData alwaysInterval() {
        var negInf = PlutusData.constr(0);
        var posInf = PlutusData.constr(2);
        var trueVal = PlutusData.constr(1);
        var lowerBound = PlutusData.constr(0, negInf, trueVal);
        var upperBound = PlutusData.constr(0, posInf, trueVal);
        return PlutusData.constr(0, lowerBound, upperBound);
    }

    @Nested
    class BigIntegerValueOf {
        @Test
        void bigIntegerValueOfCompilesAsIntLiteral() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(42) == 42;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "BigInteger.valueOf(42) == 42 should be true. Got: " + result);
        }

        @Test
        void bigIntegerValueOfInArithmetic() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(10) + BigInteger.valueOf(5) == 15;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "BigInteger.valueOf(10) + BigInteger.valueOf(5) == 15 should be true. Got: " + result);
        }
    }

    @Nested
    class BigIntegerConstructor {
        @Test
        void newBigIntegerWithStringLiteral() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return new BigInteger("50000000000") == 50000000000L;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "new BigInteger(\"50000000000\") == 50000000000L should be true. Got: " + result);
        }

        @Test
        void newBigIntegerInArithmetic() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return new BigInteger("100") + new BigInteger("200") == 300;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "new BigInteger(\"100\") + new BigInteger(\"200\") == 300 should be true. Got: " + result);
        }
    }

    @Nested
    class BigIntegerConstants {
        @Test
        void bigIntegerZero() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.ZERO == 0;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "BigInteger.ZERO == 0 should be true. Got: " + result);
        }

        @Test
        void bigIntegerOne() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.ONE == 1;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "BigInteger.ONE == 1 should be true. Got: " + result);
        }

        @Test
        void bigIntegerTwoAndTen() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.TWO + BigInteger.TEN == 12;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var ctx = buildScriptContext(
                    buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "BigInteger.TWO + BigInteger.TEN == 12 should be true. Got: " + result);
        }
    }

    @Nested
    class EqualsMethod {
        @Test
        void equalsOnRecordIntField() {
            // redeemer.no().equals(BigInteger.valueOf(42))
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record MyRedeemer(BigInteger no) {}

                        @Entrypoint
                        static boolean validate(MyRedeemer redeemer, ScriptContext ctx) {
                            return redeemer.no().equals(BigInteger.valueOf(42));
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            // 2-param entrypoint: wrapper extracts redeemer from ScriptContext field 1
            // MyRedeemer(no=42) = Constr(0, [IData(42)])
            var redeemerData = PlutusData.constr(0, PlutusData.integer(42));
            var scriptInfo = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            var txInfo = buildTxInfo(new PlutusData[0], alwaysInterval());
            var ctx = buildScriptContext(txInfo, redeemerData, scriptInfo);

            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "redeemer.no().equals(BigInteger.valueOf(42)) should be true. Got: " + result);
        }

        @Test
        void equalsOnRecordIntFieldFalse() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record MyRedeemer(BigInteger no) {}

                        @Entrypoint
                        static boolean validate(MyRedeemer redeemer, ScriptContext ctx) {
                            return redeemer.no().equals(BigInteger.valueOf(42));
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            // MyRedeemer(no=99) — should fail because 99 != 42
            var redeemerData = PlutusData.constr(0, PlutusData.integer(99));
            var scriptInfo = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            var txInfo = buildTxInfo(new PlutusData[0], alwaysInterval());
            var ctx = buildScriptContext(txInfo, redeemerData, scriptInfo);

            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertFalse(result.isSuccess(), "redeemer.no().equals(42) with no=99 should fail. Got: " + result);
        }

        @Test
        void equalsOnByteStringField() {
            // datum.beneficiary().equals(redeemer) where redeemer is PlutusData (raw bytes)
            // This tests .equals() on a ByteString field against another ByteString
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record VestingDatum(byte[] beneficiary, BigInteger deadline) {}

                        @Entrypoint
                        static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.beneficiary().equals(redeemer);
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var pkh = new byte[]{1, 2, 3, 4, 5};
            var datum = PlutusData.constr(0,
                    PlutusData.bytes(pkh),
                    PlutusData.integer(1000));
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0,
                    PlutusData.bytes(new byte[32]),
                    PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var txInfo = buildTxInfo(new PlutusData[0], alwaysInterval());
            // Redeemer is BData(pkh) — same bytes as datum.beneficiary
            var ctx = buildScriptContext(txInfo, PlutusData.bytes(pkh), scriptInfo);

            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "datum.beneficiary().equals(redeemer) should be true. Got: " + result);
        }

        @Test
        void equalsWithBigIntegerZero() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record MyDatum(BigInteger value) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.value().equals(BigInteger.ZERO);
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var datum = PlutusData.constr(0, PlutusData.integer(0));
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0,
                    PlutusData.bytes(new byte[32]),
                    PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var txInfo = buildTxInfo(new PlutusData[0], alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0), scriptInfo);

            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "datum.value().equals(BigInteger.ZERO) with value=0 should be true. Got: " + result);
        }
    }

    @Nested
    class FullValidatorScenarios {
        @Test
        void vestingValidatorWithEqualsAndValueOf() {
            // Full vesting validator using .equals() and BigInteger.valueOf() — the user's original pattern
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class VestingValidator {
                        record VestingDatum(byte[] beneficiary, BigInteger deadline) {}
                        record VestingRedeemer(BigInteger no) {}

                        @Entrypoint
                        static boolean validate(VestingDatum datum, VestingRedeemer redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            boolean hasSigner = sigs.contains(datum.beneficiary());
                            boolean correctAction = redeemer.no().equals(BigInteger.valueOf(1));
                            return hasSigner && correctAction;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var beneficiary = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
            var datum = PlutusData.constr(0,
                    PlutusData.bytes(beneficiary),
                    PlutusData.integer(1000));
            // VestingRedeemer(no=1) = Constr(0, [IData(1)])
            var redeemerData = PlutusData.constr(0, PlutusData.integer(1));

            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0,
                    PlutusData.bytes(new byte[32]),
                    PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(beneficiary)},
                    alwaysInterval());
            // V3 wrapper takes single arg: ScriptContext with redeemer embedded
            var ctx = buildScriptContext(txInfo, redeemerData, scriptInfo);

            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Vesting validator with .equals() and BigInteger.valueOf() should succeed. Got: " + result);
        }

        @Test
        void vestingValidatorRejectsWrongRedeemer() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class VestingValidator {
                        record VestingDatum(byte[] beneficiary, BigInteger deadline) {}
                        record VestingRedeemer(BigInteger no) {}

                        @Entrypoint
                        static boolean validate(VestingDatum datum, VestingRedeemer redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            boolean hasSigner = sigs.contains(datum.beneficiary());
                            boolean correctAction = redeemer.no().equals(BigInteger.valueOf(1));
                            return hasSigner && correctAction;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var beneficiary = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
            var datum = PlutusData.constr(0,
                    PlutusData.bytes(beneficiary),
                    PlutusData.integer(1000));
            // VestingRedeemer(no=99) — wrong value
            var redeemerData = PlutusData.constr(0, PlutusData.integer(99));

            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0,
                    PlutusData.bytes(new byte[32]),
                    PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(beneficiary)},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, redeemerData, scriptInfo);

            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertFalse(result.isSuccess(), "Vesting validator should reject wrong redeemer. Got: " + result);
        }
    }
}
