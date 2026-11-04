package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.core.PlutusData;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.testkit.JulcEval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 M5: {@code byte[].equals} on the default frontend, javac's, is {@code JULC0087}, and the remedy its fix names,
 * {@code Builtins.equalsByteString(a, b)}, compiles to the script the JavaParser frontend made of {@code a.equals(b)} and
 * compares contents on the JVM and on-chain. {@code ByteStringLib.equals(a, b)} gives the same answers.
 */
class ByteEqualsRemedyTest {

    private static String source(String imports, String comparison) {
        return """
                %s
                class C {
                    static boolean m(byte[] a, byte[] b) { return %s; }
                    static boolean inLoop(JulcList<byte[]> xs, byte[] k) {
                        boolean found = false;
                        for (var x : xs) { found = found || %s; }
                        return found;
                    }
                }
                """.formatted("import org.julclang.core.types.JulcList;\n" + imports, comparison,
                comparison.replace("(a, b)", "(x, k)").replace("a.equals(b)", "x.equals(k)"));
    }

    private static final String OLD = source("", "a.equals(b)");
    private static final String BUILTIN = source("import org.julclang.stdlib.Builtins;", "Builtins.equalsByteString(a, b)");
    private static final String LIBRARY = source("import org.julclang.stdlib.lib.ByteStringLib;", "ByteStringLib.equals(a, b)");

    @Test
    void theOldFormIsJulc0087WithTheRemedyAndTheWayBack() {
        var e = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(OLD, "m"));
        var diagnostic = e.diagnostics().getFirst();
        assertEquals(DiagnosticCodes.ARRAY_EQUALS_UNSUPPORTED.code(), diagnostic.code(), e.getMessage());
        assertTrue(diagnostic.message().contains("byte[]"), diagnostic.message());
        assertTrue(diagnostic.suggestion().contains("Builtins.equalsByteString(a, b)"), diagnostic.suggestion());
        assertTrue(diagnostic.suggestion().contains("--frontend javaparser"), diagnostic.suggestion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"m", "inLoop"})
    void theBuiltinKeepsTheJavaParserFrontendsScript(String method) {
        var reference = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAPARSER)).compileMethod(OLD, method);
        var javac = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(BUILTIN, method);
        assertFalse(javac.hasErrors(), javac.diagnostics()::toString);
        assertArrayEquals(UplcFlatEncoder.encodeProgram(reference.program()), UplcFlatEncoder.encodeProgram(javac.program()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"builtin", "library"})
    void theRemediesCompareContents(String remedy) {
        String source = remedy.equals("builtin") ? BUILTIN : LIBRARY;
        var bytes = PlutusData.bytes(new byte[]{1, 2, 3});
        var same = PlutusData.bytes(new byte[]{1, 2, 3});
        var other = PlutusData.bytes(new byte[]{1, 2, 4});
        assertTrue(JulcEval.forSource(source).call("m", bytes, same).asBoolean());
        assertFalse(JulcEval.forSource(source).call("m", bytes, other).asBoolean());
        var list = PlutusData.list(other, same);
        assertTrue(JulcEval.forSource(source).call("inLoop", list, bytes).asBoolean());
        assertFalse(JulcEval.forSource(source).call("inLoop", PlutusData.list(other), bytes).asBoolean());
    }

    @Test
    void theRemediesCompareContentsOnTheJvm() {
        assertTrue(org.julclang.stdlib.Builtins.equalsByteString(new byte[]{1, 2}, new byte[]{1, 2}));
        assertTrue(org.julclang.stdlib.lib.ByteStringLib.equals(new byte[]{1, 2}, new byte[]{1, 2}));
        assertFalse(org.julclang.stdlib.Builtins.equalsByteString(new byte[]{1, 2}, new byte[]{1, 3}));
    }
}
