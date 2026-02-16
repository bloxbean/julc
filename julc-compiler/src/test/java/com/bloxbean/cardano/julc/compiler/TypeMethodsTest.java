package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.JulcVm;
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

    static JulcVm vm;
    static final com.bloxbean.cardano.julc.stdlib.StdlibRegistry STDLIB = com.bloxbean.cardano.julc.stdlib.StdlibRegistry.defaultRegistry();

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
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
            var compiler = new JulcCompiler();
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
            var compiler = new JulcCompiler();
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
            var compiler = new JulcCompiler();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
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
            var compiler = new JulcCompiler();
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
            var compiler = new JulcCompiler();
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
            var program = new JulcCompiler().compile(source).program();
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
            var compiler = new JulcCompiler();
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
            var compiler = new JulcCompiler();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
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
            var compiler = new JulcCompiler();
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
            var compiler = new JulcCompiler();
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
            var compiler = new JulcCompiler();
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
            var compiler = new JulcCompiler();
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "Data .equals() should use EqualsData. Got: " + result);
        }
    }

    @Nested
    class BigIntegerInstanceMethods {
        @Test
        void addMethod() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(10).add(BigInteger.valueOf(5)) == 15;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "10.add(5) should be 15. Got: " + result);
        }

        @Test
        void subtractMethod() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(10).subtract(BigInteger.valueOf(3)) == 7;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "10.subtract(3) should be 7. Got: " + result);
        }

        @Test
        void multiplyMethod() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(6).multiply(BigInteger.valueOf(7)) == 42;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "6.multiply(7) should be 42. Got: " + result);
        }

        @Test
        void divideMethod() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(17).divide(BigInteger.valueOf(5)) == 3;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "17.divide(5) should be 3. Got: " + result);
        }

        @Test
        void remainderMethod() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(17).remainder(BigInteger.valueOf(5)) == 2;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "17.remainder(5) should be 2. Got: " + result);
        }

        @Test
        void modMethod() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(17).mod(BigInteger.valueOf(5)) == 2;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "17.mod(5) should be 2. Got: " + result);
        }

        @Test
        void signumPositive() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(42).signum() == 1;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "signum(42) should be 1. Got: " + result);
        }

        @Test
        void signumZero() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.ZERO.signum() == 0;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "signum(0) should be 0. Got: " + result);
        }

        @Test
        void signumNegative() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(-7).signum() == -1;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "signum(-7) should be -1. Got: " + result);
        }

        @Test
        void compareToLess() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(3).compareTo(BigInteger.valueOf(7)) == -1;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "compareTo(3, 7) should be -1. Got: " + result);
        }

        @Test
        void compareToEqual() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(5).compareTo(BigInteger.valueOf(5)) == 0;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "compareTo(5, 5) should be 0. Got: " + result);
        }

        @Test
        void compareToGreater() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(7).compareTo(BigInteger.valueOf(3)) == 1;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "compareTo(7, 3) should be 1. Got: " + result);
        }

        @Test
        void chainedArithmetic() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(10).add(BigInteger.valueOf(5)).multiply(BigInteger.valueOf(2)) == 30;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "(10 + 5) * 2 should be 30. Got: " + result);
        }
    }

    // =========================================================================
    // Edge-case tests for smart contract correctness (post-TypeMethodRegistry)
    // =========================================================================

    @Nested
    class IntegerEdgeCases {
        @Test
        void absOfZero() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.ZERO.abs() == 0;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "abs(0) should be 0. Got: " + result);
        }

        @Test
        void negateZero() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.ZERO.negate() == 0;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "negate(0) should be 0. Got: " + result);
        }

        @Test
        void maxEqualValues() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(5).max(BigInteger.valueOf(5)) == 5;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "max(5, 5) should be 5. Got: " + result);
        }

        @Test
        void minEqualValues() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(5).min(BigInteger.valueOf(5)) == 5;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "min(5, 5) should be 5. Got: " + result);
        }

        @Test
        void absResultInArithmetic() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(-3).abs() + BigInteger.valueOf(2) == 5;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "abs(-3) + 2 should be 5. Got: " + result);
        }

        @Test
        void negateResultInComparison() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            return BigInteger.valueOf(5).negate() < 0;
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "negate(5) < 0 should be true. Got: " + result);
        }
    }

    @Nested
    class ListEdgeCases {
        @Test
        void sizeOfEmptyList() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            return sigs.size() == 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // 0 signatories
            var txInfo = buildTxInfo(new PlutusData[0], alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Empty list size should be 0. Got: " + result);
        }

        @Test
        void tailThenHead() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            TxInfo txInfo = ctx.txInfo();
                            var sigs = txInfo.signatories();
                            var second = sigs.tail().head();
                            return second == second;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // 2 signatories — tail().head() gets second element
            var txInfo = buildTxInfo(
                    new PlutusData[]{PlutusData.bytes(new byte[]{1}), PlutusData.bytes(new byte[]{2})},
                    alwaysInterval());
            var ctx = buildScriptContext(txInfo, PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "tail().head() should get second element. Got: " + result);
        }
    }

    @Nested
    class OptionalEdgeCases {
        @Test
        void getFromOptionalUsedInArithmetic() {
            var source = """
                    import java.math.BigInteger;
                    import java.util.Optional;

                    @Validator
                    class TestValidator {
                        record MyDatum(Optional<BigInteger> value) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.value().get() + 10 == 52;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
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
            assertTrue(result.isSuccess(), "Some(42).get() + 10 should be 52. Got: " + result);
        }
    }

    @Nested
    class StringEdgeCases {
        @Test
        void emptyStringEquals() {
            var source = """
                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                            String a = "";
                            String b = "";
                            return a.equals(b);
                        }
                    }
                    """;
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "Empty string .equals() should return true. Got: " + result);
        }
    }

    @Nested
    class ByteStringEdgeCases {
        @Test
        void emptyByteStringLength() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record MyDatum(byte[] data) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.data().length() == 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var datum = PlutusData.constr(0, PlutusData.bytes(new byte[0]));
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Empty byte[].length() should be 0. Got: " + result);
        }
    }

    @Nested
    class CrossTypeEdgeCases {
        @Test
        void equalsMethodOnRecordField() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record MyDatum(BigInteger amount, byte[] owner) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.amount().equals(BigInteger.valueOf(100)) && datum.owner().length() > 0;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var datum = PlutusData.constr(0,
                    PlutusData.integer(100),
                    PlutusData.bytes(new byte[]{1, 2, 3}));
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "equals() on record field + length() chain should work. Got: " + result);
        }
    }

    @Nested
    class NestedRecordAccess {

        PlutusData nestedCtx(PlutusData redeemer) {
            return buildScriptContext(
                    buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0),
                    PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        }

        PlutusData datumCtx(PlutusData datum) {
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            return buildScriptContext(
                    buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
        }

        @Test
        void nestedRecordFieldAccess() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record Inner(BigInteger no, BigInteger amount) {}
                        record Outer(Inner inner) {}

                        @Entrypoint
                        static boolean validate(Outer datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.inner().amount() == 100;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // Inner = Constr(0, [IData(42), IData(100)])
            var inner = PlutusData.constr(0, PlutusData.integer(42), PlutusData.integer(100));
            // Outer = Constr(0, [inner])
            var datum = PlutusData.constr(0, inner);
            var result = vm.evaluateWithArgs(program, List.of(datumCtx(datum)));
            assertTrue(result.isSuccess(), "outer.inner().amount() should return 100. Got: " + result);
        }

        @Test
        void nestedRecordInComparison() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record Inner(BigInteger value) {}
                        record Outer(Inner nested) {}

                        @Entrypoint
                        static boolean validate(Outer datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.nested().value() == 42;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var inner = PlutusData.constr(0, PlutusData.integer(42));
            var datum = PlutusData.constr(0, inner);
            var result = vm.evaluateWithArgs(program, List.of(datumCtx(datum)));
            assertTrue(result.isSuccess(), "outer.nested().value() == 42 should be true. Got: " + result);
        }

        @Test
        void nestedRecordWithHelperMethod() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record Inner(BigInteger amount) {}
                        record Outer(Inner inner) {}

                        static boolean checkAmount(Outer o) {
                            return o.inner().amount() > 50;
                        }

                        @Entrypoint
                        static boolean validate(Outer datum, PlutusData redeemer, ScriptContext ctx) {
                            return checkAmount(datum);
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var inner = PlutusData.constr(0, PlutusData.integer(100));
            var datum = PlutusData.constr(0, inner);
            var result = vm.evaluateWithArgs(program, List.of(datumCtx(datum)));
            assertTrue(result.isSuccess(), "Helper method with nested field access should work. Got: " + result);
        }

        @Test
        void deeplyNestedAccess() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record Level3(BigInteger value) {}
                        record Level2(Level3 c) {}
                        record Level1(Level2 b) {}

                        @Entrypoint
                        static boolean validate(Level1 datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.b().c().value() == 99;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            // Level3 = Constr(0, [IData(99)])
            var level3 = PlutusData.constr(0, PlutusData.integer(99));
            // Level2 = Constr(0, [level3])
            var level2 = PlutusData.constr(0, level3);
            // Level1 = Constr(0, [level2])
            var datum = PlutusData.constr(0, level2);
            var result = vm.evaluateWithArgs(program, List.of(datumCtx(datum)));
            assertTrue(result.isSuccess(), "3-level deep nested access should work. Got: " + result);
        }

        @Test
        void nestedRecordByteArrayField() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record Inner(byte[] hash) {}
                        record Outer(Inner inner) {}

                        @Entrypoint
                        static boolean validate(Outer datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.inner().hash().length() == 3;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var inner = PlutusData.constr(0, PlutusData.bytes(new byte[]{1, 2, 3}));
            var datum = PlutusData.constr(0, inner);
            var result = vm.evaluateWithArgs(program, List.of(datumCtx(datum)));
            assertTrue(result.isSuccess(), "Nested record byte[] field length should be 3. Got: " + result);
        }

        @Test
        void nestedRecordInBooleanExpression() {
            var source = """
                    import java.math.BigInteger;

                    @Validator
                    class TestValidator {
                        record Inner(BigInteger amount, BigInteger limit) {}
                        record Outer(Inner inner) {}

                        @Entrypoint
                        static boolean validate(Outer datum, PlutusData redeemer, ScriptContext ctx) {
                            return datum.inner().amount() > 0 && datum.inner().limit() <= 1000;
                        }
                    }
                    """;
            var compiler = new JulcCompiler();
            var program = compiler.compile(source).program();

            var inner = PlutusData.constr(0, PlutusData.integer(50), PlutusData.integer(500));
            var datum = PlutusData.constr(0, inner);
            var result = vm.evaluateWithArgs(program, List.of(datumCtx(datum)));
            assertTrue(result.isSuccess(), "Nested fields in boolean expression should work. Got: " + result);
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
            var program = new JulcCompiler().compile(source).program();
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
            var program = new JulcCompiler().compile(source).program();
            var result = vm.evaluateWithArgs(program, List.of(simpleCtx()));
            assertTrue(result.isSuccess(), "Integer != should still work. Got: " + result);
        }
    }

    @Nested
    class PlutusDataValueAccessor {
        @Test
        void bytesDataValueOnVar() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        record MyDatum(PlutusData cred) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            var pkh = Builtins.unBData(Builtins.headList(Builtins.constrFields(datum.cred())));
                            byte[] raw = pkh.value();
                            return raw.length() == 3;
                        }
                    }
                    """;
            var compiler = new JulcCompiler(STDLIB::lookup);
            var program = compiler.compile(source).program();

            // cred = Constr(0, [BData(bytes)])
            var cred = PlutusData.constr(0, PlutusData.bytes(new byte[]{1, 2, 3}));
            var datum = PlutusData.constr(0, cred);
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "unBData(...).value() should be identity (ByteString). Got: " + result);
        }

        @Test
        void intDataValueOnVar() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        record MyDatum(PlutusData wrapped) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            var n = Builtins.unIData(Builtins.headList(Builtins.constrFields(datum.wrapped())));
                            BigInteger val = n.value();
                            return val == 42;
                        }
                    }
                    """;
            var compiler = new JulcCompiler(STDLIB::lookup);
            var program = compiler.compile(source).program();

            // wrapped = Constr(0, [IData(42)])
            var wrapped = PlutusData.constr(0, PlutusData.integer(42));
            var datum = PlutusData.constr(0, wrapped);
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "unIData(...).value() should be identity (Integer). Got: " + result);
        }

        @Test
        void bytesDataValueChainedWithEquals() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        record MyDatum(PlutusData cred1, PlutusData cred2) {}

                        @Entrypoint
                        static boolean validate(MyDatum datum, PlutusData redeemer, ScriptContext ctx) {
                            var a = Builtins.unBData(Builtins.headList(Builtins.constrFields(datum.cred1())));
                            var b = Builtins.unBData(Builtins.headList(Builtins.constrFields(datum.cred2())));
                            return a.value() == b.value();
                        }
                    }
                    """;
            var compiler = new JulcCompiler(STDLIB::lookup);
            var program = compiler.compile(source).program();

            var cred = PlutusData.constr(0, PlutusData.bytes(new byte[]{1, 2, 3}));
            var datum = PlutusData.constr(0, cred, cred);
            var optDatum = PlutusData.constr(0, datum);
            var txOutRef = PlutusData.constr(0, PlutusData.bytes(new byte[32]), PlutusData.integer(0));
            var scriptInfo = PlutusData.constr(1, txOutRef, optDatum);
            var ctx = buildScriptContext(buildTxInfo(new PlutusData[0], alwaysInterval()),
                    PlutusData.integer(0), scriptInfo);
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "unBData().value() == unBData().value() should compare ByteStrings. Got: " + result);
        }
    }
}
