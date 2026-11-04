package org.julclang.compiler;

import org.julclang.compiler.frontend.javac.SourceReader;

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
                    """, "javac rejects", "(compiler.err.illegal.char)"},
            new String[]{"a Unicode escape with a digit that is not hex, in a comment", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a; // \\u00G1
                    } }
                    """, "javac rejects", "(compiler.err.illegal.unicode.esc)"},
            new String[]{"a Unicode escape cut off by the end of the file", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a;
                    } } // \\u00""", "javac rejects", "(compiler.err.illegal.unicode.esc)"},
            new String[]{"a backslash and u without digits, in a comment", """
                    import java.math.BigInteger;
                    public class P { public static BigInteger m(BigInteger a) {
                        return a; /* \\u */
                    } }
                    """, "javac rejects", "(compiler.err.illegal.unicode.esc)"},
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

    /**
     * ADR-068 M5: on javac's frontend, the default, javac decides. A source javac rejects is {@code JULC0085}; a source javac
     * accepts compiles, as the ADR's Compatibility section and r11 decide: the escape shapes JuLC's readers rejected
     * (scheduled for acceptance), and a lone surrogate in a comment, which cannot reach a script (accepted by decision).
     */
    @Test
    void onTheDefaultFrontendJavacDecides() throws Exception {
        var problems = new ArrayList<String>();
        for (var row : REJECTED) {
            for (var level : OptimizationLevel.values()) {
                var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level));
                if (row[2].equals("javac accepts")) {
                    try {
                        compiler.compileMethod(row[1], "m");
                    } catch (CompilerException rejected) {
                        problems.add(row[0] + " at " + level + ": javac accepts it, JuLC rejects it: " + rejected.getMessage());
                    }
                } else {
                    var rejected = assertThrows(CompilerException.class, () -> compiler.compileMethod(row[1], "m"), row[0]);
                    if (!rejected.getMessage().contains("JULC0085") || !rejected.getMessage().contains(row[3])) {
                        problems.add(row[0] + ": not javac's " + row[3] + ": " + rejected.getMessage());
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
                import org.julclang.stdlib.annotation.OnchainLibrary;
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
    void debugBindingRangesReferToTheSourceAsWritten() {
        String template = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;
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

    /** Ways main code could build a JavaParser parser outside {@code JavaSourceParser}. */
    private static final List<Pattern> PARSER_CONSTRUCTS = List.of(
            Pattern.compile("\\b(StaticJavaParser|JavaParserAdapter|JavaParserFacade)\\b"),
            Pattern.compile("\\bnew\\s+(com\\.github\\.javaparser\\.)?(JavaParser|ParserConfiguration)\\s*\\("),
            Pattern.compile("\\bJavaParser\\s*::\\s*new\\b"));

    private static List<String> parserConstructs(String text) {
        return PARSER_CONSTRUCTS.stream().map(p -> p.matcher(text)).filter(Matcher::find).map(Matcher::group).toList();
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
