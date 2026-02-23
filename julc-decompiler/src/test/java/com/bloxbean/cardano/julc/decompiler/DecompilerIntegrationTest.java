package com.bloxbean.cardano.julc.decompiler;

import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborEncoder;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests: compile JuLC validators, then decompile them.
 * Tests the full pipeline: Java source -> UPLC -> HIR -> Java
 */
class DecompilerIntegrationTest {

    private static final HexFormat HEX = HexFormat.of();

    static final String SIMPLE_ALWAYS_TRUE = """
            @Validator
            class AlwaysTrue {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;

    static final String SIMPLE_ADDITION = """
            import java.math.BigInteger;

            @Validator
            class AddValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger a = 10;
                    BigInteger b = 20;
                    return a + b == 30;
                }
            }
            """;

    static final String IF_ELSE_VALIDATOR = """
            import java.math.BigInteger;

            @Validator
            class IfElseValidator {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger x = 42;
                    if (x > 10) {
                        return true;
                    } else {
                        return false;
                    }
                }
            }
            """;

    static final String HELPER_METHOD_VALIDATOR = """
            import java.math.BigInteger;

            @Validator
            class HelperValidator {
                static boolean isPositive(BigInteger n) {
                    return n > 0;
                }

                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return isPositive(42);
                }
            }
            """;

    private Program compileValidator(String source) {
        var compiler = new JulcCompiler();
        var result = compiler.compile(source);
        if (result.hasErrors()) {
            fail("Compilation failed: " + result.diagnostics());
        }
        return result.program();
    }

    private String toDoubleCborHex(Program program) throws Exception {
        byte[] flat = UplcFlatEncoder.encodeProgram(program);
        byte[] inner = cborWrap(flat);
        byte[] outer = cborWrap(inner);
        return HEX.formatHex(outer);
    }

    private byte[] cborWrap(byte[] data) throws Exception {
        var baos = new ByteArrayOutputStream();
        new CborEncoder(baos).encode(new CborBuilder().add(data).build());
        return baos.toByteArray();
    }

    // --- Disassembly tests ---

    @Test
    void testDisassembleAlwaysTrue() {
        var program = compileValidator(SIMPLE_ALWAYS_TRUE);
        var result = JulcDecompiler.decompile(program, DecompileOptions.disassembly());

        assertNotNull(result.uplcText());
        assertTrue(result.uplcText().startsWith("(program"));
        assertNotNull(result.stats());
        assertTrue(result.stats().totalNodes() > 0);
        assertNull(result.hir());
        assertNull(result.javaSource());
    }

    @Test
    void testDisassembleFromCborHex() throws Exception {
        var program = compileValidator(SIMPLE_ALWAYS_TRUE);
        String hex = toDoubleCborHex(program);

        var result = JulcDecompiler.disassemble(hex);

        assertNotNull(result.program());
        assertNotNull(result.uplcText());
        assertNotNull(result.stats());
    }

    @Test
    void testStatsForAlwaysTrue() {
        var program = compileValidator(SIMPLE_ALWAYS_TRUE);
        var stats = ScriptAnalyzer.analyze(program);

        // Compiler wraps multi-param entrypoint into a single lambda
        assertTrue(stats.estimatedArity() >= 1);
        assertTrue(stats.totalNodes() > 0);
    }

    // --- Structured decompilation tests ---

    @Test
    void testStructuredAlwaysTrue() {
        var program = compileValidator(SIMPLE_ALWAYS_TRUE);
        var result = JulcDecompiler.decompile(program, DecompileOptions.structured());

        assertNotNull(result.hir());
        assertNull(result.javaSource());
    }

    @Test
    void testStructuredAddition() {
        var program = compileValidator(SIMPLE_ADDITION);
        var result = JulcDecompiler.decompile(program, DecompileOptions.structured());

        assertNotNull(result.hir());
        // The HIR should contain let bindings and a builtin call
    }

    @Test
    void testStructuredIfElse() {
        var program = compileValidator(IF_ELSE_VALIDATOR);
        var result = JulcDecompiler.decompile(program, DecompileOptions.structured());

        assertNotNull(result.hir());
        // Should contain If nodes
        assertTrue(containsNodeType(result.hir(), HirTerm.If.class));
    }

    // --- Full Java generation tests ---

    @Test
    void testFullJavaAlwaysTrue() {
        var program = compileValidator(SIMPLE_ALWAYS_TRUE);
        var result = JulcDecompiler.decompile(program, DecompileOptions.defaults());

        assertNotNull(result.javaSource());
        assertTrue(result.javaSource().contains("class DecompiledValidator"));
        assertTrue(result.javaSource().contains("public static boolean validate"));
    }

    @Test
    void testFullJavaAddition() {
        var program = compileValidator(SIMPLE_ADDITION);
        var result = JulcDecompiler.decompile(program, DecompileOptions.defaults());

        assertNotNull(result.javaSource());
        assertTrue(result.javaSource().contains("class DecompiledValidator"));
    }

    @Test
    void testFullJavaIfElse() {
        var program = compileValidator(IF_ELSE_VALIDATOR);
        var result = JulcDecompiler.decompile(program, DecompileOptions.defaults());

        assertNotNull(result.javaSource());
        assertTrue(result.javaSource().contains("if ("));
    }

    @Test
    void testFullJavaHelperMethod() {
        var program = compileValidator(HELPER_METHOD_VALIDATOR);
        var result = JulcDecompiler.decompile(program, DecompileOptions.defaults());

        assertNotNull(result.javaSource());
        assertTrue(result.javaSource().contains("class DecompiledValidator"));
    }

    // --- Typed decompilation ---

    @Test
    void testTypedDecompilation() {
        var program = compileValidator(SIMPLE_ADDITION);
        var result = JulcDecompiler.decompile(program, DecompileOptions.typed());

        assertNotNull(result.hir());
        assertNull(result.javaSource());
    }

    // --- Stats validation across validators ---

    @Test
    void testStatsForAddition() {
        var program = compileValidator(SIMPLE_ADDITION);
        var stats = ScriptAnalyzer.analyze(program);

        // Addition validator computes a + b == 30
        // The compiler may constant-fold the arithmetic on literals,
        // but always generates at least IfThenElse for the return wrapper
        assertFalse(stats.builtinsUsed().isEmpty(),
                "Addition validator should use at least 1 builtin, found: " + stats.builtinsUsed());
        assertTrue(stats.totalNodes() > 3, "Addition validator should have multiple AST nodes");
    }

    @Test
    void testStatsForIfElse() {
        var program = compileValidator(IF_ELSE_VALIDATOR);
        var stats = ScriptAnalyzer.analyze(program);

        // If-else validator computes x > 10 with a conditional
        // V3 scripts may use Case/Constr SOP instead of IfThenElse
        assertTrue(stats.totalNodes() > 5, "IfElse validator should have multiple AST nodes");
        assertTrue(stats.builtinsUsed().size() >= 1,
                "IfElse validator should use builtins, found: " + stats.builtinsUsed());
    }

    // --- Roundtrip: compile -> encode -> decode -> decompile ---

    @Test
    void testRoundtripViaCborHex() throws Exception {
        var program = compileValidator(SIMPLE_ALWAYS_TRUE);
        String hex = toDoubleCborHex(program);

        var result = JulcDecompiler.decompile(hex, DecompileOptions.defaults());

        assertNotNull(result.program());
        assertEquals(program.versionString(), result.program().versionString());
        assertNotNull(result.uplcText());
        assertNotNull(result.stats());
        assertNotNull(result.hir());
        assertNotNull(result.javaSource());
    }

    @Test
    void testMultipleDecompilationLevels() {
        var program = compileValidator(SIMPLE_ADDITION);

        // Disassembly
        var r1 = JulcDecompiler.decompile(program, DecompileOptions.disassembly());
        assertNotNull(r1.uplcText());
        assertNull(r1.hir());
        assertNull(r1.javaSource());

        // Structured
        var r2 = JulcDecompiler.decompile(program, DecompileOptions.structured());
        assertNotNull(r2.hir());
        assertNull(r2.javaSource());

        // Typed
        var r3 = JulcDecompiler.decompile(program, DecompileOptions.typed());
        assertNotNull(r3.hir());
        assertNull(r3.javaSource());

        // Full Java
        var r4 = JulcDecompiler.decompile(program, DecompileOptions.defaults());
        assertNotNull(r4.hir());
        assertNotNull(r4.javaSource());
    }

    // --- Helper ---

    private boolean containsNodeType(HirTerm term, Class<?> type) {
        if (type.isInstance(term)) return true;
        return switch (term) {
            case HirTerm.Let let -> containsNodeType(let.value(), type) || containsNodeType(let.body(), type);
            case HirTerm.LetRec lr -> containsNodeType(lr.value(), type) || containsNodeType(lr.body(), type);
            case HirTerm.If iff -> containsNodeType(iff.condition(), type)
                    || containsNodeType(iff.thenBranch(), type) || containsNodeType(iff.elseBranch(), type);
            case HirTerm.Lambda lam -> containsNodeType(lam.body(), type);
            case HirTerm.Switch sw -> sw.branches().stream().anyMatch(b -> containsNodeType(b.body(), type));
            case HirTerm.BuiltinCall bc -> bc.args().stream().anyMatch(a -> containsNodeType(a, type));
            case HirTerm.FunCall fc -> fc.args().stream().anyMatch(a -> containsNodeType(a, type));
            case HirTerm.Trace tr -> containsNodeType(tr.message(), type) || containsNodeType(tr.body(), type);
            case HirTerm.DataEncode de -> containsNodeType(de.operand(), type);
            case HirTerm.DataDecode dd -> containsNodeType(dd.operand(), type);
            default -> false;
        };
    }
}
