package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeValueTypingTest {

    private static final String IMPORTS = """
            import com.bloxbean.cardano.julc.core.PlutusData;
            import com.bloxbean.cardano.julc.core.types.JulcValue;
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            import com.bloxbean.cardano.julc.stdlib.lib.NativeValueLib;
            import java.math.BigInteger;
            """;

    @Test
    void explicitNativeTypeIsZeroCostComparedWithVarInference() {
        var explicit = compile(IMPORTS + """
                class ExplicitNativeValue {
                    static BigInteger lookup(PlutusData data, byte[] policy, byte[] token) {
                        JulcValue value = NativeValueLib.fromData(data);
                        return NativeValueLib.lookupCoin(policy, token, value);
                    }
                }
                """, "lookup");
        var inferred = compile(IMPORTS + """
                class InferredNativeValue {
                    static BigInteger lookup(PlutusData data, byte[] policy, byte[] token) {
                        var value = NativeValueLib.fromData(data);
                        return NativeValueLib.lookupCoin(policy, token, value);
                    }
                }
                """, "lookup");

        assertArrayEquals(
                UplcFlatEncoder.encodeProgram(inferred.program()),
                UplcFlatEncoder.encodeProgram(explicit.program()));
    }

    @Test
    void rejectsDataWhereNativeValueIsRequiredThroughTypedAndRawApis() {
        var typed = assertCompileError(IMPORTS + """
                class TypedMixing {
                    static BigInteger bad(PlutusData data, byte[] policy, byte[] token) {
                        return NativeValueLib.lookupCoin(policy, token, data);
                    }
                }
                """, "bad");
        assertEquals("JULC0041", typed.diagnostics().getFirst().code());

        var raw = assertCompileError(IMPORTS + """
                class RawMixing {
                    static BigInteger bad(PlutusData data, byte[] policy, byte[] token) {
                        return Builtins.lookupCoin(policy, token, data);
                    }
                }
                """, "bad");
        assertEquals("JULC0041", raw.diagnostics().getFirst().code());
    }

    @Test
    void rejectsNativeValueAtDataOperationAndExternalArgumentBoundary() {
        var dataOperation = assertCompileError(IMPORTS + """
                class NativeAsData {
                    static boolean bad(PlutusData data) {
                        JulcValue value = NativeValueLib.fromData(data);
                        return Builtins.equalsData(value, data);
                    }
                }
                """, "bad");
        assertEquals("JULC0041", dataOperation.diagnostics().getFirst().code());

        var boundary = assertCompileError(IMPORTS + """
                class NativeBoundary {
                    static PlutusData bad(JulcValue value) {
                        return NativeValueLib.toData(value);
                    }
                }
                """, "bad");
        assertEquals("JULC0042", boundary.diagnostics().getFirst().code());
    }

    private static CompileResult compile(String source, String method) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(source, method);
    }

    private static CompilerException assertCompileError(String source, String method) {
        return assertThrows(CompilerException.class, () -> compile(source, method));
    }
}
