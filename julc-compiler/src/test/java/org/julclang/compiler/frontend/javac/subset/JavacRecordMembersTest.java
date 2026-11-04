package org.julclang.compiler.frontend.javac.subset;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SpelledMethods.checkRecordMembers} at each way into {@code frontend=JAVAC}: a {@code toPlutusData} that a component
 * generates has no tree, and is {@code JULC0101} where the record is declared. The programs are in {@code Adr068ReadsHarnessTest},
 * with the JVM's answer.
 */
class JavacRecordMembersTest {

    private static final String DATA = "record D(PlutusData toPlutusData) implements PlutusDataConvertible { }";

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.ledger.PlutusDataConvertible;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.*;
            """;

    private static CompilerOptions javac() {
        return new CompilerOptions();
    }

    private static JulcCompiler compiler() {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), javac());
    }

    private static void assertRejected(String words, Executable compilation) {
        var failure = assertThrows(CompilerException.class, compilation);
        assertEquals("JULC0101", failure.diagnostics().getFirst().code(), failure::getMessage);
        assertTrue(failure.getMessage().contains(words), failure::getMessage);
    }

    @Test
    void aValidatorsDatum() {
        assertRejected("D.toPlutusData", () -> compiler().compile(IMPORTS + """
                @SpendingValidator
                public class V {
                    %s
                    @Entrypoint
                    public static boolean validate(D d, PlutusData r, ScriptContext c) { return true; }
                }
                """.formatted(DATA)));
    }

    @Test
    void aMethodsRecordThatIsNotRead() {
        assertRejected("D.toPlutusData", () -> compiler().compileMethod(IMPORTS + """
                class P {
                    %s
                    static long m() { return 1; }
                }
                """.formatted(DATA), "m"));
    }

    @Test
    void aSecondTypeOfTheUnit() {
        assertRejected("D.toPlutusData", () -> compiler().compileMethod(IMPORTS + """
                class P {
                    static long m() { return 1; }
                }
                %s
                """.formatted(DATA), "m"));
    }

    private static final String LIBRARY = """
            package demo;

            import org.julclang.core.PlutusData;
            import org.julclang.ledger.PlutusDataConvertible;
            import org.julclang.stdlib.annotation.OnchainLibrary;

            @OnchainLibrary
            public class Lib {
                public record D(PlutusData toPlutusData) implements PlutusDataConvertible { }
                public static long f() { return 1; }
            }
            """;

    @Test
    void aLibraryAProgramReads() {
        var root = """
                import demo.Lib;
                class P { static long m() { return Lib.f(); } }
                """;
        assertRejected("D.toPlutusData", () -> compiler().compileMethod(root, "m", List.of(LIBRARY)));
    }

    @Test
    void aProvidersSource() {
        assertRejected("D.toPlutusData", () -> new JavaLibraryProvider(List.of(LIBRARY), StdlibRegistry.defaultRegistry(), javac()));
    }

    /**
     * The one default of {@code toPlutusData} in a class file is {@code JulcList}'s, whose {@code Iterable} javac cannot read
     * from the platform image. Any other default is a method tree, which {@code checkDeclarations} reads. So no record inherits one.
     */
    @Test
    void aRecordOfTheOneDefaultInAClassFileIsRefusedFirst() {
        var methods = Stream.of("T head()", "T get(long i)", "JulcList<T> tail()", "JulcList<T> take(long n)", "JulcList<T> drop(long n)",
                        "JulcList<T> prepend(T e)", "JulcList<T> concat(JulcList<T> o)", "JulcList<T> reverse()", "long size()", "boolean isEmpty()",
                        "boolean contains(T e)", "<R> JulcList<R> map(java.util.function.Function<T, R> f)",
                        "JulcList<T> filter(java.util.function.Predicate<T> p)", "boolean any(java.util.function.Predicate<T> p)",
                        "boolean all(java.util.function.Predicate<T> p)", "T find(java.util.function.Predicate<T> p)",
                        "java.util.Iterator<T> iterator()")
                .map(method -> "public " + method + " { throw new UnsupportedOperationException(); }")
                .collect(Collectors.joining(" "));
        var failure = assertThrows(CompilerException.class, () -> compiler().compileMethod(IMPORTS + """
                class P {
                    record L<T>(BigInteger a) implements JulcList<T> { %s }
                    static long m() { return 1; }
                }
                """.formatted(methods), "m"));
        assertEquals("JULC0085", failure.diagnostics().getFirst().code(), failure::getMessage);
    }
}
