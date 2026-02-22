package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Optional.of() and Optional.empty() on-chain factory methods.
 * Verifies ConstrData encoding (tag 0 = Some, tag 1 = None) and consumption via
 * isPresent(), isEmpty(), and get().
 */
class OptionalFactoryTest {

    static JulcVm vm;
    static final StdlibRegistry STDLIB = StdlibRegistry.defaultRegistry();

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    static PlutusData mockCtx(PlutusData redeemer) {
        return PlutusData.constr(0,
                PlutusData.integer(0),
                redeemer,
                PlutusData.integer(0));
    }

    @Test
    void optionalOfIntegerIsPresentReturnsTrue() {
        var source = """
            import java.math.BigInteger;
            import java.util.Optional;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Optional<BigInteger> opt = Optional.of(BigInteger.valueOf(42));
                    return opt.isPresent();
                }
            }
            """;
        var result = new JulcCompiler(STDLIB).compile(source);
        assertFalse(result.hasErrors(), "Should compile. Got: " + result.diagnostics());

        var evalResult = vm.evaluateWithArgs(result.program(),
                List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(evalResult.isSuccess(), "Optional.of(42).isPresent() should succeed. Got: " + evalResult);
    }

    @Test
    void optionalEmptyIsEmptyReturnsTrue() {
        var source = """
            import java.math.BigInteger;
            import java.util.Optional;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Optional<BigInteger> opt = Optional.empty();
                    return opt.isEmpty();
                }
            }
            """;
        var result = new JulcCompiler(STDLIB).compile(source);
        assertFalse(result.hasErrors(), "Should compile. Got: " + result.diagnostics());

        var evalResult = vm.evaluateWithArgs(result.program(),
                List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(evalResult.isSuccess(), "Optional.empty().isEmpty() should succeed. Got: " + evalResult);
    }

    @Test
    void optionalOfGetReturnsValue() {
        var source = """
            import java.math.BigInteger;
            import java.util.Optional;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Optional<BigInteger> opt = Optional.of(BigInteger.valueOf(99));
                    BigInteger val = opt.get();
                    return val.equals(BigInteger.valueOf(99));
                }
            }
            """;
        var result = new JulcCompiler(STDLIB).compile(source);
        assertFalse(result.hasErrors(), "Should compile. Got: " + result.diagnostics());

        var evalResult = vm.evaluateWithArgs(result.program(),
                List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(evalResult.isSuccess(), "Optional.of(99).get() == 99 should succeed. Got: " + evalResult);
    }

    @Test
    void optionalEmptyIsPresentReturnsFalse() {
        var source = """
            import java.math.BigInteger;
            import java.util.Optional;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Optional<BigInteger> opt = Optional.empty();
                    return !opt.isPresent();
                }
            }
            """;
        var result = new JulcCompiler(STDLIB).compile(source);
        assertFalse(result.hasErrors(), "Should compile. Got: " + result.diagnostics());

        var evalResult = vm.evaluateWithArgs(result.program(),
                List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(evalResult.isSuccess(), "!Optional.empty().isPresent() should succeed. Got: " + evalResult);
    }

    @Test
    void optionalOfDataTypedArg() {
        var source = """
            import java.math.BigInteger;
            import java.util.Optional;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    Optional<PlutusData> opt = Optional.of(redeemer);
                    return opt.isPresent();
                }
            }
            """;
        var result = new JulcCompiler(STDLIB).compile(source);
        assertFalse(result.hasErrors(), "Should compile. Got: " + result.diagnostics());

        var evalResult = vm.evaluateWithArgs(result.program(),
                List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(evalResult.isSuccess(), "Optional.of(redeemer).isPresent() should succeed. Got: " + evalResult);
    }

    @Test
    void optionalOfByteArrayArg() {
        // Test with byte[] argument (ByteStringType) to verify BData wrapping
        var source = """
            import java.math.BigInteger;
            import java.util.Optional;
            import com.bloxbean.cardano.julc.stdlib.Builtins;

            @Validator
            class TestValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, PlutusData ctx) {
                    byte[] data = Builtins.emptyByteString();
                    Optional<byte[]> opt = Optional.of(data);
                    return opt.isPresent();
                }
            }
            """;
        var result = new JulcCompiler(STDLIB).compile(source);
        assertFalse(result.hasErrors(), "Should compile. Got: " + result.diagnostics());

        var evalResult = vm.evaluateWithArgs(result.program(),
                List.of(mockCtx(PlutusData.integer(0))));
        assertTrue(evalResult.isSuccess(), "Optional.of(bytes).isPresent() should succeed. Got: " + evalResult);
    }
}
