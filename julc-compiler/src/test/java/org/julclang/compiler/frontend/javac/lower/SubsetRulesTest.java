package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.frontend.javac.subset.UnloweredInitializers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameAsReference;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameMethodAsReference;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameMethodRejectionAsReference;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameRejectionAsReference;

/**
 * The subset's rules for an entry point and for what a field initializer may hold, on both frontends: the same code, words
 * and position. Rules only the javac frontend makes, because they are about what javac resolved, say so.
 */
class SubsetRulesTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.*;
            """;

    private static String code(Function<JulcCompiler, ?> compile, CompilerFrontend frontend) {
        var options = new CompilerOptions().setFrontend(frontend).setOptimizationLevel(OptimizationLevel.DEFAULT);
        var thrown = assertThrows(CompilerException.class, () -> compile.apply(new JulcCompiler(null, options)));
        return thrown.diagnostics().getFirst().code();
    }

    /** Both frontends reject {@code source} alike, with {@code expected}. */
    private static void assertRule(String expected, String source) {
        assertSameRejectionAsReference(source);
        assertEquals(expected, code(compiler -> compiler.compile(source), CompilerFrontend.JAVAC));
    }

    private static void assertMethodRule(String expected, String source) {
        assertSameMethodRejectionAsReference(source, "m");
        assertEquals(expected, code(compiler -> compiler.compileMethod(source, "m"), CompilerFrontend.JAVAC));
    }

    private static void assertJavacRule(String expected, String source) {
        assertEquals(expected, code(compiler -> compiler.compile(source), CompilerFrontend.JAVAC));
    }

    // --- An entry point returns boolean ---

    @ParameterizedTest
    @ValueSource(strings = {"long", "BigInteger", "String", "void", "PlutusData"})
    void aSpendingEntryPointReturnsBoolean(String type) {
        assertRule("JULC0094", IMPORTS + """
                @SpendingValidator
                class V {
                    @Entrypoint
                    static %s validate(PlutusData redeemer, ScriptContext ctx) { %s }
                }
                """.formatted(type, body(type)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"long", "String"})
    void aSpendingEntryPointWithADatumReturnsBoolean(String type) {
        assertRule("JULC0094", IMPORTS + """
                @SpendingValidator
                class V {
                    @Entrypoint
                    static %s validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) { %s }
                }
                """.formatted(type, body(type)));
    }

    @Test
    void aMintingEntryPointReturnsBoolean() {
        assertRule("JULC0094", IMPORTS + """
                @MintingValidator
                class V {
                    @Entrypoint
                    static long validate(PlutusData redeemer, ScriptContext ctx) { return 1; }
                }
                """);
    }

    @Test
    void everyEntryPointOfAMultiValidatorReturnsBoolean() {
        assertRule("JULC0094", IMPORTS + """
                @MultiValidator
                class V {
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(PlutusData redeemer, ScriptContext ctx) { return true; }
                    @Entrypoint(purpose = Purpose.WITHDRAW)
                    static long withdraw(PlutusData redeemer, ScriptContext ctx) { return 1; }
                }
                """);
    }

    @Test
    void aMultiValidatorsOneEntryPointReturnsBoolean() {
        assertRule("JULC0094", IMPORTS + """
                @MultiValidator
                class V {
                    @Entrypoint
                    static long validate(PlutusData redeemer, ScriptContext ctx) { return 1; }
                }
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"boolean", "Boolean", "java.lang.Boolean"})
    void anEntryPointReturningBooleanCompiles(String type) {
        assertSameAsReference(IMPORTS + """
                @SpendingValidator
                class V {
                    @Entrypoint
                    static %s validate(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """.formatted(type));
    }

    /** {@code Boolean} is the JDK's by identity on javac: a user's own is another type. */
    @Test
    void aUsersBooleanIsNotABooleanResult() {
        assertJavacRule("JULC0094", IMPORTS + """
                @SpendingValidator
                class V {
                    record Boolean(long value) { }
                    @Entrypoint
                    static Boolean validate(Boolean redeemer, ScriptContext ctx) { return redeemer; }
                }
                """);
    }

    private static String body(String type) {
        return switch (type) {
            case "void" -> "return;";
            case "long" -> "return 1;";
            case "String" -> "return \"s\";";
            case "BigInteger" -> "return BigInteger.ONE;";
            default -> "return redeemer;";
        };
    }

    // --- One entry point, static ---

    @Test
    void aSinglePurposeValidatorHasOneEntryPoint() {
        assertJavacRule("JULC0096", IMPORTS + """
                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean first(PlutusData redeemer, ScriptContext ctx) { return true; }
                    @Entrypoint
                    static boolean second(PlutusData redeemer, ScriptContext ctx) { return false; }
                }
                """);
    }

    @Test
    void aProgramHasOneValidatorClass() {
        assertJavacRule("JULC0096", IMPORTS + """
                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                @MintingValidator
                class W {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """);
    }

    @Test
    void anEntryPointIsStatic() {
        assertJavacRule("JULC0097", IMPORTS + """
                @SpendingValidator
                class V {
                    @Entrypoint
                    public boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """);
    }

    // --- What a field initializer may hold ---

    @ParameterizedTest
    @ValueSource(strings = {
            "@Param static byte[] p = helper();",
            "@Param static BigInteger p = BigInteger.valueOf(1);",
            "@Param static BigInteger p = other;",
            "long instance = helper2();",
            "static final long K = 1; BigInteger instance = BigInteger.valueOf(K);",
            "BigInteger instance = new BigInteger(\"5\");",
            "long instance = 1 + 1;",
            "long instance = helper2() > 0 ? 1 : 2;"})
    void aFieldNothingLowersHoldsOnlyAConstant(String field) {
        assertMethodRule("JULC0095", IMPORTS + """
                class P {
                    static byte[] helper() { return new byte[]{1}; }
                    static long helper2() { return 1; }
                    static BigInteger other = BigInteger.ONE;
                    %s
                    static long m() { return 1; }
                }
                """.formatted(field));
    }

    /** The one array is {@code byte[]}. The reference has an older word for this; both reject it. */
    @Test
    void anArrayInAnInitializerIsNoConstant() {
        String source = """
                class P {
                    long[] instance = new long[]{1};
                    static long m() { return 1; }
                }
                """;
        assertEquals("JULC0095", code(compiler -> compiler.compileMethod(source, "m"), CompilerFrontend.JAVAC));
        assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions()).compileMethod(source, "m"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "class Nested { static long f = P.helper(); }",
            "record Nested(long a) { static long f = P.helper(); }",
            "interface Nested { long F = P.helper(); }"})
    void aFieldOfAnotherTypeHoldsOnlyAConstant(String nested) {
        assertMethodRule("JULC0095", """
                class P {
                    static long helper() { return 1; }
                    %s
                    static long m() { return 1; }
                }
                """.formatted(nested));
    }

    @Test
    void aFieldOfATopLevelRecordHoldsOnlyAConstant() {
        assertMethodRule("JULC0095", """
                record R(long a) { static long f = P.helper(); }
                class P {
                    static long helper() { return 1; }
                    static long m() { return 1; }
                }
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@Param static BigInteger p = BigInteger.ZERO;",
            "@Param static BigInteger p = BigInteger.TEN;",
            "@Param static byte[] p = new byte[]{1, -2};",
            "@Param static PlutusData p = PlutusData.UNIT;",
            "@Param static Boolean p = Boolean.valueOf(true);",
            "static final long K = 1; long instance = K;",
            "long instance = 5;",
            "long instance = -5;",
            "String instance = \"s\";",
            "final long instance = 1 + 1;",
            "static final long K = 1; final long instance = K * 2;",
            "final String instance = \"a\" + \"b\";",
            "static class Nested { static final long K = 1 + 1; }"})
    void aConstantIsWhatAFieldNothingLowersMayHold(String field) {
        assertSameMethodAsReference(IMPORTS + """
                class P {
                    %s
                    static long m() { return 1; }
                }
                """.formatted(field), "m");
    }

    /** A {@code @Param} that is a constant variable is still D14's rejection on javac, not this rule's. */
    @Test
    void aConstantParamIsReportedAsAConstantVariable() {
        String source = IMPORTS + """
                class P {
                    @Param static final long K = 5;
                    static long m() { return 1; }
                }
                """;
        assertEquals("JULC0088", code(compiler -> compiler.compileMethod(source, "m"), CompilerFrontend.JAVAC));
    }

    /** The one call is the JDK's: a user's {@code Boolean} has no {@code valueOf} this rule knows. */
    @Test
    void aUsersValueOfIsACall() {
        String source = """
                class P {
                    static class Boolean { static long valueOf(boolean b) { return 1; } }
                    long instance = Boolean.valueOf(true);
                    static long m() { return 1; }
                }
                """;
        assertEquals("JULC0095", code(compiler -> compiler.compileMethod(source, "m"), CompilerFrontend.JAVAC));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static long X; static { X = 2; }",
            "long x; { x = 2; }",
            "static long X; static { X = helper(); }"})
    void anInitializerBlockIsEmpty(String member) {
        assertMethodRule("JULC0095", """
                class P {
                    static long helper() { return 1; }
                    %s
                    static long m() { return 1; }
                }
                """.formatted(member));
    }

    /** A local type's field: a method body stops at the scan today, so the rule is asked directly, as lowering will reach it. */
    @Test
    void aFieldOfALocalTypeHoldsOnlyAConstant() {
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of("""
                class P {
                    static long helper() { return 1; }
                    static long m() {
                        class Local { static long f = helper(); }
                        return 1;
                    }
                }
                """)), List.of());
        program.read(facts -> {
            var thrown = assertThrows(CompilerException.class,
                    () -> UnloweredInitializers.check(facts, program.roots().getFirst(), field -> false));
            assertEquals("JULC0095", thrown.diagnostics().getFirst().code());
            return null;
        });
    }

    /** A library's static fields lower too, and the same rule holds for the rest. The javac frontend reads no library yet. */
    @Test
    void aLibraryFieldNothingLowersHoldsOnlyAConstant() {
        String validator = IMPORTS + """
                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """;
        String library = """
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                class L {
                    long instance = helper();
                    static long helper() { return 1; }
                }
                """;
        var thrown = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions()).compile(validator, List.of(library)));
        assertEquals("JULC0095", thrown.diagnostics().getFirst().code());
        new JulcCompiler(null, new CompilerOptions()).compile(validator, List.of(library.replace("long instance = helper();", "static long lowered = helper();")));
    }

    @Test
    void anEmptyInitializerBlockHoldsNothing() {
        assertSameMethodAsReference("class P { static { } static long m() { return 1; } }", "m");
    }

    /** A static field of the compiled class lowers, so this rule says nothing about it. */
    @Test
    void aStaticFieldOfTheCompiledClassIsLowered() {
        assertSameMethodAsReference("""
                class P {
                    static long helper() { return 1; }
                    static long K = 2;
                    static long m() { return K; }
                }
                """, "m");
    }

    // --- Varargs ---

    /** The words the reference has for a {@code byte} parameter, where it has no position to give. */
    private static void assertNoVarargs(String source) {
        var reference = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions()).compileMethod(source, "m"));
        var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);
        var javac = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options).compileMethod(source, "m"));
        assertEquals(reference.getMessage(), javac.diagnostics().getFirst().message());
    }

    @Test
    void aHelperHasNoVarargs() {
        assertNoVarargs("""
                class P {
                    static byte[] helper(byte... xs) { return xs; }
                    static long m() { return 1; }
                }
                """);
    }

    @Test
    void aCompiledMethodHasNoVarargs() {
        assertNoVarargs("class P { static byte[] m(byte... xs) { return xs; } }");
    }

    @Test
    void anArrayOfBytesIsNoVarargs() {
        assertSameMethodAsReference("class P { static byte[] m(byte[] xs) { return xs; } }", "m");
    }
}
