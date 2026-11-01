package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.frontend.javac.subset.UnloweredInitializers;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameAsReference;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameMethodAsReference;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameMethodRejectionAsReference;
import static org.julclang.compiler.frontend.javac.lower.LoweringFixture.assertSameRejectionAsReference;

/**
 * The subset's rules for an entry point, for what a field initializer may hold and for a cast to {@code boolean}, on both
 * frontends: the same code, words and position. One rule runs on javac's trees for both, so what these tests say about one
 * frontend they say about the other. Rules only the javac frontend makes say so.
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

    /** {@code Boolean} is the JDK's by identity: a user's own is another type, however it is declared. */
    @ParameterizedTest
    @ValueSource(strings = {
            "record Boolean(long value) { }",
            "@NewType record Boolean(BigInteger value) { }",
            "class Boolean { }"})
    void aUsersBooleanIsNotABooleanResult(String declaration) {
        assertRule("JULC0094", IMPORTS + """
                @SpendingValidator
                class V {
                    %s
                    @Entrypoint
                    static Boolean validate(BigInteger redeemer, ScriptContext ctx) { return null; }
                }
                """.formatted(declaration));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@NewType record Boolean(BigInteger value) { }",
            "record Boolean(long value) { }"})
    void aTopLevelUsersBooleanIsNotABooleanResult(String declaration) {
        assertRule("JULC0094", IMPORTS + """
                %s
                @SpendingValidator
                class V {
                    @Entrypoint
                    static Boolean validate(BigInteger redeemer, ScriptContext ctx) { return null; }
                }
                """.formatted(declaration));
    }

    /** The rule reads what an annotation is called, as the compilation does: a user's own {@code @Entrypoint} is one. */
    @Test
    void anEntryPointIsKnownByItsAnnotationsName() {
        assertRule("JULC0094", """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.SpendingValidator;
                @SpendingValidator
                class V {
                    @interface Entrypoint { }
                    @Entrypoint
                    static long validate(PlutusData redeemer, ScriptContext ctx) { return 1; }
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
            "long instance = helper2() > 0 ? 1 : 2;",
            "final long instance = 1 / 0;",
            "@Param static final long p = 1 / 0;",
            "final long instance = 1L % (1L - 1L);",
            "static class Nested { static final long H = 1L % (1L - 1L); }",
            "record Nested(long a) { static final long H = 1 / 0; }",
            "BigInteger instance = null;",
            "long instance = ~1;",
            "boolean instance = !true;",
            "long instance = -helper2();",
            "int instance = Boolean.hashCode(true);",
            "boolean instance = Boolean.valueOf(helper2() > 0);",
            "boolean instance = Boolean.valueOf(\"true\");"})
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

    /** The one array is {@code byte[]}. */
    @Test
    void anArrayInAnInitializerIsNoConstant() {
        assertMethodRule("JULC0095", """
                class P {
                    long[] instance = new long[]{1};
                    static long m() { return 1; }
                }
                """);
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

    /** What stands before the dot runs unless it is a type, whatever the dot reaches. */
    @ParameterizedTest
    @ValueSource(strings = {
            "@Param static long p = ((OPEN = false) ? SELF : SELF).K;",
            "@Param static long p = SELF.x;",
            "@Param static Boolean p = ((OPEN = false) ? Boolean.TRUE : Boolean.FALSE).valueOf(true);",
            "@Param static long p = new P().K;",
            "long instance = (SELF).K;",
            "long instance = SELF.K;"})
    void aConstantIsReachedOnAType(String field) {
        assertMethodRule("JULC0095", """
                import org.julclang.stdlib.annotation.*;
                class P {
                    static boolean OPEN = true;
                    static P SELF;
                    static final long K = 5;
                    final long x = 5;
                    %s
                    static long m() { return 1; }
                }
                """.formatted(field));
    }

    /** A name is what it resolved to: a field of that name is no API type, and a user's type has no API constant. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static P BigInteger; long ZERO; @Param static long p = BigInteger.ZERO;",
            "static P PlutusData; long UNIT; @Param static long p = PlutusData.UNIT;",
            "static class BigInteger { static final Object ZERO = new Object(); } Object instance = BigInteger.ZERO;",
            "static class PlutusData { static final Object UNIT = new Object(); } Object instance = PlutusData.UNIT;"})
    void anApiConstantIsTheApis(String field) {
        assertMethodRule("JULC0095", """
                import org.julclang.stdlib.annotation.*;
                class P {
                    %s
                    static long m() { return 1; }
                }
                """.formatted(field));
    }

    /** The one call is the JDK's: a user's {@code Boolean} has no {@code valueOf} this rule knows. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static class Boolean { static long valueOf(boolean b) { return 1; } } long instance = Boolean.valueOf(true);",
            "static class Boolean { static boolean valueOf(boolean b) { return 1 / 0 == 0; } } @Param static boolean p = Boolean.valueOf(true);",
            "static class Boolean { static boolean valueOf(boolean b) { OPEN = false; return b; } } @Param static boolean p = Boolean.valueOf(true);"})
    void aUsersValueOfIsACall(String field) {
        assertMethodRule("JULC0095", """
                import org.julclang.stdlib.annotation.*;
                class P {
                    static boolean OPEN = true;
                    %s
                    static long m() { return 1; }
                }
                """.formatted(field));
    }

    @Test
    void aTopLevelUsersValueOfIsACall() {
        assertMethodRule("JULC0095", """
                import org.julclang.stdlib.annotation.*;
                class P {
                    static boolean OPEN = true;
                    @Param static boolean p = Boolean.valueOf(true);
                    static long m() { return 1; }
                }
                class Boolean {
                    static boolean valueOf(boolean b) { P.OPEN = false; return b; }
                }
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "long instance = K;",
            "long instance = P.K;",
            "long instance = (P.K);"})
    void aConstantOnATypeIsAConstant(String field) {
        assertSameMethodAsReference("""
                class P {
                    static final long K = 5;
                    %s
                    static long m() { return 1; }
                }
                """.formatted(field), "m");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Boolean.valueOf(true)", "java.lang.Boolean.valueOf(false)", "valueOf(true)"})
    void theJdksValueOfIsTheOneCall(String call) {
        assertSameMethodAsReference("""
                import static java.lang.Boolean.valueOf;
                class P {
                    boolean instance = %s;
                    static long m() { return 1; }
                }
                """.formatted(call), "m");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SpendingValidator", "MintingValidator", "MultiValidator"})
    void aParamOfAnyValidatorHoldsOnlyAConstant(String annotation) {
        assertRule("JULC0095", IMPORTS + """
                @%s
                class V {
                    @Param static final long p = 1 / 0;
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """.formatted(annotation));
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

    /** And a type declared in a lowered field's initializer. */
    @Test
    void aFieldOfAnAnonymousTypeHoldsOnlyAConstant() {
        var program = JavacProgram.analyze(List.of(JavacFrontend.Unit.of("""
                class P {
                    static long helper() { return 1; }
                    static Object lowered = new Object() { long f = helper(); };
                }
                """)), List.of());
        program.read(facts -> {
            var thrown = assertThrows(CompilerException.class, () -> UnloweredInitializers.check(facts,
                    program.roots().getFirst(), field -> field.getName().contentEquals("lowered")));
            assertEquals("JULC0095", thrown.diagnostics().getFirst().code());
            return null;
        });
    }

    private static final String LIBRARY_CALLER = IMPORTS + """
            @SpendingValidator
            class V {
                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) { return L.helper() > 0; }
            }
            """;

    private static String library(String member) {
        return """
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                class L {
                    %s
                    static long helper() { return 1; }
                }
                """.formatted(member);
    }

    /** A library's static fields lower too, and the same rule holds for the rest. */
    @ParameterizedTest
    @ValueSource(strings = {
            "long instance = helper();",
            "long instance = 1 / 0;",
            "record R(int a) { static final int H = 1 / 0; }",
            "@org.julclang.stdlib.annotation.Param static long p = helper();",
            "static { helper(); }",
            "interface I { long F = helper(); }"})
    void aLibraryFieldNothingLowersHoldsOnlyAConstant(String member) {
        var libraries = List.of(library(member));
        LoweringFixture.assertSameRejection(frontend -> new JulcCompiler(null, new CompilerOptions().setFrontend(frontend))
                .compile(LIBRARY_CALLER, libraries));
        assertEquals("JULC0095", code(compiler -> compiler.compile(LIBRARY_CALLER, libraries), CompilerFrontend.JAVAC));
        assertEquals("JULC0095", code(compiler -> compiler.compile(LIBRARY_CALLER, libraries), CompilerFrontend.JAVAPARSER));
    }

    @Test
    void aLibrarysStaticFieldLowers() {
        new JulcCompiler(null, new CompilerOptions()).compile(LIBRARY_CALLER, List.of(library("static long lowered = helper();")));
    }

    /** javac reads what the program references, and the rule reads what javac read. */
    @Test
    void aLibraryTheProgramDoesNotReadIsNotChecked() {
        String validator = LIBRARY_CALLER.replace("L.helper() > 0", "true");
        for (var level : OptimizationLevel.values()) {
            var options = new CompilerOptions().setOptimizationLevel(level);
            new JulcCompiler(null, options).compile(validator, List.of(library("long instance = helper();")));
        }
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

    // --- A cast to boolean ---

    private static String validator(String body) {
        return IMPORTS + """
                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(BigInteger r, ScriptContext ctx) { %s }
                }
                """.formatted(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "return (boolean) (Object) r;",
            "return (Boolean) (Object) r;",
            "return (boolean) ((Object) r);",
            "return (boolean) (Object) (PlutusData) (Object) r;",
            "if ((boolean) (Object) r) { return true; } return false;",
            "boolean b = (boolean) (Object) r; return b && true;",
            "return !(boolean) (Object) r;",
            "return (boolean) (Object) ((PlutusData) (Object) r);",
            "PlutusData d = (PlutusData) (Object) (boolean) (Object) r; return true;"})
    void aCastToBooleanTakesABooleanOrData(String body) {
        assertRule("JULC0098", validator(body));
    }

    @Test
    void aHelpersCastToBooleanTakesABooleanOrData() {
        assertRule("JULC0098", IMPORTS + """
                @SpendingValidator
                class V {
                    static boolean helper(BigInteger r) { return (boolean) (Object) r; }
                    @Entrypoint
                    static boolean validate(BigInteger r, ScriptContext ctx) { return helper(r); }
                }
                """);
    }

    @Test
    void aMethodsCastToBooleanTakesABooleanOrData() {
        assertMethodRule("JULC0098", """
                import java.math.BigInteger;
                class P {
                    static boolean m(BigInteger r) { return (Boolean) (Object) r; }
                }
                """);
    }

    @Test
    void aLibrarysCastToBooleanTakesABooleanOrData() {
        var library = library("static boolean flag(BigInteger r) { return (boolean) (Object) r; }")
                .replace("import ", "import java.math.BigInteger;\nimport ");
        assertEquals("JULC0098", code(compiler -> compiler.compile(LIBRARY_CALLER, List.of(library)), CompilerFrontend.JAVAPARSER));
        assertEquals("JULC0098", code(compiler -> compiler.compile(LIBRARY_CALLER, List.of(library)), CompilerFrontend.JAVAC));
    }

    /** What is allowed is left as it was: boolean stays boolean, and Data is read at the cast. */
    @ParameterizedTest
    @ValueSource(strings = {
            "return (boolean) true;",
            "return (Boolean) true;",
            "boolean flag = r.signum() > 0; return (boolean) flag;",
            "boolean flag = r.signum() > 0; return (Boolean) flag;",
            "Boolean flag = r.signum() > 0; return (boolean) flag;",
            "boolean flag = r.signum() > 0; return (boolean) (Object) flag;",
            "boolean flag = r.signum() > 0; return (Boolean) (Object) flag;",
            "boolean flag = r.signum() > 0; return (boolean) ((Object) flag);"})
    void aCastToBooleanOfABooleanCompiles(String body) {
        new JulcCompiler(null, new CompilerOptions()).compile(validator(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PlutusData", "PlutusData.ConstrData"})
    void aCastToBooleanOfDataCompiles(String type) {
        new JulcCompiler(null, new CompilerOptions()).compile(IMPORTS + """
                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(%s r, ScriptContext ctx) { return (boolean) (Object) r; }
                }
                """.formatted(type));
    }

    @Test
    void aCastToAnotherTypeIsLeftAlone() {
        new JulcCompiler(null, new CompilerOptions()).compile(validator("BigInteger same = (BigInteger) (Object) r; return same.signum() > 0;"));
    }

    /** {@code null} has no type this rule reads, and no meaning on-chain: that is its own rejection. */
    @Test
    void aCastOfNullIsNull() {
        assertEquals("JULC0017", code(compiler -> compiler.compile(validator("return (Boolean) null;")), CompilerFrontend.JAVAPARSER));
    }

    // --- A constructor holds no statement ---

    private static Stream<Arguments> constructors() {
        return Stream.of(
                Arguments.of("record R(BigInteger a) { R { a = a.abs(); } }", "R", "redeemer.a().compareTo(BigInteger.ZERO) > 0"),
                Arguments.of("record R(BigInteger a) { R { a = BigInteger.ONE; } }", "PlutusData", "new R(BigInteger.TEN).a().equals(BigInteger.ONE)"),
                Arguments.of("record R(BigInteger a) { R() { this(BigInteger.ONE); } }", "PlutusData", "new R().a().equals(BigInteger.ONE)"),
                Arguments.of("static boolean OPEN = true; record R(BigInteger a) { R { OPEN = false; } }", "R", "OPEN"),
                Arguments.of("record R(BigInteger a) { R { a = a.max(BigInteger.ONE); } }", "R", "redeemer.a().compareTo(BigInteger.ONE) < 0"));
    }

    @ParameterizedTest
    @MethodSource("constructors")
    void aValidatorsConstructorHoldsNoStatement(String member, String redeemer, String result) {
        assertRule("JULC0099", IMPORTS + """
                @SpendingValidator
                class V {
                    %s
                    @Entrypoint
                    static boolean validate(%s redeemer, ScriptContext ctx) { return %s; }
                }
                """.formatted(member, redeemer, result));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "record R(long a) { R(long a) { this.a = a; } }",
            "record R(long a) { R(long a) { this.a = a + 1; } }",
            "record R(long a) { record Q(long b) { Q { b = 1; } } }",
            "static class C { final long x; C() { x = 1; } }",
            "static class C { C() { super(); } }",
            "static class C { C(long a) { } C() { this(1); } }",
            "static class Outer { static class C { C() { long x = 1; } } }"})
    void aConstructorOfAnyKindHoldsNoStatement(String member) {
        assertMethodRule("JULC0099", """
                class P {
                    %s
                    static long m() { return 1; }
                }
                """.formatted(member));
    }

    @Test
    void aLocalTypesConstructorHoldsNoStatement() {
        assertMethodRule("JULC0099", """
                class P {
                    static long m() {
                        class C { C() { long x = 1; } }
                        return 1;
                    }
                }
                """);
    }

    @Test
    void aTopLevelTypesConstructorHoldsNoStatement() {
        assertMethodRule("JULC0099", """
                record R(long a) { R { a = 1; } }
                class P {
                    static long m() { return 1; }
                }
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "record R(long a) { R { a = 1; } }",
            "record R(long a) { R() { this(1); } }",
            "static class C { C() { long x = 1; } }"})
    void aLibrarysConstructorHoldsNoStatement(String member) {
        var libraries = List.of(library(member));
        LoweringFixture.assertSameRejection(frontend -> new JulcCompiler(null, new CompilerOptions().setFrontend(frontend))
                .compile(LIBRARY_CALLER, libraries));
        assertEquals("JULC0099", code(compiler -> compiler.compile(LIBRARY_CALLER, libraries), CompilerFrontend.JAVAPARSER));
    }

    @Test
    void theRuleNamesTheConstructor() {
        var thrown = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions()).compileMethod("""
                import java.math.BigInteger;
                class P {
                    record R(BigInteger a) { R { a = a.abs(); } }
                    static long m() { return 1; }
                }
                """, "m"));
        var diagnostic = thrown.diagnostics().getFirst();
        assertTrue(diagnostic.message().contains("R(java.math.BigInteger)"), diagnostic.message());
        assertTrue(diagnostic.message().contains("does not run on-chain"), diagnostic.message());
        assertEquals(3, diagnostic.line());
        assertEquals(30, diagnostic.column());
    }

    /** What javac wrote into a body has no source text: a {@code super();} and a compact constructor's assignments. */
    @ParameterizedTest
    @ValueSource(strings = {
            "record R(long a) { R { } }",
            "record R(long a) { R { /* nothing */ } }",
            "static class C { C() { } }",
            "static class C { private C() { } C(long a) { } }"})
    void anEmptyConstructorHoldsNothing(String member) {
        String with = "class P { %s static long m() { return 1; } }".formatted(member);
        String without = "class P { static long m() { return 1; } }";
        for (var level : OptimizationLevel.values()) {
            var options = new CompilerOptions().setOptimizationLevel(level);
            assertArrayEquals(UplcFlatEncoder.encodeProgram(new JulcCompiler(null, options).compileMethod(without, "m").program()),
                    UplcFlatEncoder.encodeProgram(new JulcCompiler(null, options).compileMethod(with, "m").program()), level.name());
        }
    }

    /** A type with no constructor of its own has one javac generated, with a body javac wrote. */
    @Test
    void aGeneratedConstructorHoldsNothing() {
        new JulcCompiler(null, new CompilerOptions()).compileMethod("""
                class P {
                    record R(long a) { }
                    static class C { }
                    static long m() { return 1; }
                }
                """, "m");
    }

    /** An API type is a class file here, so its constructors are not read: nothing of them runs on-chain either way. */
    @Test
    void anApiTypesConstructorIsNotRead() {
        new JulcCompiler(null, new CompilerOptions()).compile(IMPORTS + """
                import org.julclang.ledger.PubKeyHash;
                import org.julclang.ledger.TokenName;
                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(PubKeyHash redeemer, ScriptContext ctx) {
                        return new TokenName(redeemer.hash()).name().length > 0;
                    }
                }
                """);
    }

    /** A method's statements are its own: the rule is about constructors. */
    @Test
    void aMethodHoldsStatements() {
        new JulcCompiler(null, new CompilerOptions()).compileMethod("""
                class P {
                    static long m() {
                        long x = 1;
                        return x;
                    }
                }
                """, "m");
    }

    // --- A declaration of several variables ---

    /** Each variable has its own type: the {@code []} is on the name. */
    @ParameterizedTest
    @ValueSource(strings = {
            "final byte a = 1, b[] = new byte[]{1};",
            "static class Nested { static final byte a = 1, b[] = new byte[]{1}; }"})
    void aDeclarationOfTwoTypesHoldsOnlyConstants(String field) {
        assertSameMethodAsReference("""
                class P {
                    %s
                    static long m() { return 1; }
                }
                """.formatted(field), "m");
    }

    @Test
    void aLibrarysDeclarationOfTwoTypesHoldsOnlyConstants() {
        new JulcCompiler(null, new CompilerOptions()).compile(LIBRARY_CALLER, List.of(library("final byte a = 1, b[] = new byte[]{1};")));
    }

    /** A lowered {@code byte} has no type on-chain: that is the type's rejection, not a failure to read the declaration. */
    @ParameterizedTest
    @ValueSource(strings = {"static final byte a = 1, b[] = new byte[]{1};", "@Param static byte a = 1, b[] = new byte[]{1};"})
    void aLoweredDeclarationOfTwoTypesIsReadVariableByVariable(String field) {
        var thrown = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions()).compile(IMPORTS + """
                @SpendingValidator
                class V {
                    %s
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """.formatted(field)));
        assertTrue(thrown.getMessage().contains("byte"), thrown::getMessage);
    }

    @Test
    void aLibrarysLoweredDeclarationOfTwoTypesIsReadVariableByVariable() {
        var thrown = assertThrows(CompilerException.class, () -> new JulcCompiler(null, new CompilerOptions())
                .compile(LIBRARY_CALLER, List.of(library("static final byte a = 1, b[] = new byte[]{1};"))));
        assertTrue(thrown.getMessage().contains("byte"), thrown::getMessage);
    }

    @Test
    void theParamsHaveATypeEach() {
        var result = new JulcCompiler(null, new CompilerOptions()).compile(IMPORTS + """
                @SpendingValidator
                class V {
                    @Param static BigInteger first = BigInteger.ZERO, second = BigInteger.ONE;
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return first.compareTo(second) < 0; }
                }
                """);
        assertEquals(List.of("first", "second"), result.params().stream().map(CompileResult.ParamInfo::name).toList());
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
