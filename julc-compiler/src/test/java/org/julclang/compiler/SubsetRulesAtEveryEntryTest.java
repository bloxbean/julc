package org.julclang.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.JavacGate;
import org.julclang.stdlib.StdlibRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The subset's rules run at the gate, so every way into the compiler on the JavaParser frontend (selected explicitly from
 * ADR-068 M5), at the default gate mode, meets them. A rule that is broken in a validator, in a method's class and in a
 * library unit is rejected at each entry. {@link #aValidatorEntryMeetsTheRulesOnJavac} holds the same entries on javac's
 * frontend, the default, where the rules run from {@code SubsetRules.check} and the lowering.
 */
class SubsetRulesAtEveryEntryTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.*;
            """;

    private static final String RETURNS_A_NUMBER = IMPORTS + """
            @SpendingValidator
            public class V {
                @Entrypoint
                public static long validate(PlutusData redeemer, ScriptContext ctx) { return 1; }
            }
            """;

    private static final String HOLDS_A_DIVISION = IMPORTS + """
            @SpendingValidator
            public class V {
                final long instance = 1 / 0;
                @Entrypoint
                public static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
            }
            """;

    private static final String RUNS_A_BLOCK = IMPORTS + """
            @SpendingValidator
            public class V {
                long instance;
                { instance = 2; }
                @Entrypoint
                public static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
            }
            """;

    private static final String CASTS_A_NUMBER = IMPORTS + """
            @SpendingValidator
            public class V {
                @Entrypoint
                public static boolean validate(BigInteger redeemer, ScriptContext ctx) { return (boolean) (Object) redeemer; }
            }
            """;

    private record Constructor(String member, String redeemer, String result) {
        String validator() {
            return IMPORTS + """
                    @SpendingValidator
                    public class V {
                        %s
                        @Entrypoint
                        public static boolean validate(%s redeemer, ScriptContext ctx) { return %s; }
                    }
                    """.formatted(member, redeemer, result);
        }

        String method() {
            return """
                    import java.math.BigInteger;
                    import org.julclang.core.PlutusData;
                    class P {
                        %s
                        static boolean m(%s redeemer) { return %s; }
                    }
                    """.formatted(member, redeemer, result);
        }

        String library() {
            return """
                    import java.math.BigInteger;
                    import org.julclang.stdlib.annotation.OnchainLibrary;
                    @OnchainLibrary
                    public class L {
                        %s
                        public static long helper() { return 1; }
                    }
                    """.formatted(member);
        }
    }

    private static final List<Constructor> CONSTRUCTORS = List.of(
            new Constructor("record R(BigInteger a) { R { a = a.abs(); } }", "R", "redeemer.a().compareTo(BigInteger.ZERO) > 0"),
            new Constructor("record R(BigInteger a) { R { a = BigInteger.ONE; } }", "PlutusData", "new R(BigInteger.TEN).a().equals(BigInteger.ONE)"),
            new Constructor("record R(BigInteger a) { R() { this(BigInteger.ONE); } }", "PlutusData", "new R().a().equals(BigInteger.ONE)"),
            new Constructor("static boolean OPEN = true; record R(BigInteger a) { R { OPEN = false; } }", "R", "OPEN"),
            new Constructor("record R(BigInteger a) { R { a = a.max(BigInteger.ONE); } }", "R", "redeemer.a().compareTo(BigInteger.ONE) < 0"));

    /** Each is a way a value of another type could reach a boolean: a test per source, on each entry. */
    private static final List<String> CAST_BODIES = List.of(
            "return (boolean) (Object) r;",
            "PlutusData d = (PlutusData) (Object) r; return (boolean) (Object) d;",
            "return PlutusData.cast(r, Boolean.class);",
            "return PlutusData.cast(r, boolean.class);",
            "Class<Boolean> k = Boolean.class; return PlutusData.cast(r, k);",
            "return (boolean) PlutusData.cast(r, Object.class);");

    private static String casting(String body) {
        return IMPORTS + """
                @SpendingValidator
                public class V {
                    @Entrypoint
                    public static boolean validate(BigInteger r, ScriptContext ctx) { %s }
                }
                """.formatted(body);
    }

    private static final List<String> CARRIERS = List.of(
            "JulcList<BigInteger> xs = JulcList.of(r); JulcList<Boolean> l = (JulcList<Boolean>) (Object) xs; return l.head();",
            "Optional<BigInteger> o = Optional.of(r); Optional<Boolean> p = (Optional<Boolean>) (Object) o; return p.get();",
            "JulcMap<BigInteger, Boolean> n = (JulcMap<BigInteger, Boolean>) (Object) r; return n.get(r);",
            "Tuple2<BigInteger, BigInteger> t = new Tuple2<>(r, r); Tuple2<Boolean, BigInteger> u = (Tuple2<Boolean, BigInteger>) (Object) t; return u.first();",
            "F x = (F) (Object) new Q(r); return x.flag();");

    private static String carrying(String body) {
        return """
                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.core.types.Tuple2;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;
                @SpendingValidator
                public class V {
                    record Q(BigInteger a) { }
                    record F(boolean flag) { }
                    @Entrypoint
                    public static boolean validate(BigInteger r, ScriptContext ctx) { %s }
                }
                """.formatted(body);
    }

    private static String declaring(String member) {
        return IMPORTS + """
                @SpendingValidator
                public class V {
                    %s
                    @Entrypoint
                    public static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """.formatted(member);
    }

    private static final List<String> SPELLED_TYPES = List.of(
            "record Boolean(BigInteger a) { }",
            "@NewType record Boolean(BigInteger a) { }",
            "record BigInteger(long a) { static boolean valueOf(long x) { return false; } }",
            "static <Boolean> Boolean bad(long x) { return (Boolean) (Object) x; }");

    private static final List<String> SPELLED_METHODS = List.of(
            "record R(BigInteger a) { static boolean fromPlutusData(long x) { return false; } }",
            "@NewType record N(BigInteger value) { static BigInteger of(BigInteger x) { return x.add(BigInteger.ONE); } }",
            "@NewType record N(BigInteger v) { boolean abs() { return true; } }",
            "record R(BigInteger a) { public BigInteger a() { return BigInteger.ONE; } }",
            "record R(BigInteger a) { public boolean equals(Object o) { return true; } }",
            "@NewType record N(BigInteger abs) { }",
            "@NewType record H(byte[] length) { }",
            "@NewType record H(byte[] b) { }");

    private static String libraryOf(String member) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.annotation.*;
                @OnchainLibrary
                public class L {
                    %s
                    public static long helper() { return 1; }
                }
                """.formatted(member);
    }

    private static final Constructor EMPTY = new Constructor("record R(BigInteger a) { R { } }", "R", "redeemer.a().signum() > 0");

    private static final String CALLS_THE_LIBRARY = IMPORTS + """
            @SpendingValidator
            public class V {
                @Entrypoint
                public static boolean validate(PlutusData redeemer, ScriptContext ctx) { return L.helper() > 0; }
            }
            """;

    private static final String METHOD_CALLS_THE_LIBRARY = "class P { static long m() { return L.helper(); } }";

    private static final String METHOD_HOLDS_A_DIVISION = "class P { final long instance = 1 / 0; static long m() { return 1; } }";

    private static final String METHOD_CASTS_A_NUMBER = """
            import java.math.BigInteger;
            class P { static boolean m(BigInteger r) { return (boolean) (Object) r; } }
            """;

    private static final String LIBRARY = """
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary
            public class L {
                long instance = helper();
                public static long helper() { return 1; }
            }
            """;

    private static final String CASTING_LIBRARY = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary
            public class L {
                public static long helper() { return 1; }
                static boolean flag(BigInteger r) { return (boolean) (Object) r; }
            }
            """;

    @TempDir
    static Path dir;

    private interface Entry {
        Object compile(JulcCompiler compiler, String source) throws Exception;
    }

    private static JulcCompiler compiler() {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setSourceMapEnabled(true));
    }

    private static int files;

    /** A directory of its own, so that a sibling is only ever what the test put there. */
    private static Path write(String name, String source) throws IOException {
        Path file = Files.createDirectories(dir.resolve("unit" + files++)).resolve(name);
        return Files.writeString(file, source);
    }

    private static void assertRejected(String code, Entry entry, String source) {
        var thrown = assertThrows(CompilerException.class, () -> entry.compile(compiler(), source));
        assertEquals(List.of(code), thrown.diagnostics().stream().filter(CompilerDiagnostic::isError).map(CompilerDiagnostic::code).toList());
    }

    static Stream<Arguments> validatorEntries() {
        var entries = new ArrayList<Arguments>();
        entries.add(entry("compile", JulcCompiler::compile));
        entries.add(entry("compile with libraries", (compiler, source) -> compiler.compile(source, List.of())));
        entries.add(entry("compileWithDetails", JulcCompiler::compileWithDetails));
        entries.add(entry("compileWithDetails with libraries", (compiler, source) -> compiler.compileWithDetails(source, List.of())));
        entries.add(entry("compileForDebug", JulcCompiler::compileForDebug));
        entries.add(entry("compileForDebug with libraries", (compiler, source) -> compiler.compileForDebug(source, List.of())));
        entries.add(entry("compileContract", JulcCompiler::compileContract));
        entries.add(entry("compileContract with libraries", (compiler, source) -> compiler.compileContract(source, List.of())));
        entries.add(entry("compileContractWithDetails", JulcCompiler::compileContractWithDetails));
        entries.add(entry("compileContractWithDetails with libraries",
                (compiler, source) -> compiler.compileContractWithDetails(source, List.of())));
        entries.add(entry("compile(Path)", (compiler, source) -> compiler.compile(write("V.java", source))));
        entries.add(entry("compile(Path, libraries)", (compiler, source) -> compiler.compile(write("V.java", source), List.of())));
        entries.add(entry("compile(File)", (compiler, source) -> compiler.compile(write("V.java", source).toFile())));
        entries.add(entry("compileWithSiblings", (compiler, source) -> compiler.compileWithSiblings(write("V.java", source))));
        entries.add(entry("compileContract(Path)", (compiler, source) -> compiler.compileContract(write("V.java", source))));
        entries.add(entry("compileContract(Path, libraries)",
                (compiler, source) -> compiler.compileContract(write("V.java", source), List.of())));
        return entries.stream();
    }

    private static Arguments entry(String name, Entry entry) {
        return Arguments.of(name, entry);
    }

    private static final String DATUM_OF_A_SUM_WITH_A_NEWTYPE_VARIANT = IMPORTS + """
            @SpendingValidator
            public class V {
                sealed interface W permits X { }
                @NewType record X(BigInteger value) implements W { }
                @Entrypoint
                public static boolean validate(W datum, PlutusData redeemer, ScriptContext ctx) { return true; }
            }
            """;

    private static void assertRejectedOnJavac(String code, Entry entry, String source) {
        var javac = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setSourceMapEnabled(true));
        var thrown = assertThrows(CompilerException.class, () -> entry.compile(javac, source), code);
        assertEquals(List.of(code), thrown.diagnostics().stream().filter(CompilerDiagnostic::isError).map(CompilerDiagnostic::code).toList());
    }

    /**
     * The javac twin (M5 review F9): each validator entry on the default frontend meets the rules that hold on both
     * frontends, and {@code JULC0127} for a sum with no layout. The name rules ({@code JULC0100}, the gate-only
     * {@code JULC0101}) describe the JavaParser frontend and have no place here (D15).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("validatorEntries")
    void aValidatorEntryMeetsTheRulesOnJavac(String name, Entry entry) {
        assertRejectedOnJavac("JULC0094", entry, RETURNS_A_NUMBER);
        assertRejectedOnJavac("JULC0095", entry, HOLDS_A_DIVISION);
        assertRejectedOnJavac("JULC0095", entry, RUNS_A_BLOCK);
        assertRejectedOnJavac("JULC0098", entry, CASTS_A_NUMBER);
        for (var constructor : CONSTRUCTORS) assertRejectedOnJavac("JULC0099", entry, constructor.validator());
        for (var body : CAST_BODIES) assertRejectedOnJavac("JULC0098", entry, casting(body));
        assertRejectedOnJavac("JULC0127", entry, DATUM_OF_A_SUM_WITH_A_NEWTYPE_VARIANT);
    }

    /** A carrier's read of a boolean is a decode, which {@code BooleanCarriersTest} evaluates: every entry compiles it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("validatorEntries")
    void aValidatorEntryCompilesACarrier(String name, Entry entry) {
        for (var body : CARRIERS) assertDoesNotThrow(() -> entry.compile(compiler(), carrying(body)), body);
    }

    /** A type variable of a validator's own method has no type to read. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("validatorEntries")
    void aValidatorEntryDoesNotCompileAHelpersTypeVariable(String name, Entry entry) {
        assertThrows(CompilerException.class, () -> entry.compile(compiler(), declaring(
                "static <T> T f(BigInteger x) { return (T) (Object) x; } static boolean g(BigInteger r) { boolean b = f(r); return b; }")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validatorEntries")
    void aValidatorEntryRejectsAnEmptyCompactConstructor(String name, Entry entry) {
        assertRejected("JULC0080", entry, EMPTY.validator());
    }

    static Stream<Arguments> methodEntries() {
        return Stream.of(
                entry("compileMethod", (compiler, source) -> compiler.compileMethod(source, "m")),
                entry("compileMethod with libraries", (compiler, source) -> compiler.compileMethod(source, "m", List.of())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("methodEntries")
    void aMethodEntryMeetsTheRulesOnAClass(String name, Entry entry) {
        assertRejected("JULC0095", entry, METHOD_HOLDS_A_DIVISION);
        assertRejected("JULC0098", entry, METHOD_CASTS_A_NUMBER);
        for (var constructor : CONSTRUCTORS) assertRejected("JULC0099", entry, constructor.method());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("methodEntries")
    void aMethodEntryRejectsAnEmptyCompactConstructor(String name, Entry entry) {
        assertRejected("JULC0080", entry, EMPTY.method());
    }

    @Test
    void compileMethodLeavesAnEntryPointsResultToTheValidatorEntries() {
        String method = IMPORTS + """
                class P {
                    @Entrypoint
                    public static long m(PlutusData redeemer, ScriptContext ctx) { return 1; }
                }
                """;
        assertDoesNotThrow(() -> compiler().compileMethod(method, "m"));
    }

    /** What a compilation compiles is part of what the gate remembers: one source, in either order. */
    @Test
    void oneSourceIsReadAsWhatEachEntryCompiles() {
        String source = RETURNS_A_NUMBER.replace("public static long validate(PlutusData redeemer, ScriptContext ctx)", "public static long validate()");
        assertDoesNotThrow(() -> compiler().compileMethod(source, "validate"));
        assertRejected("JULC0094", JulcCompiler::compile, source);
        assertDoesNotThrow(() -> compiler().compileMethod(source, "validate"));
    }

    static Stream<Arguments> libraryEntries() {
        return Stream.of(
                entry("compile", (compiler, library) -> compiler.compile(CALLS_THE_LIBRARY, List.of(library))),
                entry("compileWithDetails", (compiler, library) -> compiler.compileWithDetails(CALLS_THE_LIBRARY, List.of(library))),
                entry("compileForDebug", (compiler, library) -> compiler.compileForDebug(CALLS_THE_LIBRARY, List.of(library))),
                entry("compileContract", (compiler, library) -> compiler.compileContract(CALLS_THE_LIBRARY, List.of(library))),
                entry("compileContractWithDetails",
                        (compiler, library) -> compiler.compileContractWithDetails(CALLS_THE_LIBRARY, List.of(library))),
                entry("compile(Path, libraries)", (compiler, library) -> compiler.compile(
                        write("V.java", CALLS_THE_LIBRARY), List.of(write("L.java", library)))),
                entry("compileContract(Path, libraries)", (compiler, library) -> compiler.compileContract(
                        write("V.java", CALLS_THE_LIBRARY), List.of(write("L.java", library)))),
                entry("compileWithSiblings", (compiler, library) -> {
                    Path validator = write("V.java", CALLS_THE_LIBRARY);
                    Files.writeString(validator.resolveSibling("L.java"), library);
                    return compiler.compileWithSiblings(validator);
                }),
                entry("compileMethod", (compiler, library) -> compiler.compileMethod(METHOD_CALLS_THE_LIBRARY, "m", List.of(library))),
                entry("JavaLibraryProvider", (compiler, library) -> new JavaLibraryProvider(List.of(library), null, new CompilerOptions())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("libraryEntries")
    void aLibraryAtEveryEntryMeetsTheRulesOnAClass(String name, Entry entry) {
        assertRejected("JULC0095", entry, LIBRARY);
        assertRejected("JULC0098", entry, CASTING_LIBRARY);
        for (var constructor : CONSTRUCTORS) assertRejected("JULC0099", entry, constructor.library());
        assertRejected("JULC0098", entry, libraryOf("static boolean flag(BigInteger r) { return PlutusData.cast(r, Boolean.class); }"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("libraryEntries")
    void aLibraryAtEveryEntryRejectsAnEmptyCompactConstructor(String name, Entry entry) {
        assertRejected("JULC0080", entry, EMPTY.library());
    }


    private static boolean namesTheRule(Path report) {
        try {
            return Files.readString(report).contains("JULC0095");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code off} holds nothing back on the default frontend, and the javac frontend has no gate to turn off. */
    @Test
    void theFrontendAppliesTheRules() {
        var thrown = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(HOLDS_A_DIVISION));
        assertEquals("JULC0095", thrown.diagnostics().getFirst().code());
        var constructor = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(CONSTRUCTORS.getFirst().validator()));
        assertEquals("JULC0099", constructor.diagnostics().getFirst().code());
    }

    @Test
    void theJavacFrontendReadsALibraryUnitByTheRulesOnAClass() {
        var options = new CompilerOptions().setFrontend(CompilerFrontend.JAVAC);
        var thrown = assertThrows(CompilerException.class, () -> new JavaLibraryProvider(List.of(LIBRARY), null, options));
        assertEquals("JULC0095", thrown.diagnostics().getFirst().code());
        var constructor = assertThrows(CompilerException.class,
                () -> new JavaLibraryProvider(List.of(CONSTRUCTORS.getFirst().library()), null, options));
        assertEquals("JULC0099", constructor.diagnostics().getFirst().code());
    }
}
