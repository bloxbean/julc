package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.DiagnosticCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertMethodNotPorted;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertNotPorted;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameMethodRejectionAsReference;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameRejectionAsReference;

/**
 * Rejections the javac frontend makes in M3a: discovery's, with the reference's code, words and position, and the
 * allow-list's, which stop what the reference rejects even where lowering would never look (ADR-068 I2, I8).
 */
class JavacRejectionsTest {

    private static final String IMPORTS = """
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcArray;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.*;
            import org.julclang.core.PlutusData.BytesData;
            """;

    @Test
    void noValidatorAnnotation() {
        assertSameRejectionAsReference(IMPORTS + "class V { static boolean m() { return true; } }");
    }

    @Test
    void twoValidatorAnnotations() {
        assertSameRejectionAsReference(IMPORTS + """
                @SpendingValidator @MintingValidator
                class V { @Entrypoint static boolean m(PlutusData r, ScriptContext c) { return true; } }
                """);
    }

    @Test
    void aLibraryAndAValidatorAtOnce() {
        assertSameRejectionAsReference(IMPORTS + """
                @OnchainLibrary @SpendingValidator
                class V { @Entrypoint static boolean m(PlutusData r, ScriptContext c) { return true; } }
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SpendingValidator", "MintingValidator", "MultiValidator"})
    void noEntrypoint(String annotation) {
        assertSameRejectionAsReference(IMPORTS + "@" + annotation + " class V { static boolean m() { return true; } }");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SpendingValidator|ScriptContext c", "SpendingValidator|PlutusData a, PlutusData b, PlutusData d, ScriptContext c",
            "MintingValidator|ScriptContext c", "MintingValidator|PlutusData a, PlutusData b, ScriptContext c",
            "WithdrawValidator|ScriptContext c", "CertifyingValidator|PlutusData a, PlutusData b, ScriptContext c",
            "VotingValidator|ScriptContext c", "ProposingValidator|ScriptContext c", "MultiValidator|ScriptContext c"})
    void theWrongParameterCount(String annotation, String parameters) {
        assertSameRejectionAsReference(IMPORTS + "@" + annotation
                + " class V {\n    @Entrypoint static boolean m(" + parameters + ") { return true; }\n}\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@Entrypoint static boolean a(PlutusData r, ScriptContext c) { return true; } @Entrypoint(purpose = Purpose.MINT) static boolean b(PlutusData r, ScriptContext c) { return true; }",
            "@Entrypoint static boolean a(PlutusData r, ScriptContext c) { return true; } @Entrypoint static boolean b(PlutusData r, ScriptContext c) { return true; }",
            "@Entrypoint(purpose = Purpose.MINT) static boolean a(PlutusData r, ScriptContext c) { return true; } @Entrypoint(purpose = Purpose.MINT) static boolean b(PlutusData r, ScriptContext c) { return true; }",
            "@Entrypoint(purpose = Purpose.SPEND) static boolean a(ScriptContext c) { return true; }",
            "@Entrypoint(purpose = Purpose.VOTE) static boolean a(PlutusData d, PlutusData r, ScriptContext c) { return true; }"})
    void multiValidatorRules(String methods) {
        assertSameRejectionAsReference(IMPORTS + "@MultiValidator class V {\n" + methods + "\n}\n");
    }

    @Test
    void noClassAndNoMethodForCompileMethod() {
        assertSameMethodRejectionAsReference("interface P { static long m() { return 1; } }", "m");
        assertSameMethodRejectionAsReference("class P { static long n() { return 1; } }", "m");
        assertSameMethodRejectionAsReference("class P { long m() { return 1; } }", "m");
    }

    @Test
    void aBannedParamType() {
        assertSameMethodRejectionAsReference(IMPORTS + """
                class P {
                    @Param static BytesData a, b;
                    static long m() { return 1; }
                }
                """, "m");
    }

    @Test
    void aNativeArrayParam() {
        assertSameMethodRejectionAsReference(IMPORTS + """
                class P {
                    @Param static JulcArray<PlutusData> a;
                    static long m() { return 1; }
                }
                """, "m");
    }

    @Test
    void anOverloadThatDiffers() {
        assertSameMethodRejectionAsReference("""
                class P {
                    static long f(long a) { return 1; }
                    static long f(long a, long b) { return 2; }
                    static long m() { return 1; }
                }
                """, "m");
    }

    @Test
    void anOverloadOfAnEntrypoint() {
        assertSameRejectionAsReference(IMPORTS + """
                @SpendingValidator
                class V {
                    @Entrypoint static boolean validate(PlutusData r, ScriptContext c) { return true; }
                    static boolean validate(long x) { return true; }
                }
                """);
    }

    @Test
    void aNativeValueThroughARawReturn() {
        // A helper, because the compiled method's own parameters are rejected earlier, at its data boundary.
        String source = """
                import org.julclang.core.types.JulcG1;
                import org.julclang.core.types.JulcList;
                class P {
                    static JulcList raw(JulcList<JulcG1> xs) { return xs; }
                    static long m() { return 1; }
                }
                """;
        assertSameMethodRejectionAsReference(source, "m");
        var e = assertThrows(CompilerException.class, () -> new JulcCompiler(null,
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source, "m", List.of()));
        assertEquals(DiagnosticCodes.NATIVE_TYPE_MISMATCH.code(), e.diagnostics().getFirst().code(), e.getMessage());
        assertTrue(e.diagnostics().getFirst().message().contains("Return value"), e.getMessage());
    }

    /** {@code compileMethod} decodes its target's arguments from Data, so a native type cannot be one, at any depth. */
    @ParameterizedTest
    @ValueSource(strings = {"JulcValue v", "JulcList<JulcG1> xs", "long a, Optional<JulcValue> v"})
    void aNativeTypeAtTheCompiledMethodsBoundary(String parameters) {
        String source = """
                import java.util.Optional;
                import org.julclang.core.types.*;
                class P {
                    static long m(%s) { return 1; }
                }
                """.formatted(parameters);
        assertSameMethodRejectionAsReference(source, "m");
        var e = assertThrows(CompilerException.class, () -> new JulcCompiler(null,
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source, "m", List.of()));
        assertEquals(DiagnosticCodes.NATIVE_TYPE_AT_DATA_BOUNDARY.code(), e.diagnostics().getFirst().code(), e.getMessage());
    }

    @Test
    void anUnpairedSurrogateHasACodeHere() {
        String source = "class P {\n    static String m() { return \"\\uD800\"; }\n}\n";
        var e = assertThrows(CompilerException.class,
                () -> new JulcCompiler(null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source, "m", List.of()));
        var diagnostic = e.diagnostics().getFirst();
        assertEquals(DiagnosticCodes.STRING_LITERAL_UNPAIRED_SURROGATE.code(), diagnostic.code());
        assertTrue(diagnostic.message().contains("U+D800"), diagnostic.message());
        assertEquals(2, diagnostic.line());
    }

    @Test
    void aLoneSurrogateIsRejectedEvenWhereLoweringNeverLooks() {
        String source = "class P {\n    String instance() { return \"\\uDC00\"; }\n    static long m() { return 1; }\n}\n";
        var e = assertThrows(CompilerException.class,
                () -> new JulcCompiler(null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source, "m", List.of()));
        assertEquals(DiagnosticCodes.STRING_LITERAL_UNPAIRED_SURROGATE.code(), e.diagnostics().getFirst().code());
    }

    @Test
    void aPairedSurrogateIsAccepted() {
        LoweringFixture.assertSameMethodAsReference("class P { static String m() { return \"\\uD83D\\uDE00\"; } }", "m");
    }

    /** Code the reference's lowering skips but its validator rejects: without the scan, each of these would be accepted. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "String instance() { return null; }|NULL_LITERAL",
            "long instance() { int a = 1, b = 2; return a; }|VARIABLE",
            "long instance() { for (;;) { } }|FOR_LOOP",
            "long instance() { throw new RuntimeException(); }|THROW",
            "long instance() { try { return 1; } finally { } }|TRY",
            "long instance() { do { } while (true); }|DO_WHILE_LOOP",
            "byte[] instance() { return new byte[2]; }|NEW_ARRAY",
            "long[] instance() { return new long[]{1}; }|NEW_ARRAY",
            "long instance(long[] a) { return a[0]; }|ARRAY_ACCESS",
            "P instance() { return this; }|IDENTIFIER",
            "int instance() { return super.hashCode(); }|METHOD_INVOCATION",
            "double instance() { return 1; }|PRIMITIVE_TYPE",
            "float instance;|PRIMITIVE_TYPE",
            "static double sum;|PRIMITIVE_TYPE"})
    void theScanStopsWhatLoweringNeverReaches(String member, String construct) {
        String source = "class P {\n    static long m() { return 1; }\n    " + member + "\n}\n";
        assertMethodNotPorted(source, "m", construct, 3);
        assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions()).compileMethod(source, "m", List.of()),
                "this fixture is only worth having if the reference rejects it");
    }

    /** The reference accepts this: it never lowers the method, and no rule rejects a loop. So it waits for the loops. */
    @Test
    void aLoopInCodeTheReferenceDoesNotLowerIsNotPortedYet() {
        assertMethodNotPorted("class P {\n    static long m() { return 1; }\n    long instance() { while (true) { } }\n}\n",
                "m", "WHILE_LOOP", 3);
    }

    /** {@code V.this} is a member select to javac, so {@code this} is not only an identifier. */
    @Test
    void aQualifiedThisIsOutsideTheSubset() {
        assertMethodNotPorted("class V {\n    boolean b;\n    boolean g() { return V.this.b; }\n    static long m() { return 1; }\n}\n",
                "m", "MEMBER_SELECT", 3);
    }

    @Test
    void inheritanceIsOutsideTheSubset() {
        assertMethodNotPorted("class B { }\nclass P extends B { static long m() { return 1; } }\n", "m", "inheritance", 1 + 1);
        assertMethodNotPorted("interface I { }\nsealed interface J extends I permits K { }\nrecord K() implements J { }\nclass P { static long m() { return 1; } }\n",
                "m", "inheritance", 2);
    }

    @Test
    void aClassExtendingObjectIsNoInheritance() {
        LoweringFixture.assertSameMethodAsReference("class P extends Object { static long m() { return 1; } }", "m");
        LoweringFixture.assertSameMethodAsReference("class P extends java.lang.Object { static long m() { return 1; } }", "m");
    }

    /** By identity: a user's class named {@code Object} is inheritance, whatever it is spelled. */
    @Test
    void aUsersObjectIsInheritance() {
        assertMethodNotPorted("class P extends Object { static long m() { return 1; } }\nclass Object { }\n", "m", "inheritance", 1);
    }

    @Test
    void aLibraryJavacReadIsNotPortedAndNothingFallsBack() {
        var library = """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class L { public static long one() { return 1; } }
                """;
        var root = """
                import lib.L;
                class P { static long m() { return 1; } }
                """;
        var e = assertThrows(org.julclang.compiler.frontend.javac.NotPorted.class, () -> new JulcCompiler(null,
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(root, "m", List.of(library)));
        assertEquals("a library unit", e.construct());
    }

    @Test
    void anUnreferencedLibraryIsNotRead() {
        var library = """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class L { public static long one() { return 1; } }
                """;
        var bytes = new JulcCompiler(null, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                .compileMethod("class P { static long m() { return 1; } }", "m", List.of(library));
        assertTrue(bytes.program() != null);
    }

    @Test
    void aStatementNotPortedIsNamedWhereItIs() {
        var e = assertNotPorted(IMPORTS + """
                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(PlutusData r, ScriptContext c) {
                        if (true) return true;
                        return false;
                    }
                }
                """, "IF", 10);
        assertEquals("JULC0084", e.diagnostics().getFirst().code());
        assertTrue(e.getMessage().contains("IF `if (true) return true;`"), e.getMessage());
    }
}
