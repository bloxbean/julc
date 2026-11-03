package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirTerm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertMethodNotPorted;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameAsReference;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameMethodAsReference;

/** Names, static constants and {@code @Param} fields, against the reference's bytes (ADR-068 I3, D14). */
class JavacNamesTest {

    private static final CompilerOptions JAVAC = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);

    private static CompilerException rejected(String source) {
        return assertThrows(CompilerException.class, () -> new JulcCompiler(null, JAVAC).compileMethod(source, "m", List.of()));
    }

    @Test
    void parameters() {
        assertSameMethodAsReference("class P { static long m(long a, long b) { return a; } }", "m");
        assertSameMethodAsReference("class P { static long m(long a, long b) { return b; } }", "m");
        assertSameMethodAsReference("class P { static boolean m(boolean a) { return (a); } }", "m");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static final long K = 7; static long m() { return K; }",
            "static long K = 7; static long m() { return K; }",
            "static final String S = \"s\"; static String m() { return S; }",
            "static final byte[] B = new byte[]{1, 2}; static byte[] m() { return B; }",
            "static final boolean F = false; static boolean m() { return F; }",
            "static final long A = 1; static final long B = A; static long m() { return B; }",
            "static final long A = 1, B = 2; static long m() { return B; }",
            "static final long A = 1; static final long B = A, C = B; static long m() { return C; }"})
    void staticConstants(String members) {
        assertSameMethodAsReference("class P { " + members + " }", "m");
    }

    @Test
    void anUnusedStaticIsBoundAsTheReferenceBindsIt() {
        assertSameMethodAsReference("class P { static final long UNUSED = 9; static long m() { return 1; } }", "m");
    }

    /** I3: javac binds the name, and I13 chooses the binder. The bytes cannot show a capture of these, so the term does. */
    @Test
    void aParameterShadowsAStaticByElement() {
        String source = "class P { static long K = 7; static long m(long K) { return K; } }";
        assertSameMethodAsReference(source, "m");
        var pir = new JulcCompiler(null, JAVAC).compileMethod(source, "m", List.of()).pirTerm();
        var binders = new ArrayList<String>();
        collectLambdas(pir, binders);
        assertTrue(binders.contains("K'1"), binders.toString());
    }

    private static void collectLambdas(PirTerm term, List<String> into) {
        switch (term) {
            case PirTerm.Lam lam -> {
                into.add(lam.param());
                collectLambdas(lam.body(), into);
            }
            case PirTerm.Let let -> {
                collectLambdas(let.value(), into);
                collectLambdas(let.body(), into);
            }
            default -> {
            }
        }
    }

    @Test
    void validatorParameterShadowingAStatic() {
        assertSameAsReference("""
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;

                @SpendingValidator
                class V {
                    static final boolean ok = false;

                    @Entrypoint
                    static boolean validate(boolean ok, ScriptContext ctx) { return ok; }
                }
                """);
    }

    @Test
    void validatorWithStaticsHelpersAndParams() {
        assertSameAsReference("""
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;

                @SpendingValidator
                class V {
                    @Param static BigInteger first;
                    @Param static byte[] second;
                    static final boolean OK = true;
                    static final BigInteger COPY = first;

                    static BigInteger helper(BigInteger x) { return COPY; }
                    static void nothing() { }

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return OK; }
                }
                """);
    }

    @Test
    void methodsTheReferenceDoesNotLowerAreIgnoredAlike() {
        assertSameMethodAsReference("""
                class P {
                    long instance() { return 1; }
                    static long m() { return 2; }
                }
                """, "m");
        assertSameMethodAsReference("""
                record R(long a) { }
                sealed interface S permits S.A, S.B { record A() implements S { } record B(long x) implements S { } }
                class P {
                    long f = 5;
                    static R m(R r) { return r; }
                    static S n(S s) { return s; }
                }
                """, "m");
    }

    /** An interface's field is static by the language, though the source does not say so. */
    @Test
    void anInterfaceConstantIsStatic() {
        assertSameAsReference("""
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;

                @SpendingValidator
                interface V {
                    boolean K = true;
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return K; }
                }
                """);
    }

    @Test
    void aVoidMethodIsUnit() {
        assertSameMethodAsReference("class P { static void m() { } }", "m");
        assertSameMethodAsReference("class P { static void m() { return; } }", "m");
        assertSameMethodAsReference("class P { static void helper() { return; } static long m() { return 1; } }", "m");
    }

    /** javac bound the name, and nothing stands for it: the reference's words, and a position it did not give. */
    @ParameterizedTest
    @ValueSource(strings = {"static long K; static long m() { return K; }"})
    void aFieldWithNoBinderIsUndefined(String members) {
        var e = rejected("class P { " + members + " }");
        var reference = assertThrows(CompilerException.class,
                () -> new JulcCompiler(null, new CompilerOptions()).compileMethod("class P { " + members + " }", "m", List.of()));
        assertEquals("Undefined variable: K", reference.getMessage(), "the reference's words");
        var diagnostic = e.diagnostics().getFirst();
        assertEquals("Undefined variable: K", diagnostic.message());
        assertEquals(1, diagnostic.line());
    }

    @Test
    void aFieldOfAnotherClassIsUndefinedToo() {
        var e = rejected("""
                import static java.math.BigInteger.ZERO;
                class P { static java.math.BigInteger m() { return ZERO; } }
                """);
        assertEquals("Undefined variable: ZERO", e.diagnostics().getFirst().message());
    }

    @Test
    void thisIsNotPorted() {
        assertMethodNotPorted("class P { P m() { return this; } static long n() { return 1; } }", "n", "IDENTIFIER", 1);
    }

    @Test
    void aMethodNotPortedStopsTheProgramEvenUnused() {
        assertMethodNotPorted("""
                class P {
                    static long helper(long a) {
                        long b = (short) a;
                        return b;
                    }
                    static long m() { return 1; }
                }
                """, "m", "a cast to short", 3);
    }

    @Test
    void anInitializerNotPortedStopsTheProgramEvenUnused() {
        assertMethodNotPorted("""
                class P {
                    static final long K = ~1;
                    static long m() { return 1; }
                }
                """, "m", "BITWISE_COMPLEMENT", 2);
    }

    // --- D14 ---

    private static void assertConstantParam(String field) {
        String source = """
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.Param;
                class P {
                    %s
                    static long m() { return 1; }
                }
                """.formatted(field);
        var diagnostic = rejected(source).diagnostics().getFirst();
        assertEquals(DiagnosticCodes.PARAM_CONSTANT_VARIABLE.code(), diagnostic.code(), diagnostic.message());
        assertEquals(4, diagnostic.line());
        // The reference has no such rule: this is a rejection of the javac frontend alone (D13, a policy rejection).
        new JulcCompiler(null, new CompilerOptions()).compileMethod(source, "m", List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"@Param static final boolean ENABLED = true;", "@Param static final long K = 5;",
            "@Param static final int K = 5;",
            "@Param static final String S = \"s\";", "@Param final long K = 5;", "@Param static final boolean A = true, B = false;"})
    void aConstantVariableParamIsRejected(String field) {
        assertConstantParam(field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"@Param static boolean ENABLED;", "@Param static boolean ENABLED = true;", "@Param static long K = 5;",
            "@Param static final Boolean BOXED = true;", "@Param static final BigInteger REFERENCE = BigInteger.ONE;",
            "@Param static String S;"})
    void aParamJavaCannotFoldIsAccepted(String field) {
        assertSameMethodAsReference("""
                import java.math.BigInteger;
                import org.julclang.stdlib.annotation.Param;
                class P {
                    %s
                    static long m() { return 1; }
                }
                """.formatted(field), "m");
    }

    /** A constant depending on a {@code @Param} is no constant for javac, so it reads the argument (D14). */
    @Test
    void whatDependsOnAParamIsNotAConstant() {
        assertSameMethodAsReference("""
                import org.julclang.stdlib.annotation.Param;
                class P {
                    @Param static boolean ENABLED;
                    static final boolean COPY = ENABLED;
                    static boolean m() { return COPY; }
                }
                """, "m");
    }

    @Test
    void aMultiDeclaratorParamIsTwoParams() {
        assertSameMethodAsReference("""
                import org.julclang.stdlib.annotation.Param;
                class P {
                    @Param static long a, b;
                    static long m() { return b; }
                }
                """, "m");
    }

    /** A constant variable's initializer is any constant expression javac folds, and it reaches its operator in lowering. */
    @Test
    void aConstantExpressionFieldLowersItsOperator() {
        assertSameMethodAsReference("""
                class P {
                    static long m() { return K; }
                    static final long K = 1 + 1;
                }
                """, "m");
    }

    /** What the static fields of the compiled class hold lowers; a {@code BigInteger} constant keeps its position. */
    @Test
    void aStaticInitializerKeepsThePositionsOfItsConstants() {
        assertSameMethodAsReference("""
                import java.math.BigInteger;
                class P {
                    static final long NEGATIVE = -5;
                    static final BigInteger TEN = BigInteger.TEN;
                    static BigInteger m() { return TEN; }
                }
                """, "m");
    }
}
