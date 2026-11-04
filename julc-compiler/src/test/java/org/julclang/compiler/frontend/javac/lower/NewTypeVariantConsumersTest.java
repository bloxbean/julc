package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR-068 M5 (review H3): a sealed interface whose variant is a {@code @NewType} record or a class has no layout: both
 * registries hold such a variant as a constructor with no fields, which drops its value. {@code JULC0127} stops a
 * {@code new}, an {@code instanceof} and a {@code case} of the variant. On the javac frontend, the default from M5, no
 * other reader of the registry may take that layout either: a parameter, a datum (its boundary check and its contract
 * schema, which the blueprint is made from), a record component, a return type, and a library's exports (Elci's provider).
 * Each stops with {@code JULC0127}.
 */
class NewTypeVariantConsumersTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.NewType;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            import org.julclang.stdlib.annotation.SpendingValidator;
            """;

    /** The two variants that are not plain records: a {@code @NewType} record, and a final class. */
    private static String family(String variant) {
        return variant.equals("newtype")
                ? "sealed interface W permits V { } @NewType record V(BigInteger value) implements W { }"
                : "sealed interface W permits V { } static final class V implements W { }";
    }

    private static final CompilerOptions JAVAC = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);

    private static void assertRefused(Executable compile) {
        var e = assertThrows(CompilerException.class, compile);
        assertEquals(DiagnosticCodes.VARIANT_NOT_PLAIN_RECORD.code(), e.diagnostics().getFirst().code(), e.getMessage());
    }

    private static JulcCompiler compiler(CompilerOptions options) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
    }

    @ParameterizedTest
    @ValueSource(strings = {"newtype", "class"})
    void aParameterOfTheSum(String variant) {
        String source = IMPORTS + "class C { " + family(variant) + " static boolean m(W w) { return true; } }";
        assertRefused(() -> compiler(JAVAC).compileMethod(source, "m"));
        assertRefused(() -> compiler(new CompilerOptions()).compileMethod(source, "m"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"newtype", "class"})
    void aSumPassedThrough(String variant) {
        String source = IMPORTS + "class C { " + family(variant) + " static W m(W w) { return w; } }";
        assertRefused(() -> compiler(JAVAC).compileMethod(source, "m"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"newtype", "class"})
    void aRecordComponentOfTheSum(String variant) {
        String source = IMPORTS + "class C { " + family(variant)
                + " record R(W w, BigInteger n) { } static BigInteger m(R r) { return r.n(); } }";
        assertRefused(() -> compiler(JAVAC).compileMethod(source, "m"));
    }

    /** The datum's boundary check and the contract schema a blueprint is made from. */
    @ParameterizedTest
    @ValueSource(strings = {"newtype", "class"})
    void aDatumOfTheSum(String variant) {
        String source = IMPORTS + """
                @SpendingValidator
                class X {
                    %s
                    @Entrypoint
                    static boolean validate(W datum, PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """.formatted(family(variant));
        assertRefused(() -> compiler(JAVAC).compile(source));
        assertRefused(() -> compiler(JAVAC).compileContract(source));
        assertRefused(() -> compiler(new CompilerOptions()).compileContract(source));
    }

    /**
     * Elci's provider: a library that exports a method over the sum. A nested class in a library unit stops earlier, with
     * "Only @OnchainLibrary sources can be exported" (an uncoded IllegalArgumentException, a gap in 068-m5b.md), so the
     * {@code @NewType} variant is the case here.
     */
    @ParameterizedTest
    @ValueSource(strings = {"newtype"})
    void aLibraryExportOverTheSum(String variant) {
        String library = "package lib;\n" + IMPORTS + """
                @OnchainLibrary
                public class L {
                    %s
                    public static boolean f(W w) { return true; }
                }
                """.formatted(family(variant).replace("sealed interface", "public sealed interface")
                .replace("@NewType record", "@NewType public record").replace("static final class", "public static final class"));
        assertRefused(() -> new JavaLibraryProvider(List.of(library), StdlibRegistry.defaultRegistry(), JAVAC));
    }
}
