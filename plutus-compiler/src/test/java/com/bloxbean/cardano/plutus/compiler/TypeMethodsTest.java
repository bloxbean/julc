package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.core.*;
import com.bloxbean.cardano.plutus.vm.PlutusVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for type-aware method support across all types:
 * String ops, ByteString ops, Bool equality, .length(), .tail(),
 * BigInteger .abs/.negate/.max/.min, Optional .isPresent/.isEmpty/.get, Data .equals
 */
class TypeMethodsTest {

    static PlutusVm vm;

    @BeforeAll
    static void setUp() {
        vm = PlutusVm.create();
    }

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

    static PlutusData simpleCtx() {
        return buildScriptContext(
                buildTxInfo(new PlutusData[0], alwaysInterval()),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
    }

    @Nested
    class StringEquality {
        @Test
        void stringEqualsOperator() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            String a = "hello";
                            String b = "hello";
                            return a == b;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "String == should use EqualsString. Got: " + result);
        }

        @Test
        void stringNotEqualsOperator() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            String a = "hello";
                            String b = "world";
                            return a != b;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "String != should work. Got: " + result);
        }

        @Test
        void stringEqualsMethod() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            String a = "hello";
                            String b = "hello";
                            return a.equals(b);
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "String .equals() should use EqualsString. Got: " + result);
        }
    }

    @Nested
    class StringConcatenation {
        @Test
        void stringPlusOperator() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            String a = "hel";
                            String b = "lo";
                            String c = a + b;
                            return c == "hello";
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "String + should use AppendString. Got: " + result);
        }
    }

    @Nested
    class ByteStringEquality {
        @Test
        void byteStringEqualsOperator() {
            var source = """
                    @Validator
                    class TestValidator {
                        record MyDatum(byte[] hash1, byte[] hash2) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.hash1() == datum.hash2();
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var pkh = new byte[]{1, 2, 3};
            var datum = PlutusData.constr(0, PlutusData.bytes(pkh), PlutusData.bytes(pkh));
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "byte[] == should use EqualsByteString. Got: " + result);
        }

        @Test
        void byteStringNotEqualsOperator() {
            var source = """
                    @Validator
                    class TestValidator {
                        record MyDatum(byte[] hash1, byte[] hash2) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.hash1() != datum.hash2();
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var datum = PlutusData.constr(0, PlutusData.bytes(new byte[]{1}), PlutusData.bytes(new byte[]{2}));
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "byte[] != should work. Got: " + result);
        }
    }

    @Nested
    class ByteStringAppend {
        @Test
        void byteStringPlusOperator() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record MyDatum(byte[] a, byte[] b) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            byte[] combined = datum.a() + datum.b();
                            return combined.length() == 4;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var datum = PlutusData.constr(0,
                    PlutusData.bytes(new byte[]{1, 2}),
                    PlutusData.bytes(new byte[]{3, 4}));
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "byte[] + should use AppendByteString. Got: " + result);
        }
    }

    @Nested
    class BooleanEquality {
        @Test
        void boolEqualsTrue() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            boolean a = true;
                            boolean b = true;
                            return a == b;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "true == true should be true. Got: " + result);
        }

        @Test
        void boolNotEquals() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            boolean a = true;
                            boolean b = false;
                            return a != b;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "true != false should be true. Got: " + result);
        }
    }

    @Nested
    class ByteStringLength {
        @Test
        void byteArrayLengthMethod() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record MyDatum(byte[] data) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.data().length() == 5;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var datum = PlutusData.constr(0, PlutusData.bytes(new byte[]{1, 2, 3, 4, 5}));
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "byte[].length() should return 5. Got: " + result);
        }

        @Test
        void byteArrayLengthField() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record MyDatum(byte[] data) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            byte[] d = datum.data();
                            return d.length == 3;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            var datum = PlutusData.constr(0, PlutusData.bytes(new byte[]{10, 20, 30}));
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "byte[].length field should return 3. Got: " + result);
        }
    }

    @Nested
    class StringLength {
        @Test
        void stringLengthMethod() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            String s = "hello";
                            return s.length() == 5;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "\"hello\".length() should return 5. Got: " + result);
        }
    }

    @Nested
    class ListTail {
        @Test
        void listTailChainedIsEmpty() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            return sigs.tail().isEmpty();
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            // 1 signatory => tail is empty
            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(new byte[]{1})},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "tail() of single-element list should be empty. Got: " + result);
        }

        @Test
        void listTailNotEmpty() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            return !sigs.tail().isEmpty();
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            // 2 signatories => tail is not empty
            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(new byte[]{1}), PlutusData.bytes(new byte[]{2})},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "tail() of 2-element list should not be empty. Got: " + result);
        }
    }

    @Nested
    class BigIntegerAbs {
        @Test
        void absPositive() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(5).abs() == 5;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "abs(5) should be 5. Got: " + result);
        }

        @Test
        void absNegative() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(-7).abs() == 7;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "abs(-7) should be 7. Got: " + result);
        }
    }

    @Nested
    class BigIntegerNegate {
        @Test
        void negatePositive() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(5).negate() == -5;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "negate(5) should be -5. Got: " + result);
        }

        @Test
        void negateNegative() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(-3).negate() == 3;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "negate(-3) should be 3. Got: " + result);
        }
    }

    @Nested
    class BigIntegerMaxMin {
        @Test
        void maxReturnsLarger() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(3).max(BigInteger.valueOf(7)) == 7;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "max(3, 7) should be 7. Got: " + result);
        }

        @Test
        void maxWhenFirstIsLarger() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(10).max(BigInteger.valueOf(2)) == 10;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "max(10, 2) should be 10. Got: " + result);
        }

        @Test
        void minReturnsSmaller() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(3).min(BigInteger.valueOf(7)) == 3;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "min(3, 7) should be 3. Got: " + result);
        }

        @Test
        void minWhenFirstIsSmaller() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(1).min(BigInteger.valueOf(9)) == 1;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "min(1, 9) should be 1. Got: " + result);
        }
    }

    @Nested
    class OptionalMethods {
        @Test
        void optionalIsPresentTrue() {
            var source = """
                    import java.math.BigInteger;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        record MyDatum(Optional<BigInteger> value) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.value().isPresent();
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            // Some(42) = Constr(0, [IData(42)])
            var optValue = PlutusData.constr(0, PlutusData.integer(42));
            var datum = PlutusData.constr(0, optValue);
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Some(42).isPresent() should be true. Got: " + result);
        }

        @Test
        void optionalIsPresentFalse() {
            var source = """
                    import java.math.BigInteger;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        record MyDatum(Optional<BigInteger> value) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return !datum.value().isPresent();
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            // None = Constr(1, [])
            var optValue = PlutusData.constr(1);
            var datum = PlutusData.constr(0, optValue);
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "None.isPresent() should be false. Got: " + result);
        }

        @Test
        void optionalIsEmpty() {
            var source = """
                    import java.math.BigInteger;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        record MyDatum(Optional<BigInteger> value) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.value().isEmpty();
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            // None = Constr(1, [])
            var optValue = PlutusData.constr(1);
            var datum = PlutusData.constr(0, optValue);
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "None.isEmpty() should be true. Got: " + result);
        }

        @Test
        void optionalGet() {
            var source = """
                    import java.math.BigInteger;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        record MyDatum(Optional<BigInteger> value) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.value().get() == 42;
                        }
                    }
                    """;
            var compiler = new PlutusCompiler();
            var program = compiler.compile(source).program();

            // Some(42) = Constr(0, [IData(42)])
            var optValue = PlutusData.constr(0, PlutusData.integer(42));
            var datum = PlutusData.constr(0, optValue);
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Some(42).get() should return 42. Got: " + result);
        }
    }

    @Nested
    class DataEquality {
        @Test
        void dataEqualsOperator() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData a = redeemer;
                            PlutusData b = redeemer;
                            return a == b;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "Data == Data should use EqualsData. Got: " + result);
        }

        @Test
        void dataEqualsMethod() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            PlutusData a = redeemer;
                            PlutusData b = redeemer;
                            return a.equals(b);
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "Data .equals() should use EqualsData. Got: " + result);
        }
    }

    @Nested
    class IntegerEqualityStillWorks {
        @Test
        void integerEqualsStillWorks() {
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
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "Integer == should still work. Got: " + result);
        }

        @Test
        void integerNotEqualsStillWorks() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(42) != 99;
                        }
                    }
                    """;
            var program = new PlutusCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "Integer != should still work. Got: " + result);
        }
    }
}
