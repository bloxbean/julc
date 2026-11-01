package org.julclang.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.JavacGate;
import org.julclang.compiler.frontend.javac.JavacGateMode;

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
 * The subset's rules run at the gate, so every way into the compiler on the default frontend, at the default gate mode, meets
 * them. A rule that is broken in a validator, in a method's class and in a library unit is rejected at each entry.
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
        return new JulcCompiler(null, new CompilerOptions().setSourceMapEnabled(true));
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("validatorEntries")
    void aValidatorEntryMeetsEveryRule(String name, Entry entry) {
        assertRejected("JULC0094", entry, RETURNS_A_NUMBER);
        assertRejected("JULC0095", entry, HOLDS_A_DIVISION);
        assertRejected("JULC0095", entry, RUNS_A_BLOCK);
        assertRejected("JULC0098", entry, CASTS_A_NUMBER);
        for (var constructor : CONSTRUCTORS) assertRejected("JULC0099", entry, constructor.validator());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validatorEntries")
    void aValidatorEntryCompilesAnEmptyConstructor(String name, Entry entry) {
        assertDoesNotThrow(() -> entry.compile(compiler(), EMPTY.validator()));
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
    void aMethodEntryCompilesAnEmptyConstructor(String name, Entry entry) {
        assertDoesNotThrow(() -> entry.compile(compiler(), EMPTY.method()));
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
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("libraryEntries")
    void aLibraryAtEveryEntryCompilesAnEmptyConstructor(String name, Entry entry) {
        assertDoesNotThrow(() -> entry.compile(compiler(), EMPTY.library()));
    }

    private static Executable compiling(JavacGateMode mode, String source) {
        return () -> new JulcCompiler(null, new CompilerOptions().setJavacGate(mode)).compile(source);
    }

    @Test
    void theDefaultModeEnforces() {
        assertEquals(JavacGateMode.ENFORCE, JavacGate.mode(new CompilerOptions()));
    }

    @Test
    void enforceRejects() {
        assertThrows(CompilerException.class, compiling(JavacGateMode.ENFORCE, HOLDS_A_DIVISION));
    }

    /** Both modes that do not reject leave the compilation to the frontend: the gate is what holds the rules. */
    @Test
    void reportAndOffLeaveTheRulesUnenforced() {
        for (var mode : List.of(JavacGateMode.REPORT, JavacGateMode.OFF)) {
            assertDoesNotThrow(compiling(mode, HOLDS_A_DIVISION));
            assertDoesNotThrow(compiling(mode, RUNS_A_BLOCK));
            assertDoesNotThrow(compiling(mode, CONSTRUCTORS.getFirst().validator()));
        }
    }

    @Test
    void reportWritesTheRuleDown() throws IOException {
        Path reports = Files.createDirectories(dir.resolve("reports"));
        String saved = System.getProperty(JavacGate.REPORT_PROPERTY);
        System.setProperty(JavacGate.REPORT_PROPERTY, reports.toString());
        try {
            assertDoesNotThrow(compiling(JavacGateMode.REPORT, HOLDS_A_DIVISION));
        } finally {
            if (saved == null) System.clearProperty(JavacGate.REPORT_PROPERTY);
            else System.setProperty(JavacGate.REPORT_PROPERTY, saved);
        }
        try (var written = Files.walk(reports)) {
            assertEquals(true, written.filter(Files::isRegularFile).anyMatch(SubsetRulesAtEveryEntryTest::namesTheRule));
        }
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
    void theJavacFrontendAppliesTheRulesInEveryMode() {
        for (var mode : JavacGateMode.values()) {
            var options = new CompilerOptions().setJavacGate(mode).setFrontend(CompilerFrontend.JAVAC);
            var thrown = assertThrows(CompilerException.class, () -> new JulcCompiler(null, options).compile(HOLDS_A_DIVISION));
            assertEquals("JULC0095", thrown.diagnostics().getFirst().code());
            var options2 = new CompilerOptions().setJavacGate(mode).setFrontend(CompilerFrontend.JAVAC);
            var constructor = assertThrows(CompilerException.class,
                    () -> new JulcCompiler(null, options2).compile(CONSTRUCTORS.getFirst().validator()));
            assertEquals("JULC0099", constructor.diagnostics().getFirst().code());
        }
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
