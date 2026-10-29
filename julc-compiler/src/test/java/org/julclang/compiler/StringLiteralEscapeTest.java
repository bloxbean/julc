package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-062 T22 (#256): a string literal compiles to the string javac gives it. The compiler used to keep
 * the literal's source text, escapes included, so {@code "a\nb"} held a backslash and an {@code n}
 * on-chain. javac is the oracle: each literal is compiled and run by javac, and JuLC must produce the
 * same string at every optimization level, both as compiled and after a FLAT round trip, or reject the
 * literal where javac does. {@link #FAILS_CLOSED} lists the literals JuLC rejects although javac accepts them.
 */
class StringLiteralEscapeTest {

    /** Literal texts, quotes excluded, as they appear in source (each backslash doubled here). */
    private static final List<String> LITERALS = List.of(
            // Escape sequences (JLS 3.10.7).
            "a\\bb", "a\\sb", "a\\tb", "a\\nb", "a\\fb", "a\\rb", "a\\\"b", "a\\'b", "a\\\\b", "a\\\\nb",
            "\\0", "\\7", "\\12", "\\101", "\\377", "\\1234", "\\400", "\\0001", "\\08",
            "plain", "", "é😀", "a\\qb",
            // Unicode escapes (JLS 3.3), translated first.
            "\\u0041", "\\uuu0041", "\\u00e9", "\\uD83D\\uDE00", "\\u0661", "\\u005cn", "\\u005c\\u006e",
            "\\u005cs", "\\u005c\\u005c", "\\\\u0041", "\\\\\\u005cn", "\\u005c\\u005cu0041",
            "\\u005cu005cu0041", "\\u005c\\u0041", "\\u0022", "\\u000a", "\\u000d", "\\u12", "\\uXYZW",
            "a\\u005c", "\\u005cq",
            // A backslash written as a Unicode escape before raw backslashes. javac counts it toward their
            // parity (JLS 21 §3.3 does not), so JuLC rejects an odd run of them before a raw backslash.
            "\\u005c\\\\\\u0030", "\\u005c\\\\\\u005cn", "\\u005c\\\\\\u0041", "\\u005c\\\\u0041",
            "\\u005c\\u005c\\u005c\\n", "\\u005c\\\\", "\\u005c\\u005c\\\\\\u0041", "\\u005c\\u005c\\\\",
            // Surrogates: a UPLC string cannot hold an unpaired one.
            "\\uD800", "\\uDC00", "\\uDE00\\uD83D", "1\\uD800\\\\\\u0041", "5\\uD800\\\\u5", "\\uD83D\\uDE00\\\\\\u0041",
            // Digits other than ASCII hex digits, which javac takes and the JLS does not.
            "\\u١٢٣٤", "\\u００４１");

    /**
     * Literals javac accepts and JuLC rejects, so they fail closed. Each is in {@link #LITERALS}: several
     * {@code u} (JavaParser's lexer allows one), the backslash parity above, unpaired surrogates, and
     * digits other than ASCII hex digits.
     */
    private static final Set<String> FAILS_CLOSED = Set.of(
            "\\uuu0041", "\\u005c\\\\\\u0030", "\\u005c\\\\\\u005cn", "\\u005c\\\\\\u0041", "\\u005c\\\\u0041", "\\u005c\\u005c\\u005c\\n",
            "\\uD800", "\\uDC00", "\\uDE00\\uD83D", "\\u١٢٣٤", "\\u００４１");

    private static String source(String literal) {
        return "public class Lit { public static String m() { return \"" + literal + "\"; } }";
    }

    /** The string javac gives the literal, or empty if javac rejects it. */
    private static Optional<String> javac(String literal) throws Exception {
        var dir = Files.createTempDirectory("julc-string-literal-");
        var file = dir.resolve("Lit.java");
        Files.writeString(file, source(literal));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, new ByteArrayOutputStream(),
                "-d", dir.toString(), file.toString());
        if (status != 0) return Optional.empty();
        try (var loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            return Optional.of((String) loader.loadClass("Lit").getMethod("m").invoke(null));
        }
    }

    private static Program compile(String source, OptimizationLevel level) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "m").program();
    }

    /**
     * The program's result, evaluated as compiled and after a FLAT round trip: the script carries the FLAT
     * bytes, so a constant the encoder cannot keep must not pass on the in-memory program alone.
     */
    private static Map<String, Term> evaluate(String source, OptimizationLevel level) {
        var program = compile(source, level);
        var results = new LinkedHashMap<String, Term>();
        results.put("in memory", run(program, level, source));
        results.put("after FLAT", run(UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(program)), level, source));
        return results;
    }

    private static Term run(Program program, OptimizationLevel level, String source) {
        var result = CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of());
        return assertInstanceOf(EvalResult.Success.class, result, level + ": " + source).resultTerm();
    }

    /** The first line of JuLC's error, or empty if JuLC compiles the literal at every level. */
    private static Optional<String> rejection(String literal) {
        Optional<String> first = Optional.empty();
        for (var level : OptimizationLevel.values()) {
            try {
                compile(source(literal), level);
                if (first.isPresent()) return Optional.of("rejected at some levels only: " + first.get());
            } catch (CompilerException rejected) {
                first = Optional.of(rejected.getMessage().lines().findFirst().orElse(""));
            }
        }
        return first;
    }

    @Test
    void everyLiteralCompilesToJavacsString() throws Exception {
        assertTrue(LITERALS.containsAll(FAILS_CLOSED), "every fail-closed literal is checked against javac");
        var problems = new ArrayList<String>();
        for (String literal : LITERALS) {
            Optional<String> expected = javac(literal);
            if (FAILS_CLOSED.contains(literal)) {
                if (expected.isEmpty()) problems.add(literal + ": listed as failing closed, but javac rejects it too");
                if (rejection(literal).isEmpty()) problems.add(literal + ": listed as failing closed, but JuLC compiles it");
                continue;
            }
            if (expected.isEmpty()) {
                if (rejection(literal).isEmpty()) problems.add(literal + ": javac rejects it, JuLC compiles it");
                continue;
            }
            for (var level : OptimizationLevel.values()) {
                Map<String, Term> actual;
                try {
                    actual = evaluate(source(literal), level);
                } catch (CompilerException rejected) {
                    problems.add(literal + ": javac accepts it, JuLC rejects it: " + rejected.getMessage().lines().findFirst().orElse(""));
                    break;
                }
                actual.forEach((form, term) -> {
                    if (!term.equals(new Term.Const(Constant.string(expected.get())))) {
                        problems.add(literal + " at " + level + " " + form + ": javac " + expected.get().chars().boxed().toList()
                                + ", JuLC " + term);
                    }
                });
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void aBigIntegerLiteralDecodesItsEscapes() {
        // new BigInteger("\061\062") is 12 in Java.
        String source = "import java.math.BigInteger; class Lit { static BigInteger m() { return new BigInteger(\"\\061\\062\"); } }";
        for (var level : OptimizationLevel.values()) {
            for (var result : evaluate(source, level).entrySet()) {
                assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(12))), result.getValue(), level + " " + result.getKey());
            }
        }
    }

    @Test
    void anIllegalEscapeNamesTheEscape() {
        // JavaParser lets a backslash through when it is written as a Unicode escape.
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source("\\u005cq"), "m"));
        assertTrue(error.getMessage().contains("Illegal escape sequence \\q"), error.getMessage());
        assertTrue(error.getMessage().contains("or an octal escape"), error.getMessage());
    }

    @Test
    void aRejectionSuggestsWhatFitsItsReason() {
        // An unpaired surrogate: the escape-sequence list would not help.
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source("\\uD800"), "m"));
        assertTrue(error.getMessage().contains("unpaired surrogate U+D800"), error.getMessage());
        assertTrue(error.getMessage().contains("low surrogate"), error.getMessage());
        assertFalse(error.getMessage().contains("octal escape"), error.getMessage());
    }

    @Test
    void anUnpairedSurrogateWrittenAsItselfIsRejected() {
        // A source file cannot hold one in UTF-8, but a source string can, and the FLAT encoder would write ?.
        for (char surrogate : new char[]{0xD800, 0xDFFF}) {
            var error = assertThrows(CompilerException.class,
                    () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source("a" + surrogate), "m"));
            assertTrue(error.getMessage().contains("unpaired surrogate"), error.getMessage());
        }
        // A pair written as itself is one supplementary character and compiles.
        String pair = Character.toString(0x1F600);
        assertEquals(new Term.Const(Constant.string("a" + pair)),
                evaluate(source("a" + pair), OptimizationLevel.DEFAULT).get("after FLAT"));
    }
}
