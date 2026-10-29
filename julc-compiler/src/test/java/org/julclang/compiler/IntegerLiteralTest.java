package org.julclang.compiler;

import net.jqwik.api.*;
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
 * ADR-062 T25 (#270): an integer literal compiles to the value javac gives it (JLS 21 §3.10.1). The
 * compiler used to read a hexadecimal, octal or binary {@code int} literal as unsigned, so
 * {@code 0xFFFFFFFF} was 4294967295 where Java has -1, and it rejected underscores and non-decimal
 * {@code long} literals with a NumberFormatException. javac is the oracle.
 */
class IntegerLiteralTest {

    /** A literal and the type of the variable it initialises. */
    record Literal(String type, String text) {
        String declaration(int i) {
            return type + " v" + i + " = " + text + ";";
        }
    }

    private static final List<Literal> FIXED = List.of(
            new Literal("int", "0xFFFFFFFF"), new Literal("int", "0x80000000"), new Literal("int", "0x7FFFFFFF"),
            new Literal("long", "0xFFFFFFFF"), new Literal("int", "037777777777"), new Literal("int", "0b11111111111111111111111111111111"),
            new Literal("int", "-2147483648"), new Literal("int", "1_000"), new Literal("int", "0B1_1"), new Literal("int", "0x_FF".replace("_", "")),
            new Literal("long", "0xFFL"), new Literal("long", "0xFF_FFl"), new Literal("long", "0xFFFFFFFFFFFFFFFFL"),
            new Literal("long", "0x8000000000000000L"), new Literal("long", "-9223372036854775808L"), new Literal("long", "1_000_000_000_000L"),
            new Literal("long", "0777L"), new Literal("long", "0b1L"), new Literal("int", "017"), new Literal("int", "0"), new Literal("long", "0L"),
            new Literal("int", "-0xFFFFFFFF"), new Literal("int", "-1"), new Literal("long", "2147483648L"));

    /** javac rejects each as too large, and so must JuLC, with JULC0070. */
    private static final List<Literal> TOO_LARGE = List.of(
            new Literal("long", "2147483648"), new Literal("long", "0x1FFFFFFFF"), new Literal("long", "040000000000"),
            new Literal("long", "9223372036854775808L"), new Literal("long", "0x1_0000_0000_0000_0000L"), new Literal("long", "-(2147483648)"));

    @Test
    void fixedLiteralsHaveJavacsValueAtEveryLevel() throws Exception {
        assertEquals(Optional.empty(), mismatch(FIXED, OptimizationLevel.values()));
    }

    @Test
    void aLiteralJavacRejectsAsTooLargeIsRejectedWithItsCode() throws Exception {
        for (var literal : TOO_LARGE) {
            assertTrue(jvm(List.of(literal)).isEmpty(), "javac accepts " + literal);
            var error = assertThrows(CompilerException.class,
                    () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source(List.of(literal)), "m"),
                    literal.toString());
            var codes = error.diagnostics().stream().map(d -> d.code()).toList();
            assertTrue(codes.contains("JULC0070"), literal + ": " + error.getMessage());
        }
    }

    /** Random literals in every radix, both widths, with underscores, suffix and prefix case, and negation. */
    @Property(tries = 200)
    void generatedLiteralsHaveJavacsValue(@ForAll("batches") List<Literal> batch) throws Exception {
        assertEquals(Optional.empty(), mismatch(batch, new OptimizationLevel[]{OptimizationLevel.BASELINE}));
    }

    @Provide
    Arbitrary<List<Literal>> batches() {
        return literals().list().ofMinSize(1).ofMaxSize(25);
    }

    private Arbitrary<Literal> literals() {
        return Combinators.combine(Arbitraries.of(32, 64), Arbitraries.of(10, 16, 8, 2), Arbitraries.longs(),
                Arbitraries.integers().between(0, 63), Arbitraries.of(true, false), Arbitraries.of(true, false),
                Arbitraries.of(true, false)).as(IntegerLiteralTest::literal);
    }

    private static Literal literal(int bits, int radix, long seed, int bitCount, boolean negate, boolean upper, boolean underscores) {
        // A value of up to bitCount bits within the width: any bit pattern for a non-decimal literal, the
        // signed range (or the most negative magnitude, negated) for a decimal one.
        var range = BigInteger.ONE.shiftLeft(bits);
        var signBit = BigInteger.ONE.shiftLeft(bits - 1);
        var value = BigInteger.valueOf(seed).mod(range).mod(BigInteger.ONE.shiftLeft(Math.min(bitCount + 1, bits)));
        if (radix == 10 && value.compareTo(signBit) >= 0) value = negate ? signBit : value.subtract(signBit);
        String digits = value.toString(radix);
        if (upper) digits = digits.toUpperCase();
        if (underscores && digits.length() > 2) digits = digits.charAt(0) + "_" + digits.substring(1, digits.length() - 1) + "__" + digits.charAt(digits.length() - 1);
        String prefix = switch (radix) {
            case 16 -> upper ? "0X" : "0x";
            case 2 -> upper ? "0B" : "0b";
            case 8 -> "0";
            default -> "";
        };
        String text = prefix + digits + (bits == 64 ? (upper ? "L" : "l") : "");
        // Negating a non-decimal literal equal to the most negative value overflows in Java, which is T6.
        boolean signed = radix != 10 && value.equals(signBit);
        if (negate && !signed) text = "-" + text;
        return new Literal(bits == 64 ? "long" : "int", text);
    }

    private static String source(List<Literal> literals) {
        var body = new StringBuilder();
        var result = new StringBuilder("BigInteger.ZERO");
        for (int i = 0; i < literals.size(); i++) {
            body.append(literals.get(i).declaration(i)).append('\n');
            // Each value in its own 130-bit slot, offset to be non-negative, so the sum determines them all.
            result.append(".add(BigInteger.valueOf(v").append(i).append(").add(new BigInteger(\"9223372036854775808\"))")
                    .append(".multiply(new BigInteger(\"").append(BigInteger.ONE.shiftLeft(130 * i)).append("\")))");
        }
        return "import java.math.BigInteger;\npublic class Lit { public static BigInteger m() {\n" + body
                + "return " + result + ";\n} }\n";
    }

    /** The result javac's class computes, or empty if javac rejects the source. */
    private static Optional<BigInteger> jvm(List<Literal> literals) throws Exception {
        var dir = Files.createTempDirectory("julc-integer-literal-");
        var file = dir.resolve("Lit.java");
        Files.writeString(file, source(literals));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, new ByteArrayOutputStream(),
                "-d", dir.toString(), file.toString());
        if (status != 0) return Optional.empty();
        try (var loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            return Optional.of((BigInteger) loader.loadClass("Lit").getMethod("m").invoke(null));
        }
    }

    /** The first literal whose UPLC value differs from javac's, at any of the levels. */
    private static Optional<String> mismatch(List<Literal> literals, OptimizationLevel[] levels) throws Exception {
        var expected = jvm(literals).orElseThrow(() -> new AssertionError("javac rejects " + literals));
        for (var level : levels) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source(literals), "m").program();
            var result = CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of());
            var actual = assertInstanceOf(EvalResult.Success.class, result, level + ": " + literals).resultTerm();
            if (!actual.equals(new Term.Const(Constant.integer(expected)))) {
                // Find the culprit one literal at a time.
                for (var literal : literals) {
                    var single = mismatch(List.of(literal), new OptimizationLevel[]{level});
                    if (single.isPresent()) return single;
                }
                return Optional.of(level + ": " + literals);
            }
            if (literals.size() == 1 && !actual.equals(new Term.Const(Constant.integer(expected)))) {
                return Optional.of(literals.getFirst() + " at " + level);
            }
        }
        return Optional.empty();
    }
}
