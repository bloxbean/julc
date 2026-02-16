package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for @NewType annotation and Type.of() factory methods.
 */
class NewTypeTest {

    static JulcVm vm;
    static final StdlibRegistry STDLIB = StdlibRegistry.defaultRegistry();

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    static Program compileValidator(String source) {
        var compiler = new JulcCompiler(STDLIB::lookup);
        var result = compiler.compile(source);
        assertFalse(result.hasErrors(), "Compilation failed: " + result);
        assertNotNull(result.program(), "Program should not be null");
        return result.program();
    }

    // =========================================================================
    // Part 1: PubKeyHash.of() and other ledger type .of() factory methods
    // =========================================================================

    @Nested
    class LedgerTypeOf {

        @Test
        void pubKeyHashOfCompilesAsIdentity() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] hash = Builtins.unBData(redeemer);
                            var pkh = PubKeyHash.of(hash);
                            return Builtins.equalsByteString(pkh, hash);
                        }
                    }
                    """;
            var program = compileValidator(source);
            // PubKeyHash.of(hash) is identity on-chain, so pkh == hash
            var redeemer = PlutusData.bytes(new byte[28]);
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "PubKeyHash.of() should be identity. Got: " + result);
        }

        @Test
        void policyIdOfCompiles() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] hash = Builtins.unBData(redeemer);
                            var pid = PolicyId.of(hash);
                            return Builtins.lengthOfByteString(pid) == 28;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes(new byte[28]);
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "PolicyId.of() should be identity. Got: " + result);
        }

        @Test
        void tokenNameOfCompiles() {
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] name = Builtins.unBData(redeemer);
                            var tn = TokenName.of(name);
                            return Builtins.lengthOfByteString(tn) > 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes(new byte[]{1, 2, 3});
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "TokenName.of() should be identity. Got: " + result);
        }
    }

    // =========================================================================
    // Part 2: @NewType annotation
    // =========================================================================

    @Nested
    class NewTypeAnnotation {

        @Test
        void newTypeByteArrayResolvesToByteStringType() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.annotation.NewType;

                    @Validator
                    class TestValidator {
                        @NewType
                        record MyHash(byte[] hash) {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] raw = Builtins.unBData(redeemer);
                            var h = new MyHash(raw);
                            return Builtins.lengthOfByteString(h) == 28;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes(new byte[28]);
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "@NewType byte[] constructor should be identity. Got: " + result);
        }

        @Test
        void newTypeConstructorIsIdentity() {
            // new MyHash(bytes) compiles to just bytes — no ConstrData wrapping
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.annotation.NewType;

                    @Validator
                    class TestValidator {
                        @NewType
                        record MyHash(byte[] hash) {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] raw = Builtins.unBData(redeemer);
                            var h = new MyHash(raw);
                            // h is just raw bytes, equalsByteString should work directly
                            return Builtins.equalsByteString(h, raw);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes(new byte[]{1, 2, 3});
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "new MyHash(raw) should equal raw. Got: " + result);
        }

        @Test
        void newTypeOfFactoryMethodAutoRegistered() {
            // MyHash.of(bytes) should work via auto-registered StdlibLookup
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.annotation.NewType;

                    @Validator
                    class TestValidator {
                        @NewType
                        record MyHash(byte[] hash) {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] raw = Builtins.unBData(redeemer);
                            var h = MyHash.of(raw);
                            return Builtins.equalsByteString(h, raw);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes(new byte[]{4, 5, 6});
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "MyHash.of(raw) should equal raw. Got: " + result);
        }

        @Test
        void newTypeBigIntegerResolvesToIntegerType() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.annotation.NewType;

                    @Validator
                    class TestValidator {
                        @NewType
                        record Amount(BigInteger value) {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            BigInteger raw = Builtins.unIData(redeemer);
                            var a = new Amount(raw);
                            return a == 42;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.integer(42);
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "@NewType BigInteger should be identity. Got: " + result);
        }

        @Test
        void newTypeOfBigIntegerAutoRegistered() {
            var source = """
                    import java.math.BigInteger;
                    import com.bloxbean.cardano.julc.stdlib.annotation.NewType;

                    @Validator
                    class TestValidator {
                        @NewType
                        record Amount(BigInteger value) {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            BigInteger raw = Builtins.unIData(redeemer);
                            var a = Amount.of(raw);
                            return a == 42;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.integer(42);
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Amount.of(42) should be identity. Got: " + result);
        }

        @Test
        void rejectsMultiFieldNewType() {
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.annotation.NewType;

                    @Validator
                    class TestValidator {
                        @NewType
                        record Bad(byte[] a, byte[] b) {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return true;
                        }
                    }
                    """;
            assertThrows(CompilerException.class, () -> {
                var compiler = new JulcCompiler(STDLIB::lookup);
                compiler.compile(source);
            }, "@NewType with multiple fields should be rejected");
        }

        @Test
        void rejectsNonPrimitiveFieldNewType() {
            var source = """
                    import java.util.List;
                    import com.bloxbean.cardano.julc.stdlib.annotation.NewType;

                    @Validator
                    class TestValidator {
                        @NewType
                        record Bad(List<byte[]> items) {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            return true;
                        }
                    }
                    """;
            assertThrows(CompilerException.class, () -> {
                var compiler = new JulcCompiler(STDLIB::lookup);
                compiler.compile(source);
            }, "@NewType with non-primitive field type should be rejected");
        }

        @Test
        void endToEndNewTypeInEqualsByteString() {
            // Full end-to-end: minting validator uses @NewType in equalsByteString comparison
            // Redeemer is a record with two byte[] fields; compare them via @NewType
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.annotation.NewType;

                    @MintingValidator
                    class TestValidator {
                        @NewType
                        record AssetId(byte[] hash) {}

                        record CompareRedeemer(byte[] expected, byte[] actual) {}

                        @Entrypoint
                        static boolean validate(CompareRedeemer redeemer, PlutusData ctx) {
                            var expected = AssetId.of(redeemer.expected());
                            var actual = AssetId.of(redeemer.actual());
                            return Builtins.equalsByteString(expected, actual);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var testBytes = new byte[]{10, 20, 30, 40};
            // CompareRedeemer: Constr(0, [bData(testBytes), bData(testBytes)])
            var redeemer = PlutusData.constr(0, PlutusData.bytes(testBytes), PlutusData.bytes(testBytes));
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "End-to-end @NewType comparison should succeed. Got: " + result);
        }

        @Test
        void endToEndNewTypeMismatchFails() {
            // Same validator, different bytes should fail
            var source = """
                    import com.bloxbean.cardano.julc.stdlib.annotation.NewType;

                    @MintingValidator
                    class TestValidator {
                        @NewType
                        record AssetId(byte[] hash) {}

                        record CompareRedeemer(byte[] expected, byte[] actual) {}

                        @Entrypoint
                        static boolean validate(CompareRedeemer redeemer, PlutusData ctx) {
                            var expected = AssetId.of(redeemer.expected());
                            var actual = AssetId.of(redeemer.actual());
                            return Builtins.equalsByteString(expected, actual);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.constr(0, PlutusData.bytes(new byte[]{1, 2, 3}), PlutusData.bytes(new byte[]{4, 5, 6}));
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertFalse(result.isSuccess(), "Mismatched @NewType comparison should fail");
        }
    }
}
