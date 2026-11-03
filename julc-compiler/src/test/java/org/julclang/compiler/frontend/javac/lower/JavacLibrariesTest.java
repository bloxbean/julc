package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D8 on {@code frontend=JAVAC}: library units lowered whole, as the reference lowers them, against the reference's
 * bytes at every level, with and without a source map, and its rejections.
 */
class JavacLibrariesTest {

    private static final StdlibRegistry STDLIB = StdlibRegistry.defaultRegistry();

    static String stdlibSource(String name) {
        try (var in = JavacLibrariesTest.class.getClassLoader()
                .getResourceAsStream("META-INF/plutus-sources/org/julclang/stdlib/lib/" + name + ".java")) {
            return new String(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String TOKEN_LIB = """
            package com.lib;

            import org.julclang.stdlib.annotation.OnchainLibrary;
            import java.math.BigInteger;

            @OnchainLibrary
            public class TokenLib {
                static final BigInteger LIMIT = BigInteger.TEN;
                static final long SCALE = 3;

                public static BigInteger scaled(BigInteger a) {
                    return a.multiply(BigInteger.valueOf(SCALE));
                }

                public static boolean small(BigInteger a) {
                    return scaled(a).compareTo(LIMIT) < 0;
                }
            }
            """;

    /** Calls TokenLib by its class name, so the reference registers {@code allowed} only after TokenLib, and {@code twice} at once. */
    private static final String RULES_LIB = """
            package com.lib;

            import org.julclang.stdlib.annotation.OnchainLibrary;
            import java.math.BigInteger;

            @OnchainLibrary
            public class RulesLib {
                public static BigInteger twice(BigInteger a) {
                    return a.add(a);
                }

                public static boolean allowed(BigInteger a, BigInteger b) {
                    return TokenLib.small(a) && TokenLib.scaled(b).signum() > 0;
                }
            }
            """;

    private static final String VALIDATOR = """
            import org.julclang.stdlib.annotation.*;
            import org.julclang.ledger.*;
            import com.lib.RulesLib;
            import com.lib.TokenLib;
            import java.math.BigInteger;

            @SpendingValidator
            class V {
                @Entrypoint
                static boolean validate(BigInteger redeemer, ScriptContext ctx) {
                    return RulesLib.allowed(redeemer, RulesLib.twice(redeemer)) && TokenLib.small(redeemer);
                }
            }
            """;

    @Test
    void aValidatorWithLibrariesIsTheReferencesProgramInEitherOrder() {
        LoweringFixture.assertSameAsReference(VALIDATOR, List.of(TOKEN_LIB, RULES_LIB), STDLIB);
        LoweringFixture.assertSameAsReference(VALIDATOR, List.of(RULES_LIB, TOKEN_LIB), STDLIB);
    }

    @Test
    void theOrderOfTheLetsIsTheReferencesRegistration() {
        var ordered = javacBytes(VALIDATOR, List.of(TOKEN_LIB, RULES_LIB));
        var inverted = javacBytes(VALIDATOR, List.of(RULES_LIB, TOKEN_LIB));
        // Not one program written two ways: the lets follow the pool's order, as the reference's do.
        assertFalse(Arrays.equals(ordered, inverted));
    }

    private static byte[] javacBytes(String source, List<String> libraries) {
        var result = LoweringFixture.validatorOn(CompilerFrontend.JAVAC, source, libraries, STDLIB, OptimizationLevel.NONE);
        return UplcFlatEncoder.encodeProgram(result.program());
    }

    @Test
    void aMethodWithTheStdlibsLibrariesIsTheReferences() {
        var source = """
                import org.julclang.stdlib.lib.MathLib;
                import org.julclang.stdlib.lib.ByteStringLib;
                import java.math.BigInteger;

                class M {
                    static BigInteger f(BigInteger a, byte[] b) {
                        return MathLib.pow(a, BigInteger.valueOf(3)).add(BigInteger.valueOf(ByteStringLib.length(b)));
                    }
                }
                """;
        LoweringFixture.assertSameMethodAsReference(source, "f",
                List.of(stdlibSource("MathLib"), stdlibSource("ByteStringLib")), STDLIB);
    }

    /**
     * D8: {@code compile(String)} and {@code compileMethod(String, String)} take the class path's library sources as their
     * pool, the reference's resolved ones first, so the bytes are the reference's.
     */
    @Test
    void theClassPathIsThePoolWhereTheCallerNamesNone() {
        var method = """
                import org.julclang.stdlib.lib.MathLib;
                import org.julclang.stdlib.lib.ByteStringLib;
                import java.math.BigInteger;

                class M {
                    static BigInteger f(BigInteger a, byte[] b) {
                        return MathLib.pow(a, BigInteger.valueOf(3)).add(BigInteger.valueOf(ByteStringLib.length(b)));
                    }
                }
                """;
        for (var level : OptimizationLevel.values()) {
            var reference = new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level)).compileMethod(method, "f");
            var javac = new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level)
                    .setFrontend(CompilerFrontend.JAVAC)).compileMethod(method, "f");
            assertArrayEquals(UplcFlatEncoder.encodeProgram(reference.program()), UplcFlatEncoder.encodeProgram(javac.program()),
                    level::toString);
        }
        var validator = VALIDATOR.replace("import com.lib.RulesLib;\n", "").replace("import com.lib.TokenLib;\n",
                "import org.julclang.stdlib.lib.MathLib;\n").replace(
                "RulesLib.allowed(redeemer, RulesLib.twice(redeemer)) && TokenLib.small(redeemer)",
                "MathLib.pow(redeemer, BigInteger.TWO).signum() >= 0");
        var reference = new JulcCompiler(STDLIB).compile(validator);
        var javac = new JulcCompiler(STDLIB, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compile(validator);
        assertArrayEquals(UplcFlatEncoder.encodeProgram(reference.program()), UplcFlatEncoder.encodeProgram(javac.program()));
    }

    @Test
    void aLibraryTheHostDidNotGiveIsNoBinder() {
        // javac reads the bundled TokenLib-free MathLib for its types; the reference has no MathLib binder without it.
        var source = """
                import org.julclang.stdlib.lib.MathLib;
                import java.math.BigInteger;

                class M {
                    static BigInteger f(BigInteger a) {
                        return MathLib.pow(a, BigInteger.valueOf(3));
                    }
                }
                """;
        LoweringFixture.assertSameMethodRejectionAsReference(source, "f", List.of(), STDLIB);
    }

    @Test
    void librariesThatCallEachOtherByClassNameAreRejectedAsTheReferenceRejectsThem() {
        var a = """
                package com.lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;
                @OnchainLibrary
                public class A {
                    public static BigInteger f(BigInteger x) { return B.g(x); }
                    public static BigInteger h(BigInteger x) { return x; }
                }
                """;
        var b = """
                package com.lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;
                @OnchainLibrary
                public class B {
                    public static BigInteger g(BigInteger x) { return A.f(x); }
                }
                """;
        var source = """
                import com.lib.A;
                import java.math.BigInteger;
                class M {
                    static BigInteger f(BigInteger a) { return A.h(a); }
                }
                """;
        LoweringFixture.assertSameMethodRejectionAsReference(source, "f", List.of(a, b), STDLIB);
    }

    @Test
    void aLibraryCallsItsOwnLaterMethodByItsName() {
        var later = """
                package com.lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;
                @OnchainLibrary
                public class L {
                    public static BigInteger f(BigInteger x) { return L.g(x); }
                    public static BigInteger g(BigInteger x) { return x.add(x); }
                }
                """;
        var earlier = later.replace("return L.g(x); }\n    public static BigInteger g(BigInteger x) { return x.add(x); }",
                "return x.add(x); }\n    public static BigInteger g(BigInteger x) { return L.f(x); }");
        var source = """
                import com.lib.L;
                import java.math.BigInteger;
                class M {
                    static BigInteger f(BigInteger a) { return L.g(a); }
                }
                """;
        LoweringFixture.assertSameMethodRejectionAsReference(source, "f", List.of(later), STDLIB);
        LoweringFixture.assertSameMethodAsReference(source, "f", List.of(earlier), STDLIB);
    }

    @Test
    void aLibraryHoldsNoValidator() {
        var library = """
                package com.lib;
                import org.julclang.stdlib.annotation.*;
                import org.julclang.ledger.*;
                import java.math.BigInteger;
                @SpendingValidator
                public class W {
                    @Entrypoint
                    public static boolean validate(BigInteger r, ScriptContext c) { return true; }
                    public static BigInteger g(BigInteger x) { return x; }
                }
                """;
        var source = VALIDATOR.replace("import com.lib.RulesLib;\n", "").replace("import com.lib.TokenLib;\n", "import com.lib.W;\n")
                .replace("RulesLib.allowed(redeemer, RulesLib.twice(redeemer)) && TokenLib.small(redeemer)", "W.g(redeemer).signum() > 0");
        LoweringFixture.assertSameRejectionAsReference(source, List.of(library), STDLIB);
    }

    @Test
    void overloadsOfOneLoweringAreOneBinderAndOthersAreRejected() {
        var same = """
                package com.lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;
                @OnchainLibrary
                public class O {
                    public static BigInteger f(BigInteger x) { return x.add(x); }
                    public static BigInteger f(long x) { return BigInteger.valueOf(x).add(BigInteger.valueOf(x)); }
                }
                """;
        var source = """
                import com.lib.O;
                import java.math.BigInteger;
                class M {
                    static BigInteger f(BigInteger a) { return O.f(a); }
                }
                """;
        LoweringFixture.assertSameMethodAsReference(source, "f", List.of(same), STDLIB);
        var different = same.replace("return BigInteger.valueOf(x).add(BigInteger.valueOf(x));", "return BigInteger.valueOf(x);");
        LoweringFixture.assertSameMethodRejectionAsReference(source, "f", List.of(different), STDLIB);
    }

    @Test
    void aLibraryCompiledAsTheRootAndGivenInThePoolIsTheReferences() {
        var library = """
                package com.lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;
                @OnchainLibrary
                public class R {
                    public static BigInteger g(BigInteger x) { return x.add(x); }
                    public static BigInteger f(BigInteger x) { return R.g(x).add(x); }
                    static class Inner {
                        static BigInteger h(BigInteger x) { return R.g(x).multiply(x); }
                    }
                }
                """;
        LoweringFixture.assertSameMethodAsReference(library, "f", List.of(library), STDLIB);
        // Not given in the pool, it has no library binder: the stdlib lookup is all there is.
        var alone = library.substring(0, library.indexOf("    static class Inner")) + "}\n";
        LoweringFixture.assertSameMethodRejectionAsReference(alone, "f", List.of(), STDLIB);
    }

    @Test
    void aPrimitivePassedWhereALibraryOfTheUnnamedPackageTakesDataIsWarnedOf() {
        var library = """
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import org.julclang.core.PlutusData;
                import java.math.BigInteger;
                @OnchainLibrary
                public class D {
                    public static PlutusData id(PlutusData x) { return x; }
                }
                """;
        var source = """
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.Builtins;
                import java.math.BigInteger;
                class M {
                    static PlutusData g(byte[] a) { return D.id(Builtins.bData(a)); }
                    static PlutusData f(BigInteger a) { return D.id(PlutusData.cast(a, PlutusData.class)); }
                }
                """;
        LoweringFixture.assertSameMethodAsReference(source, "g", List.of(library), STDLIB);
        var warned = new JulcCompiler(STDLIB).compileMethod(source, "f", List.of(library)).diagnostics();
        assertTrue(warned.stream().anyMatch(d -> d.message().contains("expects Data")), warned::toString);
        LoweringFixture.assertSameMethodAsReference(source, "f", List.of(library), STDLIB);
    }

    /** The reference looks the method up by the class name as written: a packaged library is never found, so never warned of. */
    @Test
    void aPackagedLibraryIsNotWarnedOf() {
        var library = """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import org.julclang.core.PlutusData;
                @OnchainLibrary
                public class D {
                    public static PlutusData id(PlutusData x) { return x; }
                }
                """;
        var source = """
                import lib.D;
                import org.julclang.core.PlutusData;
                import java.math.BigInteger;
                class M {
                    static PlutusData f(BigInteger a) { return D.id(PlutusData.cast(a, PlutusData.class)); }
                }
                """;
        assertTrue(new JulcCompiler(STDLIB).compileMethod(source, "f", List.of(library)).diagnostics().isEmpty());
        LoweringFixture.assertSameMethodAsReference(source, "f", List.of(library), STDLIB);
    }

    /** In a library the reference's generator holds the warning with its errors, so the library is rejected. */
    @Test
    void aWarningInALibraryRejectsIt() {
        var callee = """
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import org.julclang.core.PlutusData;
                @OnchainLibrary
                public class D {
                    public static PlutusData id(PlutusData x) { return x; }
                }
                """;
        var caller = """
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import org.julclang.core.PlutusData;
                import java.math.BigInteger;
                @OnchainLibrary
                public class E {
                    public static PlutusData f(BigInteger a) { return D.id(PlutusData.cast(a, PlutusData.class)); }
                }
                """;
        var source = """
                import org.julclang.core.PlutusData;
                import java.math.BigInteger;
                class M {
                    static PlutusData g(BigInteger a) { return E.f(a); }
                }
                """;
        for (var frontend : List.of(CompilerFrontend.JAVAPARSER, CompilerFrontend.JAVAC)) {
            var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(STDLIB,
                    new CompilerOptions().setFrontend(frontend)).compileMethod(source, "g", List.of(callee, caller)));
            assertTrue(failure.getMessage().contains("expects Data"), failure::getMessage);
        }
    }

    /** A loop's rest rebinds the class's own static methods, as the reference's symbol table holds them; a library's are not the root's. */
    @Test
    void aLoopRebindsOnlyItsOwnClassesMethods() {
        var source = """
                import com.lib.TokenLib;
                import org.julclang.core.types.JulcList;
                import java.math.BigInteger;
                class M {
                    static BigInteger m(JulcList<BigInteger> xs) {
                        BigInteger s = BigInteger.ZERO;
                        for (var x : xs) { s = s.add(x); }
                        return TokenLib.scaled(s);
                    }
                }
                """;
        LoweringFixture.assertSameMethodAsReference(source, "m", List.of(TOKEN_LIB), STDLIB);
    }

    @Test
    void aLibrarysStaticFieldHoldsOnlyAConstant() {
        var library = """
                package com.lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.math.BigInteger;
                @OnchainLibrary
                public class F {
                    static final BigInteger SEVEN = BigInteger.valueOf(7);
                    public static BigInteger f(BigInteger x) { return x.add(SEVEN); }
                }
                """;
        var source = """
                import com.lib.F;
                import java.math.BigInteger;
                class M {
                    static BigInteger f(BigInteger a) { return F.f(a); }
                }
                """;
        var off = new CompilerOptions().setJavacGate(JavacGateMode.OFF);
        assertFalse(new JulcCompiler(STDLIB, off).compileMethod(source, "f", List.of(library)).hasErrors());
        var javac = assertThrows(CompilerException.class, () -> new JulcCompiler(STDLIB,
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source, "f", List.of(library)));
        assertEquals("JULC0095", javac.diagnostics().getFirst().code(), javac::getMessage);
        assertTrue(javac.getMessage().contains("SEVEN"), javac::getMessage);
        LoweringFixture.assertSameMethodAsReference(source, "f", List.of(library.replace("BigInteger.valueOf(7)", "BigInteger.TEN")), STDLIB);
    }

    private static String library(String name, String body) {
        return "package com.lib;\nimport java.math.BigInteger;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n"
                + "@OnchainLibrary\npublic class " + name + " {\n" + body + "}\n";
    }

    private static final String CALLER_OF_A = """
            import com.lib.A;
            import java.math.BigInteger;
            class M { static BigInteger f(BigInteger a) { return A.a1(a); } }
            """;

    /** The reference's final pass can complete a unit that the last pass left half registered (review F1). */
    @Test
    void theFinalPassCompletesWhatTheLastPassLeft() {
        var a = library("A", "public static BigInteger a1(BigInteger x) { return B.b1(x); }\n"
                + "public static BigInteger a2(BigInteger x) { return x.add(BigInteger.ONE); }\n");
        var b = library("B", "public static BigInteger b1(BigInteger x) { return x.add(x); }\n"
                + "public static BigInteger b2(BigInteger x) { return A.a2(x); }\n");
        LoweringFixture.assertSameMethodAsReference(CALLER_OF_A, "f", List.of(a, b), STDLIB);
        LoweringFixture.assertSameMethodAsReference(CALLER_OF_A, "f", List.of(b, a), STDLIB);
    }

    /**
     * A library root is compiled as a library too only where the reference's resolution compiles it (review F2):
     * {@code ByteStringLib} calls itself by simple name, so the class path's resolution leaves it out, and the script is the
     * reference's at every level.
     */
    @Test
    void aLibraryRootIsALibraryOnlyWhereTheReferenceResolvesIt() {
        var source = stdlibSource("ByteStringLib");
        for (var level : OptimizationLevel.values()) {
            var reference = new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "length");
            var javac = new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level)
                    .setFrontend(CompilerFrontend.JAVAC)).compileMethod(source, "length");
            assertArrayEquals(UplcFlatEncoder.encodeProgram(reference.program()), UplcFlatEncoder.encodeProgram(javac.program()),
                    level::toString);
        }
    }

    /**
     * Two binders of one name, a root's helper and its library copy, where the copy is delayed for a field that may fail: a
     * read of the helper is the helper's, so it is not called with unit (review F2, shared code: DelayedMethodPass).
     */
    @Test
    void aDelayedCopyDoesNotDelayTheHelperOfItsName() {
        var root = library("H", "static final BigInteger HALF = BigInteger.valueOf(100).divide(BigInteger.TWO);\n"
                + "public static BigInteger g(BigInteger x) { return x.add(x); }\n"
                + "public static BigInteger f(BigInteger x) { return g(x).add(HALF); }\n");
        var off = new CompilerOptions().setJavacGate(JavacGateMode.OFF);
        for (var frontend : List.of(CompilerFrontend.JAVAPARSER, CompilerFrontend.JAVAC)) {
            for (var level : OptimizationLevel.values()) {
                var program = new JulcCompiler(STDLIB, new CompilerOptions().setJavacGate(JavacGateMode.OFF).setOptimizationLevel(level)
                        .setFrontend(frontend)).compileMethod(root, "f", List.of(root)).program();
                var result = JulcVm.create().evaluateWithArgs(program, List.of(PlutusData.integer(4)));
                assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(58))),
                        assertInstanceOf(EvalResult.Success.class, result, () -> frontend + " " + level).resultTerm(),
                        () -> frontend + " " + level);
            }
        }
        assertFalse(new JulcCompiler(STDLIB, off).compileMethod(root, "f", List.of(root)).hasErrors());
        LoweringFixture.assertSameMethodAsReference(root, "f", List.of(root), STDLIB);
    }

    /** A concrete method that shares its name with a template is the template's name to the reference (review F3). */
    @Test
    void aNameAGenericOverloadSharesIsATemplates() {
        var g = library("G", "public static BigInteger f(BigInteger x) { return x.add(x); }\n"
                + "public static <T> T f(T x, BigInteger y) { return x; }\n");
        var caller = "import com.lib.G;\nimport java.math.BigInteger;\nclass M { static BigInteger f(BigInteger a) { return G.f(a); } }\n";
        var reference = assertThrows(CompilerException.class, () -> new JulcCompiler(STDLIB).compileMethod(caller, "f", List.of(g)));
        var javac = assertThrows(CompilerException.class, () -> new JulcCompiler(STDLIB,
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(caller, "f", List.of(g)));
        // The reference throws the message alone; javac's has the call's position too.
        assertTrue(javac.getMessage().contains(reference.getMessage()), javac::getMessage);
        var simple = library("G", "static <T> T id(T x) { return x; }\npublic static BigInteger f(BigInteger x) { return id(x); }\n");
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(STDLIB,
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(caller, "f", List.of(simple)));
        assertTrue(failure.getMessage().contains("Unknown method: id"), failure::getMessage);
    }

    /** A root's field may call a library: the reference registers its class only once the callee is (review F4). */
    @Test
    void aRootsFieldWaitsForTheLibraryItCalls() {
        var r = library("R", "static final BigInteger K = O.f(BigInteger.ONE);\n"
                + "public static BigInteger g(BigInteger x) { return x.add(BigInteger.ONE); }\n"
                + "public static BigInteger f(BigInteger x) { return x.add(K).add(O.z(x)); }\n");
        var o = library("O", "public static BigInteger f(BigInteger y) { return y.add(BigInteger.TEN); }\n"
                + "public static BigInteger z(BigInteger y) { return y; }\n");
        var off = new CompilerOptions().setJavacGate(JavacGateMode.OFF);
        for (var level : OptimizationLevel.values()) {
            var reference = new JulcCompiler(STDLIB, new CompilerOptions().setJavacGate(JavacGateMode.OFF).setOptimizationLevel(level))
                    .compileMethod(r, "f", List.of(r, o));
            var javac = new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level).setFrontend(CompilerFrontend.JAVAC))
                    .compileMethod(r, "f", List.of(r, o));
            assertArrayEquals(UplcFlatEncoder.encodeProgram(reference.program()), UplcFlatEncoder.encodeProgram(javac.program()), level::toString);
        }
        var cycle = o.replace("return y.add(BigInteger.TEN);", "return R.g(y);");
        var expected = assertThrows(CompilerException.class, () -> new JulcCompiler(STDLIB, off).compileMethod(r, "f", List.of(r, cycle)));
        var actual = assertThrows(CompilerException.class, () -> new JulcCompiler(STDLIB,
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(r, "f", List.of(r, cycle)));
        assertEquals(expected.getMessage(), actual.getMessage());
    }

    /** An interface's or a record's static method is no library method: the reference's Undefined variable (review F6). */
    @Test
    void anInterfacesStaticMethodIsNoLibraryMethod() {
        var iface = "package com.lib;\nimport java.math.BigInteger;\n"
                + "public interface I { static BigInteger f(BigInteger x) { return x.add(x); } }";
        var caller = "import com.lib.I;\nimport java.math.BigInteger;\nclass M { static BigInteger f(BigInteger a) { return I.f(a); } }";
        LoweringFixture.assertSameMethodRejectionAsReference(caller, "f", List.of(iface), STDLIB);
    }
}
