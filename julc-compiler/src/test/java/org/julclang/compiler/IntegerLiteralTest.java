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

    /** javac rejects each, as too large or for an octal digit 8 or 9, and so must JuLC, with JULC0070. */
    private static final List<Literal> TOO_LARGE = List.of(
            new Literal("long", "2147483648"), new Literal("long", "0x1FFFFFFFF"), new Literal("long", "040000000000"),
            new Literal("long", "9223372036854775808L"), new Literal("long", "0x1_0000_0000_0000_0000L"), new Literal("long", "-(2147483648)"),
            new Literal("int", "08"), new Literal("int", "0_9"), new Literal("long", "09L"));

    @Test
    void fixedLiteralsHaveJavacsValueAtEveryLevel() throws Exception {
        assertEquals(Optional.empty(), mismatch(FIXED, OptimizationLevel.values()));
    }

    @Test
    void aLiteralJavacRejectsIsRejectedWithItsCode() throws Exception {
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
    @Property(tries = 300)
    void generatedLiteralsHaveJavacsValue(@ForAll("batches") List<Literal> batch) throws Exception {
        assertEquals(Optional.empty(), mismatch(batch, new OptimizationLevel[]{OptimizationLevel.BASELINE}));
    }

    @Provide
    Arbitrary<List<Literal>> batches() {
        return Arbitraries.randomValue(IntegerLiteralTest::literal).list().ofMinSize(1).ofMaxSize(25);
    }

    /**
     * A literal javac accepts: either width, any radix, any bit pattern of the width for a non-decimal
     * literal (the top bit set half the time), a length chosen uniformly so that short and full-width
     * values are both common, octal leading zeros, underscores, mixed-case digits and suffix, negation.
     */
    private static Literal literal(java.util.Random random) {
        int bits = random.nextBoolean() ? 32 : 64;
        int radix = new int[]{10, 16, 8, 2}[random.nextInt(4)];
        var signBit = BigInteger.ONE.shiftLeft(bits - 1);
        int length = 1 + random.nextInt(bits);
        var value = new BigInteger(length, random);
        if (radix != 10 && random.nextBoolean()) value = value.setBit(bits - 1);
        boolean negate = random.nextInt(3) == 0;
        if (radix == 10 && value.compareTo(signBit) >= 0) value = negate ? signBit : value.clearBit(bits - 1);
        var digits = new StringBuilder();
        for (char c : value.toString(radix).toCharArray()) {
            digits.append(random.nextBoolean() ? Character.toUpperCase(c) : c);
            if (random.nextInt(5) == 0) digits.append("_".repeat(1 + random.nextInt(2)));
        }
        while (digits.charAt(digits.length() - 1) == '_') digits.setLength(digits.length() - 1);
        String body = digits.toString();
        String prefix = switch (radix) {
            case 16 -> random.nextBoolean() ? "0X" : "0x";
            case 2 -> random.nextBoolean() ? "0B" : "0b";
            case 8 -> "0" + "0".repeat(random.nextInt(2)) + (random.nextBoolean() ? "_" : "");
            default -> "";
        };
        String text = prefix + body + (bits == 64 ? (random.nextBoolean() ? "L" : "l") : "");
        // Negating a non-decimal literal equal to the most negative value overflows in Java (T6).
        boolean mostNegative = radix != 10 && value.equals(signBit);
        if (negate && !mostNegative) text = "-" + text;
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
        var expected = new Term.Const(Constant.integer(jvm(literals).orElseThrow(() -> new AssertionError("javac rejects " + literals))));
        for (var level : levels) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source(literals), "m").program();
            var result = CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of());
            var actual = assertInstanceOf(EvalResult.Success.class, result, level + ": " + literals).resultTerm();
            if (actual.equals(expected)) continue;
            if (literals.size() > 1) {
                // Find the culprit one literal at a time; a single literal is the base case.
                for (var literal : literals) {
                    var single = mismatch(List.of(literal), new OptimizationLevel[]{level});
                    if (single.isPresent()) return single;
                }
            }
            return Optional.of(literals + " at " + level + ": javac " + expected + ", JuLC " + actual
                    + " (each value is encoded as (v + 2^63) * 2^(130 i))");
        }
        return Optional.empty();
    }

    // byte[] initializers: each element as javac assigns an int literal to a byte.

    private static final String BYTES = "public class Lit { public static byte[] m() { return new byte[]{%s}; } }\n";

    @Test
    void byteArrayElementsHaveJavacsValue() throws Exception {
        String elements = "-1, 0x46, 127, -128, 0, 0b1, 017, -0x80, 1_0";
        var dir = Files.createTempDirectory("julc-byte-literal-");
        Files.writeString(dir.resolve("Lit.java"), BYTES.formatted(elements));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, new ByteArrayOutputStream(),
                "-d", dir.toString(), dir.resolve("Lit.java").toString()));
        byte[] expected;
        try (var loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            expected = (byte[]) loader.loadClass("Lit").getMethod("m").invoke(null);
        }
        for (var level : OptimizationLevel.values()) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level))
                    .compileMethod(BYTES.formatted(elements), "m").program();
            var result = CompilerTestVm.pv11("Java").evaluateWithArgs(program, List.of());
            assertEquals(new Term.Const(Constant.byteString(expected)),
                    assertInstanceOf(EvalResult.Success.class, result, level.toString()).resultTerm(), level.toString());
        }
    }

    @Test
    void aByteArrayElementJavacRejectsIsRejectedWithItsCode() throws Exception {
        for (String element : List.of("0xFF", "200", "0x100", "-129", "08")) {
            var dir = Files.createTempDirectory("julc-byte-literal-");
            Files.writeString(dir.resolve("Lit.java"), BYTES.formatted(element));
            assertNotEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, new ByteArrayOutputStream(),
                    "-d", dir.toString(), dir.resolve("Lit.java").toString()), "javac accepts " + element);
            var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                    .compileMethod(BYTES.formatted(element), "m"), element);
            assertTrue(error.diagnostics().stream().anyMatch(d -> "JULC0070".equals(d.code())), element + ": " + error.getMessage());
        }
    }
}
