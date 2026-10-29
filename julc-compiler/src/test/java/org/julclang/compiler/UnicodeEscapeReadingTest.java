package org.julclang.compiler;

import com.github.javaparser.Position;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.debug.DebugMetadata;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-062 T24: JuLC reads Java source as javac does, Unicode escapes included (JLS 21 §3.3). javac
 * translates an escape before it reads anything else, so in a comment it can be a line terminator or the
 * comment's end, and in a name it is the character it denotes. JuLC's parsers used to leave escapes
 * untranslated, so the script and the classes its tests run could be different programs. javac is the
 * oracle: each source runs on the JVM and on UPLC at every optimization level.
 * <p>
 * The escapes in the sources below are written with a doubled backslash, so that javac does not
 * translate them while compiling this test.
 */
class UnicodeEscapeReadingTest {

    /** Each source declares {@code static BigInteger m(BigInteger a)}. */
    private static final List<String[]> SOURCES = List.of(
            new String[]{"an escaped line terminator in a line comment", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a // \\u000a .add(a)
                        ;
                    } }
                    """},
            new String[]{"an escaped comment end", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a /* \\u002a/ .add(a) /* */ ;
                    } }
                    """},
            new String[]{"an escaped name for a local that shadows a field", """
                    import java.math.BigInteger;
                    public class P {
                        static final BigInteger a = BigInteger.ONE;
                        public static BigInteger m(BigInteger x) {
                            BigInteger \\u0061 = x;
                            return a;
                        }
                    }
                    """},
            new String[]{"an escaped parameter name used plainly", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger \\u0061) {
                        return a.add(a);
                    } }
                    """},
            new String[]{"escaped keywords and operators", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        \\u0072eturn a.add(BigInteger.TWO)\\u003b
                    } }
                    """},
            new String[]{"an escape in a string literal that is a comment opener", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return BigInteger.valueOf("\\u002f\\u002f".length()).add(a);
                    } }
                    """},
            new String[]{"an escaped backslash pair before an escaped line terminator", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a // \\u005c\\u005c\\u000a .add(a)
                        ;
                    } }
                    """},
            new String[]{"a Unicode-escaped backslash before one raw backslash, in a comment", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a // \\u005c\\ .add(a)
                        ;
                    } }
                    """},
            new String[]{"a surrogate pair before an escaped line terminator", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a // \\uD83D\\uDE00\\u000a .add(a)
                        ;
                    } }
                    """});

    /**
     * Sources JuLC rejects: {name, source, whether javac accepts it, a fragment of JuLC's message}. Where javac
     * accepts one, JuLC fails closed, because javac's reading differs from the JLS or cannot be kept.
     */
    private static final List<String[]> REJECTED = List.of(
            new String[]{"a backslash a Unicode escape produces, in a name", """
                    import java.math.BigInteger;
                    public class P {
                        static final BigInteger a = BigInteger.ONE;
                        public static BigInteger m(BigInteger x) {
                            BigInteger \\u005cu0061 = x;
                            return a;
                        }
                    }
                    """, "javac rejects", "Illegal character '\\'"},
            new String[]{"a Unicode escape with a digit that is not hex, in a comment", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a; // \\u00G1
                    } }
                    """, "javac rejects", "Illegal Unicode escape"},
            new String[]{"a Unicode escape cut off by the end of the file", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a;
                    } } // \\u00""", "javac rejects", "Illegal Unicode escape"},
            new String[]{"a backslash and u without digits, in a comment", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a; /* \\u */
                    } }
                    """, "javac rejects", "Illegal Unicode escape"},
            new String[]{"a Unicode-escaped backslash before raw backslashes, in a comment", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a // \\u005c\\\\\\u000a .add(a)
                        ;
                    } }
                    """, "javac accepts", "followed by raw backslashes and u"},
            new String[]{"a Unicode escape with digits other than ASCII hex digits, in a comment", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a; // \\u١٢٣٤
                    } }
                    """, "javac accepts", "Illegal Unicode escape"},
            new String[]{"an unpaired surrogate, in a comment", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a; // \\uD800
                    } }
                    """, "javac accepts", "unpaired surrogate U+D800"},
            new String[]{"an unpaired surrogate before raw backslashes, in a comment", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a // \\uD800\\\\\\u000a .add(a)
                        ;
                    } }
                    """, "javac accepts", "unpaired surrogate U+D800"});

    private static final List<BigInteger> INPUTS = List.of(BigInteger.valueOf(5), BigInteger.valueOf(-3), BigInteger.ZERO);

    @Test
    void everySourceComputesWhatTheJvmComputes() throws Exception {
        var problems = new ArrayList<String>();
        for (var source : SOURCES) {
            var jvm = javac(source[1]).orElseThrow(() -> new AssertionError("javac rejects " + source[0]));
            for (var level : OptimizationLevel.values()) {
                var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                        new CompilerOptions().setOptimizationLevel(level)).compileMethod(source[1], "m").program();
                // The script carries the FLAT bytes, so evaluate those too.
                var deployed = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(program));
                for (var input : INPUTS) {
                    var expected = new Term.Const(Constant.integer((BigInteger) jvm.getMethod("m", BigInteger.class).invoke(null, input)));
                    for (var form : List.of(program, deployed)) {
                        var result = CompilerTestVm.pv11("Java").evaluateWithArgs(form, List.of(new PlutusData.IntData(input)));
                        var actual = result instanceof EvalResult.Success ok ? ok.resultTerm() : result;
                        if (!expected.equals(actual)) {
                            problems.add(source[0] + " at " + level + (form == deployed ? " after FLAT" : "") + " on "
                                    + input + ": JVM " + expected + ", UPLC " + actual);
                        }
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyRejectedSourceIsRejectedWhateverJavacDoes() throws Exception {
        var problems = new ArrayList<String>();
        for (var row : REJECTED) {
            boolean javacAccepts = javac(row[1]).isPresent();
            if (javacAccepts != row[2].equals("javac accepts")) problems.add(row[0] + ": expected " + row[2]);
            for (var level : OptimizationLevel.values()) {
                try {
                    new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level))
                            .compileMethod(row[1], "m");
                    problems.add(row[0] + " at " + level + ": JuLC compiles it");
                } catch (CompilerException rejected) {
                    if (!rejected.getMessage().contains(row[3])) {
                        problems.add(row[0] + ": message lacks '" + row[3] + "': " + rejected.getMessage());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void aLibrarySourceIsReadTheSameWay() {
        String library = """
                package com.example.lib;
                import java.math.BigInteger;
                @OnchainLibrary
                public class Twice {
                    public static BigInteger of(BigInteger a) {
                        return a // \\u000a .add(a)
                        ;
                    }
                }""";
        String validator = """
                import java.math.BigInteger;
                import com.example.lib.Twice;
                public class P { public static BigInteger m(BigInteger a) { return Twice.of(a); } }
                """;
        for (var level : OptimizationLevel.values()) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(validator, "m", List.of(library)).program();
            var result = CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of(new PlutusData.IntData(BigInteger.valueOf(5))));
            assertEquals(new Term.Const(Constant.integer(BigInteger.TEN)),
                    assertInstanceOf(EvalResult.Success.class, result, level.toString()).resultTerm(), level.toString());
        }
    }

    @Test
    void nodePositionsReferToTheSourceAsWritten() {
        // Source maps and diagnostics key on positions: after an escape, a column counts its six characters.
        var unit = JavaSourceParser.parseCompilationUnit(
                "class P { Object m(Object a) { return \\u0061.hashCode(); } }");
        var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
        assertEquals("a", call.getScope().orElseThrow().toString());
        assertEquals(46, call.getName().getBegin().orElseThrow().column, "hashCode as written, not 41 as translated");
        // A range that ends on an escape ends on its last character.
        var scope = call.getScope().orElseThrow().getRange().orElseThrow();
        assertEquals(new Position(1, 39), scope.begin);
        assertEquals(new Position(1, 44), scope.end, "the escape's last character, not its first");
    }

    @Test
    void parseProblemsReferToTheSourceAsWritten() {
        // Escaped line terminators: the problem is on line 4 as written, not line 6 as translated.
        assertEquals(problemAt("class P {\n  //\n  //\n  int x = ;\n}"),
                problemAt("class P {\n  // \\u000a\n  // \\u000a\n  int x = ;\n}"));
        // Escapes earlier on the line: each adds its six characters, less the one it denotes.
        var plain = problemAt("class P { int abc = ; }");
        assertEquals(new Position(plain.line, plain.column + 15),
                problemAt("class P { int \\u0061\\u0062\\u0063 = ; }"));
        // CR LF counts as one line terminator, as in JavaParser.
        assertEquals(problemAt("class P {\r\n  //\r\n  int x = ;\r\n}"),
                problemAt("class P {\r\n  // \\u000a\r\n  int x = ;\r\n}"));
    }

    @Test
    void aLexicalErrorNamesThePositionAsWritten() {
        String plain = lexicalError("class P { int a = 1; # }");
        String escaped = lexicalError("class P { int \\u0061 = 1; # }");
        int column = Integer.parseInt(plain.replaceAll("(?s).*column (\\d+).*", "$1"));
        assertTrue(escaped.contains("line 1, column " + (column + 5)), escaped);
        // The end of the file just after an escaped line terminator: still line 1 as written, after the escape.
        String eof = lexicalError("class P { int x; } /* \\u000a");
        assertTrue(eof.contains("line 1, column 29"), eof);
    }

    @Test
    void commentsAreAttributedAndPlacedAsWritten() {
        // A line comment ended by an escape, then a Javadoc on the next line as written.
        var unit = JavaSourceParser.parseCompilationUnit(
                "class P {\n int a; // \\u000a int b;\n /** doc of c */\n int c;\n}");
        var c = field(unit, "c");
        var doc = c.getComment().orElseThrow(() -> new AssertionError("the Javadoc is attached to c"));
        assertEquals(" doc of c ", doc.getContent());
        assertEquals(new Position(3, 2), doc.getRange().orElseThrow().begin);
        assertTrue(unit.findAll(FieldDeclaration.class).stream().anyMatch(f -> f.getVariable(0).getNameAsString().equals("b")),
                "the escape ends the line comment, so b is a field");
        // Escapes earlier on the line.
        var same = JavaSourceParser.parseCompilationUnit(
                "class P { int \\u0061\\u0062\\u0063 = 1; /** doc */ int r = 2; }");
        var plain = JavaSourceParser.parseCompilationUnit("class P { int abc = 1; /** doc */ int r = 2; }");
        assertEquals(" doc ", field(same, "r").getComment().orElseThrow().getContent());
        var plainBegin = field(plain, "r").getComment().orElseThrow().getRange().orElseThrow().begin;
        assertEquals(new Position(1, plainBegin.column + 15), field(same, "r").getComment().orElseThrow().getRange().orElseThrow().begin);
    }

    @Test
    void debugBindingRangesReferToTheSourceAsWritten() {
        String template = """
                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(PlutusData %s, ScriptContext %s) {
                        return true;
                    }
                }
                """;
        var plain = bindings(template.formatted("redeemer", "ctx"));
        var escaped = bindings(template.formatted("redeeme\\u0072", "ct\\u0078"));
        // redeemer ends 5 columns later; ctx starts 5 and ends 10 columns later.
        assertEquals(shifted(plain.get("redeemer"), 0, 5), escaped.get("redeemer"));
        assertEquals(shifted(plain.get("ctx"), 5, 10), escaped.get("ctx"));
    }

    @Test
    void annotationPreFiltersDoNotSkipEscapedAnnotations() {
        String validator = "@\\u0053pendingValidator class V { @Entrypoint static boolean validate(PlutusData r, ScriptContext c) { return true; } }";
        assertTrue(JavaSourceIntrospector.mightContainValidatorAnnotation(validator));
        assertEquals("V", JavaSourceIntrospector.inspect(validator).validatorType().orElseThrow().simpleName());
        assertTrue(JavaSourceIntrospector.mightContainOnchainLibraryAnnotation("@\\u004fnchainLibrary public class Twice {}"));
        assertTrue(JavaSourceIntrospector.mightContainTestAnnotation("class T { @\\u0054est void t() {} }"));
        // Without an escape, a source without the annotation is still filtered out.
        assertFalse(JavaSourceIntrospector.mightContainValidatorAnnotation("class V { }"));
        assertFalse(JavaSourceIntrospector.mightContainOnchainLibraryAnnotation("class V { }"));
    }

    @Test
    void annotationPreFiltersSkipOffChainSourcesJuLCCannotRead() throws Exception {
        // Off-chain Java that javac compiles but JuLC's strict reading rejects: an unpaired surrogate in a regex,
        // and an unnamed variable (Java 22) next to an escape. Neither names an annotation, so the build tasks must
        // skip it rather than hand it to the strict parser.
        for (var source : List.of("""
                import java.util.regex.Pattern;
                public class P {
                    static final java.util.regex.Pattern HIGH = Pattern.compile("[\\uD800-\\uDBFF]");
                }
                """, """
                import java.util.List;
                public class P {
                    static String e = "caf\\u00e9";
                    static int count(List<String> l) { int n = 0; for (String _ : l) n++; return n; }
                }
                """)) {
            assertTrue(javac(source).isPresent(), "javac compiles it: " + source);
            assertFalse(JavaSourceParser.parse(source).isSuccessful(), "JuLC's strict reading rejects it: " + source);
            assertFalse(JavaSourceIntrospector.mightContainOnchainLibraryAnnotation(source), source);
            assertFalse(JavaSourceIntrospector.mightContainValidatorAnnotation(source), source);
            assertFalse(JavaSourceIntrospector.mightContainTestAnnotation(source), source);
        }
        // A source that does name an annotation still reaches the strict parser, and fails closed there.
        String candidate = "@\\u004fnchainLibrary public class Bad { static String s = \"\\uD800\"; }";
        assertTrue(JavaSourceIntrospector.mightContainOnchainLibraryAnnotation(candidate));
        assertThrows(JavaSourceIntrospector.SourceParseException.class, () -> JavaSourceIntrospector.inspect(candidate));
    }

    /** Ways main code could build a JavaParser parser outside {@code JavaSourceParser}. */
    private static final List<Pattern> PARSER_CONSTRUCTS = List.of(
            Pattern.compile("\\b(StaticJavaParser|JavaParserAdapter|JavaParserFacade)\\b"),
            Pattern.compile("\\bnew\\s+(com\\.github\\.javaparser\\.)?(JavaParser|ParserConfiguration)\\s*\\("),
            Pattern.compile("\\bJavaParser\\s*::\\s*new\\b"));

    private static List<String> parserConstructs(String text) {
        return PARSER_CONSTRUCTS.stream().map(p -> p.matcher(text)).filter(Matcher::find).map(Matcher::group).toList();
    }

    @Test
    void theParserLintSeesEveryShape() {
        for (var shape : List.of("import com.github.javaparser.StaticJavaParser;", "Function<String, ?> f = StaticJavaParser::parse;",
                "StaticJavaParser.parse(source)", "new JavaParser()", "new JavaParser(new ParserConfiguration())",
                "new com.github.javaparser.JavaParser()", "com.github.javaparser.JavaParserAdapter.of(parser)",
                "Supplier<JavaParser> s = JavaParser::new;", "var c = new ParserConfiguration ();")) {
            assertFalse(parserConstructs(shape).isEmpty(), shape);
        }
        assertEquals(List.of(), parserConstructs("var unit = JavaSourceParser.parse(source); JavaParser parser;"));
    }

    @Test
    void everyParserIsMadeByJavaSourceParser() throws IOException {
        // A parser configured anywhere else would not translate Unicode escapes. Only real module trees count:
        // hidden directories (nested worktrees, for example) and build output are skipped.
        var root = Path.of("..").toAbsolutePath().normalize();
        var files = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                boolean skip = !dir.equals(root) && (name.startsWith(".") || name.equals("build") || name.equals("node_modules"));
                return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.endsWith(".java") && relative.contains("/src/main/java/")
                        && !file.getFileName().toString().equals("JavaSourceParser.java")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue(files.size() > 100, "found the module sources: " + files.size());
        var offenders = new ArrayList<String>();
        for (var file : files) {
            for (var construct : parserConstructs(Files.readString(file))) offenders.add(root.relativize(file) + ": " + construct);
        }
        assertEquals(List.of(), offenders);
    }

    private static Position problemAt(String source) {
        var result = JavaSourceParser.parse(source);
        assertFalse(result.isSuccessful(), source);
        return result.getProblems().get(0).getLocation().orElseThrow().getBegin().getRange().orElseThrow().begin;
    }

    private static String lexicalError(String source) {
        var result = JavaSourceParser.parse(source);
        assertFalse(result.isSuccessful(), source);
        return result.getProblems().stream().map(Problem::getMessage).filter(m -> m.contains("Lexical error"))
                .findFirst().orElseThrow(() -> new AssertionError("no lexical error: " + result.getProblems()));
    }

    private static FieldDeclaration field(CompilationUnit unit, String name) {
        return unit.findAll(FieldDeclaration.class).stream()
                .filter(f -> f.getVariable(0).getNameAsString().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, DebugMetadata.SourceRange> bindings(String source) {
        var metadata = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setSourceMapEnabled(true))
                .compileForDebug(source, List.of()).debugMetadata();
        var ranges = new HashMap<String, DebugMetadata.SourceRange>();
        for (var binding : metadata.bindings()) ranges.put(binding.name(), binding.declarationRange());
        return ranges;
    }

    private static DebugMetadata.SourceRange shifted(DebugMetadata.SourceRange range, int begin, int end) {
        return new DebugMetadata.SourceRange(range.sourceId(), range.startUtf16() + begin, range.endUtf16() + end,
                range.startLine(), range.startColumn() + begin, range.endLine(), range.endColumn() + end);
    }

    /** The class javac compiles from the source, or empty if javac rejects it. */
    private static Optional<Class<?>> javac(String source) throws Exception {
        var dir = Files.createTempDirectory("julc-unicode-escape-");
        var file = dir.resolve("P.java");
        Files.writeString(file, source);
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, new ByteArrayOutputStream(), "-d", dir.toString(), file.toString());
        if (status != 0) return Optional.empty();
        return Optional.of(new URLClassLoader(new URL[]{dir.toUri().toURL()}, null).loadClass("P"));
    }
}
