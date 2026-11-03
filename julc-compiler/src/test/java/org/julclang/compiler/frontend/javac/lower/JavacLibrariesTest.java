package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                class M {
                    static PlutusData g(byte[] a) { return D.id(Builtins.bData(a)); }
                }
                """;
        LoweringFixture.assertSameMethodAsReference(source, "g", List.of(library), STDLIB);
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
}
