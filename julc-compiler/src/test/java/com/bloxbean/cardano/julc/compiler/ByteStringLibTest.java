package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ByteStringLib stdlib integration.
 * Verifies that ByteStringLib builtin operations compile and evaluate correctly.
 * <p>
 * Uses a record wrapper to properly decode byte[] from Data (record field access
 * generates UnConstrData + HeadList + UnBData, producing a real ByteString).
 */
class ByteStringLibTest {

    private static final StdlibRegistry STDLIB = StdlibRegistry.defaultRegistry();
    private final JulcCompiler compiler = new JulcCompiler(STDLIB::lookup);
    private final JulcVm vm = JulcVm.create();

    private Program compile(String source) {
        var result = compiler.compile(source);
        assertFalse(result.hasErrors(), "Compilation should not have errors: " + result.diagnostics());
        return result.program();
    }

    private EvalResult evaluate(Program program, PlutusData ctx) {
        return vm.evaluateWithArgs(program, List.of(ctx));
    }

    /** Build ScriptContext with given redeemer. */
    private PlutusData mockCtx(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),  // txInfo
                redeemer,               // redeemer
                PlutusData.integer(0)); // scriptInfo
    }

    /** Wrap a BData bytestring in a Constr(0, [BData(bs)]) record. */
    private PlutusData wrapBs(byte[] bs) {
        return PlutusData.constr(0, PlutusData.bytes(bs));
    }

    private void assertSuccess(EvalResult result) {
        assertTrue(result.isSuccess(), "Expected success but got: " + result);
    }

    /** Common record wrapper for byte[] data. */
    static final String RECORD_HEADER = """
            import java.math.BigInteger;

            @MintingPolicy
            class TestValidator {
                record BsData(byte[] bs) {}

            """;
    static final String RECORD_FOOTER = """
            }
            """;

    private String validator(String body) {
        return RECORD_HEADER + """
                    @Entrypoint
                    static boolean validate(BsData d, PlutusData ctx) {
                        byte[] data = d.bs();
                """ + body + """
                    }
                """ + RECORD_FOOTER;
    }

    /** For tests that don't need input data. */
    private String validatorNoData(String body) {
        return """
                import java.math.BigInteger;

                @MintingPolicy
                class TestValidator {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, PlutusData ctx) {
                """ + body + """
                    }
                }
                """;
    }

    @Nested
    class AtTests {
        @Test
        void atReturnsCorrectByte() {
            // at(#"ab", 0) should be 0xab = 171
            var source = validator("""
                        int b = ByteStringLib.at(data, 0);
                        return b == 171;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(wrapBs(new byte[]{(byte) 0xab}))));
        }

        @Test
        void atSecondByte() {
            // at(#"abcd", 1) should be 0xcd = 205
            var source = validator("""
                        int b = ByteStringLib.at(data, 1);
                        return b == 205;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(wrapBs(new byte[]{(byte) 0xab, (byte) 0xcd}))));
        }
    }

    @Nested
    class ConsTests {
        @Test
        void consPrependsByte() {
            // cons(0xff, #"0102") → length == 3
            var source = validator("""
                        byte[] result = ByteStringLib.cons(255, data);
                        return ByteStringLib.length(result) == 3;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(wrapBs(new byte[]{0x01, 0x02}))));
        }

        @Test
        void consFirstByteCorrect() {
            // cons(171, empty()) → at(result, 0) == 171
            var source = validatorNoData("""
                        byte[] result = ByteStringLib.cons(171, ByteStringLib.empty());
                        int b = ByteStringLib.at(result, 0);
                        return b == 171;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(PlutusData.integer(0))));
        }
    }

    @Nested
    class SliceTests {
        @Test
        void sliceExtractsCorrectLength() {
            // slice(#"0102030405", 1, 3) → length == 3
            var source = validator("""
                        byte[] result = ByteStringLib.slice(data, 1, 3);
                        return ByteStringLib.length(result) == 3;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(wrapBs(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}))));
        }

        @Test
        void sliceFirstByteCorrect() {
            // slice(#"0102030405", 2, 2) → at(result, 0) == 3
            var source = validator("""
                        byte[] result = ByteStringLib.slice(data, 2, 2);
                        int b = ByteStringLib.at(result, 0);
                        return b == 3;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(wrapBs(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}))));
        }
    }

    @Nested
    class DropTests {
        @Test
        void dropRemovesPrefix() {
            // drop(#"0102030405", 2) → length == 3
            var source = validator("""
                        byte[] result = ByteStringLib.drop(data, 2);
                        return ByteStringLib.length(result) == 3;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(wrapBs(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}))));
        }

        @Test
        void dropFirstByteCorrect() {
            // drop(#"0102030405", 3) → at(result, 0) == 4
            var source = validator("""
                        byte[] result = ByteStringLib.drop(data, 3);
                        int b = ByteStringLib.at(result, 0);
                        return b == 4;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(wrapBs(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}))));
        }
    }

    @Nested
    class LengthTests {
        @Test
        void lengthOfEmptyIsZero() {
            var source = validatorNoData("""
                        return ByteStringLib.length(ByteStringLib.empty()) == 0;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(PlutusData.integer(0))));
        }

        @Test
        void lengthReturnsCorrectValue() {
            var source = validator("""
                        return ByteStringLib.length(data) == 5;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(wrapBs(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}))));
        }
    }

    @Nested
    class AppendTests {
        @Test
        void appendConcatenates() {
            // append(slice(data,0,2), slice(data,2,2)) → length == 4
            var source = validator("""
                        byte[] a = ByteStringLib.slice(data, 0, 2);
                        byte[] b = ByteStringLib.slice(data, 2, 2);
                        byte[] result = ByteStringLib.append(a, b);
                        return ByteStringLib.length(result) == 4;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(wrapBs(new byte[]{0x01, 0x02, 0x03, 0x04}))));
        }
    }

    @Nested
    class EmptyTests {
        @Test
        void emptyHasZeroLength() {
            var source = validatorNoData("""
                        byte[] e = ByteStringLib.empty();
                        return ByteStringLib.length(e) == 0;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(PlutusData.integer(0))));
        }
    }

    @Nested
    class ComposedOperations {
        @Test
        void consAndAtRoundTrip() {
            var source = validatorNoData("""
                        byte[] bs = ByteStringLib.cons(42, ByteStringLib.empty());
                        int b = ByteStringLib.at(bs, 0);
                        return b == 42;
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(PlutusData.integer(0))));
        }

        @Test
        void dropAndSliceEquivalent() {
            // drop(data,2) and slice(data,2,3) should have same length for 5-byte input
            var source = validator("""
                        byte[] dropped = ByteStringLib.drop(data, 2);
                        byte[] sliced = ByteStringLib.slice(data, 2, 3);
                        return ByteStringLib.length(dropped) == ByteStringLib.length(sliced);
                    """);
            var program = compile(source);
            assertSuccess(evaluate(program, mockCtx(wrapBs(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}))));
        }
    }
}
