package org.julclang.compiler.error;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** What a developer reads names their code. It never names a design document, a review or a compiler's internal type. */
class DiagnosticTextTest {

    private static final Pattern INTERNAL = Pattern.compile(
            "ADR-?\\s?\\d|\\bM\\d[a-z]\\b|\\bF\\d{3,}\\b|JavaParser|\\b[A-Z][A-Za-z]*(?:Expr|Stmt)\\b|#\\d{3}\\b");
    /** What no string may hold: a design document, a finding or an issue number, or the old parser's node types. */
    private static final Pattern DOCUMENT = Pattern.compile(
            "ADR-?\\s?\\d|\\bF\\d{3,}\\b|JavaParser|\\b[A-Z][A-Za-z]*(?:Expr|Stmt)\\b|\\(#\\d{3}\\)|\\([TDI]\\d+[a-z]?[,)]|#\\d{3}\\b");

    @Test
    void theCatalogueNamesNoInternalDocumentOrType() throws IOException {
        var problems = new ArrayList<String>();
        String code = "";
        var field = Pattern.compile("^\\s*\"(code|title|template|summary|fix)\": \"(.*)\",?$");
        for (var line : Files.readAllLines(Path.of("src/main/resources/diagnostics.json"))) {
            var matcher = field.matcher(line);
            if (!matcher.find()) continue;
            if (matcher.group(1).equals("code")) code = matcher.group(2);
            else if (INTERNAL.matcher(matcher.group(2)).find()) problems.add(code + " " + matcher.group(1) + ": " + matcher.group(2));
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void noStringInTheCompilerNamesAnInternalDocument() throws IOException {
        var problems = new ArrayList<String>();
        for (String root : List.of("src/main/java", "../julc-cli/src/main/java", "../julc-gradle-plugin/src/main/java",
                "../julc-annotation-processor/src/main/java", "../julc-testkit/src/main/java")) {
            try (var files = Files.walk(Path.of(root))) {
                // That generator writes Lean sources for the proofs, with comments of their own; none of it is an error.
                for (var file : files.filter(f -> f.toString().endsWith(".java") && !f.endsWith("VerificationProjectGenerator.java")).toList()) {
                    for (var text : stringsOf(Files.readString(file))) {
                        if (DOCUMENT.matcher(text).find()) problems.add(file + ": " + text);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** A positive control: the scan reads through a split string and a text block, and not a comment. */
    @Test
    void theScanFindsAReferenceHoweverItIsWritten() {
        var source = "class C {\n"
                + "  // ADR-068 in a comment\n"
                + "  /* ADR-068 in a comment */\n"
                + "  String a = \"see (ADR-\" + \"068 D3)\";\n"
                + "  String b = \"\"\"\n     see ADR-067\n  \"\"\";\n"
                + "  String c = \"(T6, I2a) and #288\";\n"
                + "  String d = \"a \\\" quote\";\n"
                + "}\n";
        var found = stringsOf(source).stream().filter(text -> DOCUMENT.matcher(text).find()).toList();
        assertEquals(3, found.size(), found.toString());
    }

    /** The text of each string in {@code source}: literals joined by {@code +} are one string, and comments are skipped. */
    private static List<String> stringsOf(String source) {
        var strings = new ArrayList<String>();
        var current = new StringBuilder();
        boolean open = false;
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                i = source.indexOf('\n', i);
                if (i < 0) break;
            } else if (source.startsWith("/*", i)) {
                i = source.indexOf("*/", i + 2) + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                current.append(source, i + 3, end);
                open = true;
                i = end + 3;
            } else if (source.charAt(i) == '\'') {
                i += source.charAt(i + 1) == '\\' ? 4 : 3;
            } else if (source.charAt(i) == '"') {
                int end = i + 1;
                while (source.charAt(end) != '"') end += source.charAt(end) == '\\' ? 2 : 1;
                current.append(source, i + 1, end);
                open = true;
                i = end + 1;
            } else {
                char c = source.charAt(i);
                if (open && c != '+' && !Character.isWhitespace(c)) {
                    strings.add(current.toString());
                    current.setLength(0);
                    open = false;
                }
                i++;
            }
        }
        if (open) strings.add(current.toString());
        return strings;
    }

    @Test
    void anErrorShowsItsSourceLine() {
        var source = """
                import java.math.BigInteger;
                public class Probe {
                    static BigInteger m(BigInteger n) {
                        n = n.add(n);
                        return n;
                    }
                }
                """;
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compileMethod(source, "m"));
        var diagnostic = error.diagnostics().getFirst();
        assertEquals("""
                4 |         n = n.add(n);
                  |         ^""", diagnostic.excerpt());
        assertTrue(error.describe().contains("n = n.add(n);"), error.describe());
    }

    @Test
    void javacsOwnErrorShowsItsSourceLineToo() {
        var source = """
                public class Probe {
                    static int m() { return missing; }
                }
                """;
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compileMethod(source, "m"));
        var diagnostic = error.diagnostics().getFirst();
        assertEquals("JULC0085", diagnostic.code());
        assertTrue(diagnostic.excerpt().contains("return missing;"), String.valueOf(diagnostic.excerpt()));
        assertTrue(diagnostic.excerpt().contains("^^^^^^^"), diagnostic.excerpt());
    }

    @Test
    void aMissingPackageSaysHowToMakeALibraryVisible() {
        var source = """
                import com.example.lib.MathLib;
                public class Probe { static int m() { return 1; } }
                """;
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compileMethod(source, "m"));
        assertTrue(error.diagnostics().getFirst().suggestion().contains("annotation processor path"), error.describe());
    }

    // ---- The excerpt shows the source the diagnostic is about

    private static final String LIBRARY = """
            package other;
            public class Lib {
                public static long f() {
                    long unused = 0;
                    return missing;
                }
            }
            """;

    private static CompilerDiagnostic libraryError(String root, String... libraries) {
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions())
                .compileMethod(root, "m", List.of(libraries)));
        return error.diagnostics().getFirst();
    }

    @Test
    void aLibrarysErrorShowsTheLibraryEvenWhenTheRootMentionsItsName() {
        var root = """
                // class Lib
                public class Probe {
                    static String s = "record Lib";
                    record Lib(long value) {}
                    static long g() {
                        long nothing = 0;
                        return other.Lib.f();
                    }
                    static long m() { return 1; }
                }
                """;
        var diagnostic = libraryError(root, LIBRARY);
        assertEquals("Lib.java", diagnostic.fileName());
        assertTrue(diagnostic.excerpt().contains("return missing;"), diagnostic.excerpt());
    }

    @Test
    void twoSourcesWithOneNameShowNoLineRatherThanAWrongOne() {
        var root = """
                public class Probe {
                    static long m() { return other.Lib.f() + third.Lib.f(); }
                }
                """;
        var second = LIBRARY.replace("package other;", "package third;").replace("return missing;", "return 1;");
        var diagnostic = libraryError(root, LIBRARY, second);
        assertEquals("Lib.java", diagnostic.fileName());
        assertNull(diagnostic.excerpt());
    }

    @Test
    void aFileShowsItsLineByItsFullPathAndItsShortName(@TempDir Path directory) throws IOException {
        var javacError = directory.resolve("Bad.java");
        Files.writeString(javacError, "class Bad {\n    static long m() { return missing; }\n}\n");
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compile(javacError));
        assertTrue(error.diagnostics().getFirst().excerpt().contains("return missing;"), error.describe());
    }

    @Test
    void aRelativePathWithDirectoriesShowsItsLine() throws IOException {
        var directory = Files.createDirectories(Path.of("build", "tmp", "relative-excerpt", "src"));
        var file = directory.resolve("Bad.java");
        Files.writeString(file, "class Bad {\n    static long m() { return missing; }\n}\n");
        assertFalse(file.isAbsolute());
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compile(file));
        assertTrue(error.diagnostics().getFirst().excerpt().contains("return missing;"), error.describe());
    }

    @Test
    void aProblemWithAWholeFileShowsNoLine() {
        var diagnostic = libraryError("interface OnlyAnInterface { }\n");
        assertEquals("JULC0133", diagnostic.code());
        assertNull(diagnostic.excerpt());
        assertEquals("<source>", diagnostic.fileName());
    }

    // ---- JULC0131 to JULC0138

    private static final String VALIDATOR_IMPORTS = """
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.*;
            """;
    private static final String ENTRY = "(PlutusData r, ScriptContext c) { return true; }";

    private static CompilerDiagnostic validatorError(String body) {
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions())
                .compile(VALIDATOR_IMPORTS + body));
        return error.diagnostics().getFirst();
    }

    private static void reports(CompilerDiagnostic diagnostic, String code, String shown) {
        assertEquals(code, diagnostic.code(), diagnostic.toString());
        assertEquals("V.java", diagnostic.fileName(), diagnostic.toString());
        assertNotNull(diagnostic.suggestion(), diagnostic.toString());
        assertTrue(diagnostic.excerpt().contains(shown), diagnostic.toString());
    }

    @Test
    void aClassLiteralIsRequiredForACast() {
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions())
                .compileMethod(VALIDATOR_IMPORTS + "class V { static PlutusData m(PlutusData d) { return PlutusData.cast(d, d.getClass()); } }", "m"));
        reports(error.diagnostics().getFirst(), "JULC0131", "PlutusData.cast");
    }

    @Test
    void aCaseBlockHasToYieldOnEveryPath() {
        var source = """
                import java.math.BigInteger;
                class V {
                    sealed interface S permits A, B {}
                    record A() implements S {}
                    record B() implements S {}
                    static long m(S s) {
                        return switch (s) {
                            case A a -> { { yield 1; } }
                            default -> 2;
                        };
                    }
                }
                """;
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions()).compileMethod(source, "m"));
        reports(error.diagnostics().getFirst(), "JULC0132", "yield 1");
    }

    @Test
    void aClassIsALibraryOrAValidator() {
        reports(validatorError("@OnchainLibrary @SpendingValidator\nclass V { @Entrypoint static boolean m" + ENTRY + " }"), "JULC0134", "@OnchainLibrary");
    }

    @Test
    void aClassIsALibraryOrAValidatorEvenWhenMarkedThroughAMetaAnnotation() {
        // ADR-071 D1: @Marked implies @OnchainLibrary, so combining it with a validator annotation is JULC0134 too.
        String marker = """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                import java.lang.annotation.*;
                @OnchainLibrary @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
                public @interface Marked {}
                """;
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions())
                .compile(VALIDATOR_IMPORTS + "import lib.Marked;\n@Marked @SpendingValidator\nclass V { @Entrypoint static boolean m" + ENTRY + " }",
                        java.util.List.of(marker)));
        assertEquals("JULC0134", error.diagnostics().getFirst().code(), error.diagnostics().toString());
    }

    @Test
    void aClassHasOneValidatorAnnotation() {
        var diagnostic = validatorError("@SpendingValidator @MintingValidator\nclass V { @Entrypoint static boolean m" + ENTRY + " }");
        reports(diagnostic, "JULC0135", "@SpendingValidator");
        assertTrue(diagnostic.message().contains("@SpendingValidator, @MintingValidator"), diagnostic.message());
    }

    @Test
    void aMultiValidatorDoesNotMixDispatch() {
        reports(validatorError("@MultiValidator\nclass V {\n  @Entrypoint static boolean a" + ENTRY
                + "\n  @Entrypoint(purpose = Purpose.MINT) static boolean b" + ENTRY + "\n}"), "JULC0136", "static boolean");
    }

    @Test
    void aMultiValidatorHasOneDefaultEntryPoint() {
        reports(validatorError("@MultiValidator\nclass V {\n  @Entrypoint static boolean a" + ENTRY
                + "\n  @Entrypoint static boolean b" + ENTRY + "\n}"), "JULC0137", "boolean b");
    }

    @Test
    void aMultiValidatorHasOneEntryPointPerPurpose() {
        reports(validatorError("@MultiValidator\nclass V {\n  @Entrypoint(purpose = Purpose.MINT) static boolean a" + ENTRY
                + "\n  @Entrypoint(purpose = Purpose.MINT) static boolean b" + ENTRY + "\n}"), "JULC0138", "boolean b");
    }
}
