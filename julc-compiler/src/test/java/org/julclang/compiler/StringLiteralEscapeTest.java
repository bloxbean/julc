package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.Term;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-062 T22 (#256): a string literal compiles to the string javac gives it. The compiler used to keep
 * the literal's source text, escapes included, so {@code "a\nb"} held a backslash and an {@code n}
 * on-chain. javac is the oracle: each literal is compiled and run by javac, and JuLC must produce the
 * same string at every optimization level, or reject the literal where javac does.
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
            "a\\u005c", "\\u005cq");

    /**
     * javac reads it as {@code A}, which follows no rule the JLS states: the backslash a Unicode escape
     * produces starts no further Unicode escape (§3.3), so the text is a backslash followed by
     * {@code u}, an illegal escape sequence. JuLC rejects it, so it fails closed.
     */
    private static final List<String> REJECTED_BY_JULC_ONLY = List.of("\\u005cu0041");

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

    private static Term evaluate(String source, OptimizationLevel level) {
        var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "m").program();
        var result = CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of());
        return assertInstanceOf(EvalResult.Success.class, result, level + ": " + source).resultTerm();
    }

    @Test
    void everyLiteralCompilesToJavacsString() throws Exception {
        var problems = new ArrayList<String>();
        for (String literal : LITERALS) {
            Optional<String> expected = javac(literal);
            if (REJECTED_BY_JULC_ONLY.contains(literal)) {
                assertEquals(Optional.of("A"), expected, "javac's reading of " + literal);
                expected = Optional.empty();
            }
            for (var level : OptimizationLevel.values()) {
                if (expected.isEmpty()) {
                    try {
                        new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source(literal), "m");
                        problems.add(literal + ": javac rejects it, JuLC compiles it");
                    } catch (CompilerException rejected) {
                        // As javac.
                    }
                    break;
                }
                Term actual;
                try {
                    actual = evaluate(source(literal), level);
                } catch (CompilerException rejected) {
                    problems.add(literal + ": javac accepts it, JuLC rejects it: " + rejected.getMessage().lines().findFirst().orElse(""));
                    break;
                }
                if (!actual.equals(new Term.Const(Constant.string(expected.get())))) {
                    problems.add(literal + " at " + level + ": javac " + expected.get().chars().boxed().toList()
                            + ", JuLC " + actual);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void aBigIntegerLiteralDecodesItsEscapes() {
        // new BigInteger("\061\062") is 12 in Java.
        String source = "import java.math.BigInteger; class Lit { static BigInteger m() { return new BigInteger(\"\\061\\062\"); } }";
        for (var level : OptimizationLevel.values()) {
            assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(12))), evaluate(source, level), level.toString());
        }
    }

    @Test
    void anIllegalEscapeWrittenWithAUnicodeBackslashIsRejected() {
        // Unicode escapes are translated before lexing (T24), so the lexer sees the escape \q and rejects it.
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source("\\u005cq"), "m"));
        assertTrue(error.getMessage().contains("Lexical error"), error.getMessage());
    }
}
