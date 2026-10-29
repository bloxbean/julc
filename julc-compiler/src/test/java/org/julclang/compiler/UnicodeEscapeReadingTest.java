package org.julclang.compiler;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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
                    """});

    private static final List<BigInteger> INPUTS = List.of(BigInteger.valueOf(5), BigInteger.valueOf(-3), BigInteger.ZERO);

    @Test
    void everySourceComputesWhatTheJvmComputes() throws Exception {
        var problems = new ArrayList<String>();
        for (var source : SOURCES) {
            var jvm = javac(source[1]);
            for (var level : OptimizationLevel.values()) {
                var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                        new CompilerOptions().setOptimizationLevel(level)).compileMethod(source[1], "m").program();
                for (var input : INPUTS) {
                    var expected = new Term.Const(Constant.integer((BigInteger) jvm.getMethod("m", BigInteger.class).invoke(null, input)));
                    var result = CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of(new PlutusData.IntData(input)));
                    var actual = result instanceof EvalResult.Success ok ? ok.resultTerm() : result;
                    if (!expected.equals(actual)) {
                        problems.add(source[0] + " at " + level + " on " + input + ": JVM " + expected + ", UPLC " + actual);
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
    void positionsReferToTheSourceAsWritten() {
        // Source maps and diagnostics key on positions: after an escape, a column counts its six characters.
        var unit = JavaSourceParser.parseCompilationUnit(
                "class P { Object m(Object a) { return \\u0061.hashCode(); } }");
        var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
        assertEquals("a", call.getScope().orElseThrow().toString());
        assertEquals(46, call.getName().getBegin().orElseThrow().column, "hashCode as written, not 41 as translated");
    }

    @Test
    void everyParserIsMadeByJavaSourceParser() throws IOException {
        // A parser configured anywhere else would not translate Unicode escapes.
        var root = Path.of("..").toAbsolutePath().normalize();
        var offenders = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(root)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/build/") && !f.toString().contains("/node_modules/"))
                    .filter(f -> !f.getFileName().toString().equals("JavaSourceParser.java")).toList()) {
                var text = Files.readString(file);
                for (var construct : List.of("new JavaParser(", "StaticJavaParser.", "new ParserConfiguration(")) {
                    if (text.contains(construct)) offenders.add(root.relativize(file) + ": " + construct);
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    private static Class<?> javac(String source) throws Exception {
        var dir = Files.createTempDirectory("julc-unicode-escape-");
        var file = dir.resolve("P.java");
        Files.writeString(file, source);
        var errors = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors, "-d", dir.toString(), file.toString());
        assertEquals(0, status, errors::toString);
        return new URLClassLoader(new URL[]{dir.toUri().toURL()}, null).loadClass("P");
    }
}
