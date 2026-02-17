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
 * Tests for fromPlutusData() and toPlutusData() auto-compilation.
 *
 * On-chain, fromPlutusData() is always identity (UPLC Data is already in correct format).
 * toPlutusData() wraps primitives as Data via wrapEncode (identity for Data-backed types).
 */
class PlutusDataConversionTest {

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
    // fromPlutusData tests
    // =========================================================================

    @Nested
    class FromPlutusData {

        @Test
        void credentialFromPlutusDataIsIdentity() {
            // Credential is a sealed interface (SumType) — fromPlutusData should be identity
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var cred = Credential.fromPlutusData(redeemer);
                            // Identity: cred should equal redeemer at Data level
                            return Builtins.equalsData(cred, redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            // PubKeyCredential(hash) = Constr(0, [BData(hash)])
            var credData = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            var ctx = PlutusData.constr(0, PlutusData.integer(0), credData, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Credential.fromPlutusData() should be identity. Got: " + result);
        }

        @Test
        void pubKeyHashFromPlutusDataIsIdentity() {
            // PubKeyHash maps to ByteStringType — fromPlutusData is identity
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] raw = Builtins.unBData(redeemer);
                            var pkh = PubKeyHash.fromPlutusData(redeemer);
                            // PubKeyHash is ByteStringType; fromPlutusData passes raw Data through
                            return Builtins.equalsData(pkh, redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes(new byte[28]);
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "PubKeyHash.fromPlutusData() should be identity. Got: " + result);
        }

        @Test
        void addressFromPlutusDataIsIdentity() {
            // Address is a complex record — fromPlutusData is still identity
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var addr = Address.fromPlutusData(redeemer);
                            return Builtins.equalsData(addr, redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            // Address = Constr(0, [credential, stakingCredential])
            var cred = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            var stakeCred = PlutusData.constr(1); // None
            var addressData = PlutusData.constr(0, cred, stakeCred);
            var ctx = PlutusData.constr(0, PlutusData.integer(0), addressData, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Address.fromPlutusData() should be identity. Got: " + result);
        }

        @Test
        void userDefinedRecordFromPlutusDataIsIdentity() {
            // User-defined records also support fromPlutusData as identity
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        record MyDatum(BigInteger count, byte[] owner) {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var datum = MyDatum.fromPlutusData(redeemer);
                            return Builtins.equalsData(datum, redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            // MyDatum = Constr(0, [count, owner])
            var datumData = PlutusData.constr(0, PlutusData.integer(42), PlutusData.bytes(new byte[28]));
            var ctx = PlutusData.constr(0, PlutusData.integer(0), datumData, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "User record fromPlutusData() should be identity. Got: " + result);
        }

        @Test
        void varTypeInferenceFromPlutusData() {
            // var cred = Credential.fromPlutusData(x) should type cred as Credential (SumType)
            // Then we can switch on it
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var cred = Credential.fromPlutusData(redeemer);
                            return switch (cred) {
                                case Credential.PubKeyCredential pk -> true;
                                case Credential.ScriptCredential sc -> false;
                            };
                        }
                    }
                    """;
            var program = compileValidator(source);
            // PubKeyCredential = Constr(0, [hash])
            var credData = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            var ctx = PlutusData.constr(0, PlutusData.integer(0), credData, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Switch on fromPlutusData result should work. Got: " + result);
        }

        @Test
        void fromPlutusDataInFieldAccess() {
            // fromPlutusData result can be used for field access on records
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        record MyDatum(BigInteger count) {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var datum = MyDatum.fromPlutusData(redeemer);
                            return datum.count() > 0;
                        }
                    }
                    """;
            var program = compileValidator(source);
            var datumData = PlutusData.constr(0, PlutusData.integer(42));
            var ctx = PlutusData.constr(0, PlutusData.integer(0), datumData, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "Field access on fromPlutusData result should work. Got: " + result);
        }

        @Test
        void userDefinedSealedInterfaceFromPlutusData() {
            // User-defined sealed interfaces support fromPlutusData
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        sealed interface Action permits Mint, Burn {}
                        record Mint(BigInteger amount) implements Action {}
                        record Burn(BigInteger amount) implements Action {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var action = Action.fromPlutusData(redeemer);
                            return switch (action) {
                                case Mint m -> m.amount() > 0;
                                case Burn b -> b.amount() > 0;
                            };
                        }
                    }
                    """;
            var program = compileValidator(source);
            // Mint = Constr(0, [amount])
            var mintData = PlutusData.constr(0, PlutusData.integer(100));
            var ctx = PlutusData.constr(0, PlutusData.integer(0), mintData, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "User sealed interface fromPlutusData should work. Got: " + result);
        }
    }

    // =========================================================================
    // toPlutusData tests
    // =========================================================================

    @Nested
    class ToPlutusData {

        @Test
        void credentialToPlutusDataIsIdentity() {
            // SumType → identity (already Data)
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var cred = Credential.fromPlutusData(redeemer);
                            var data = cred.toPlutusData();
                            return Builtins.equalsData(data, redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var credData = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
            var ctx = PlutusData.constr(0, PlutusData.integer(0), credData, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "SumType.toPlutusData() should be identity. Got: " + result);
        }

        @Test
        void pubKeyHashToPlutusDataWrapsBData() {
            // ByteStringType → BData wrapping
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            byte[] raw = Builtins.unBData(redeemer);
                            var pkh = PubKeyHash.of(raw);
                            // toPlutusData on ByteStringType wraps with BData
                            var data = pkh.toPlutusData();
                            return Builtins.equalsData(data, redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.bytes(new byte[]{1, 2, 3, 4});
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "ByteStringType.toPlutusData() should wrap with BData. Got: " + result);
        }

        @Test
        void integerToPlutusDataWrapsIData() {
            // IntegerType → IData wrapping
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            long amount = Builtins.unIData(redeemer);
                            var data = Builtins.iData(amount);
                            // Roundtrip: unIData then iData should equal original
                            return Builtins.equalsData(data, redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var redeemer = PlutusData.integer(42);
            var ctx = PlutusData.constr(0, PlutusData.integer(0), redeemer, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "IntegerType IData wrapping roundtrip should work. Got: " + result);
        }

        @Test
        void roundtripFromPlutusDataToPlutusData() {
            // Type.fromPlutusData(x).toPlutusData() should equal x (for Data-backed types)
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var cred = Credential.fromPlutusData(redeemer);
                            var roundtripped = cred.toPlutusData();
                            return Builtins.equalsData(roundtripped, redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            // ScriptCredential = Constr(1, [hash])
            var credData = PlutusData.constr(1, PlutusData.bytes(new byte[28]));
            var ctx = PlutusData.constr(0, PlutusData.integer(0), credData, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "fromPlutusData -> toPlutusData roundtrip should be identity. Got: " + result);
        }

        @Test
        void recordToPlutusDataIsIdentity() {
            // RecordType → identity (already Data/ConstrData)
            var source = """
                    import com.bloxbean.cardano.julc.ledger.*;
                    import com.bloxbean.cardano.julc.stdlib.Builtins;

                    @Validator
                    class TestValidator {
                        record MyDatum(BigInteger count, byte[] owner) {}

                        @Entrypoint
                        static boolean validate(PlutusData redeemer, PlutusData ctx) {
                            var datum = MyDatum.fromPlutusData(redeemer);
                            var data = datum.toPlutusData();
                            return Builtins.equalsData(data, redeemer);
                        }
                    }
                    """;
            var program = compileValidator(source);
            var datumData = PlutusData.constr(0, PlutusData.integer(42), PlutusData.bytes(new byte[28]));
            var ctx = PlutusData.constr(0, PlutusData.integer(0), datumData, PlutusData.integer(0));
            var result = vm.evaluateWithArgs(program, List.of(ctx));
            assertTrue(result.isSuccess(), "RecordType.toPlutusData() should be identity. Got: " + result);
        }
    }
}
