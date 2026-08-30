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
            import com.bloxbean.cardano.julc.core.types.JulcList;
            import com.bloxbean.cardano.julc.core.types.JulcValue;
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            import com.bloxbean.cardano.julc.stdlib.lib.NativeValueLib;
            import java.math.BigInteger;
            import java.util.Optional;
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

    @Test
    void rejectsNativeValueAndDataVariableAssignmentInBothDirections() {
        var dataAsNative = assertCompileError(IMPORTS + """
                class DataAsNativeVariable {
                    static boolean bad(PlutusData data) {
                        JulcValue value = data;
                        return true;
                    }
                }
                """, "bad");
        assertEquals("JULC0041", dataAsNative.diagnostics().getFirst().code());

        var nativeAsData = assertCompileError(IMPORTS + """
                class NativeAsDataVariable {
                    static boolean bad(PlutusData data) {
                        PlutusData value = NativeValueLib.fromData(data);
                        return true;
                    }
                }
                """, "bad");
        assertEquals("JULC0041", nativeAsData.diagnostics().getFirst().code());
    }

    @Test
    void rejectsNativeValueEquality() {
        var error = assertCompileError(IMPORTS + """
                class NativeEquality {
                    static boolean bad(PlutusData left, PlutusData right) {
                        var a = NativeValueLib.fromData(left);
                        var b = NativeValueLib.fromData(right);
                        return a == b;
                    }
                }
                """, "bad");
        assertEquals("JULC0041", error.diagnostics().getFirst().code());
    }

    @Test
    void rejectsNativeValueInsideDataBackedContainers() {
        var list = assertCompileError(IMPORTS + """
                class NativeList {
                    static boolean bad(PlutusData data) {
                        var value = NativeValueLib.fromData(data);
                        var values = JulcList.of(value);
                        return true;
                    }
                }
                """, "bad");
        assertEquals("JULC0041", list.diagnostics().getFirst().code());

        var optional = assertCompileError(IMPORTS + """
                class NativeOptional {
                    static boolean bad(PlutusData data) {
                        var value = NativeValueLib.fromData(data);
                        var maybe = Optional.of(value);
                        return true;
                    }
                }
                """, "bad");
        assertEquals("JULC0041", optional.diagnostics().getFirst().code());
    }

    @Test
    void rejectsNativeValueInsideRecordDataEncoding() {
        var error = assertCompileError(IMPORTS + """
                record NativeBox(JulcValue value) {}

                class NativeRecord {
                    static boolean bad(PlutusData data) {
                        var box = new NativeBox(NativeValueLib.fromData(data));
                        return true;
                    }
                }
                """, "bad");
        assertEquals("JULC0041", error.diagnostics().getFirst().code());
    }

    @Test
    void rejectsNestedNativeValueAtCompileMethodBoundary() {
        var error = assertCompileError(IMPORTS + """
                class NativeListBoundary {
                    static BigInteger bad(JulcList<JulcValue> values) {
                        return BigInteger.ZERO;
                    }
                }
                """, "bad");
        assertEquals("JULC0042", error.diagnostics().getFirst().code());
    }

    @Test
    void reportsStableDiagnosticForNativeValidatorDatum() {
        var error = assertThrows(CompilerException.class, () ->
                new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(IMPORTS + """
                        @SpendingValidator
                        class NativeDatumValidator {
                            @Entrypoint
                            static boolean validate(
                                    JulcValue datum,
                                    PlutusData redeemer,
                                    ScriptContext context) {
                                return true;
                            }
                        }
                        """));
        assertEquals("JULC0042", error.diagnostics().getFirst().code());
    }

    private static CompileResult compile(String source, String method) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(source, method);
    }

    private static CompilerException assertCompileError(String source, String method) {
        return assertThrows(CompilerException.class, () -> compile(source, method));
    }
}
