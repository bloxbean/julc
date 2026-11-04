package org.julclang.difftest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.julclang.difftest.Adr068CallsHarnessTest.assertAgree;
import static org.julclang.difftest.Adr068CallsHarnessTest.integer;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR-068 B4: {@code String.length()} counts UTF-16 code units, as Java does. The JVM is the model. A script's string is
 * Unicode text, so it holds no unpaired surrogate, and each generated string is a sequence of scalars of every UTF-8 width.
 */
class Adr068StringLengthTest {

    private static final String LENGTH = "public static long m(String s) { return s.length(); }";

    private static PlutusData text(String value) {
        return new PlutusData.BytesData(value.getBytes(StandardCharsets.UTF_8));
    }

    @Provide
    Arbitrary<String> scalars() {
        var oneByte = Arbitraries.integers().between(0, 0x7F);
        var twoBytes = Arbitraries.integers().between(0x80, 0x7FF);
        var threeBytes = Arbitraries.oneOf(Arbitraries.integers().between(0x800, 0xD7FF), Arbitraries.integers().between(0xE000, 0xFFFF));
        var fourBytes = Arbitraries.integers().between(0x10000, 0x10FFFF);
        var edges = Arbitraries.of(0, 0x7F, 0x80, 0x7FF, 0x800, 0xD7FF, 0xE000, 0xFFFF, 0x10000, 0x10FFFF);
        return Arbitraries.oneOf(oneByte, twoBytes, threeBytes, fourBytes, edges).list().ofMaxSize(12)
                .map(points -> points.stream().collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString());
    }

    @Property(tries = 200)
    void lengthIsJavas(@ForAll("scalars") String value) {
        assertAgree(LENGTH, text(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "\u0000", "\u007F", "\u0080", "é", "߿", "ࠀ", "€", "퟿", "", "￿", "𐀀", "😀", "􏿿",
            "a😀é€", "😀😀"})
    void eachWidth(String value) {
        assertAgree(LENGTH, text(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"abc\"", "\"é\"", "\"€\"", "\"😀\"", "\"a😀é€\"", "\"\\uD83D\\uDE00\""})
    void aConstantIsJavas(String literal) {
        assertAgree("public static long m() { return " + literal + ".length(); }");
    }

    /** The operand runs once, and in its place, and a failure in it fails. */
    @Test
    void theReceiverRunsOnce() {
        assertAgree("static String s(String t, long a) { saidL(\"S\", a); return t; }"
                + " public static long m(String t, long a) { return saidL(\"L\", a) + s(t, a).length() + saidL(\"R\", a); }", text("é"), integer(1));
        assertAgree("static String s(String t) { Builtins.error(); return t; } public static long m(String t) { return s(t).length(); }", text("é"));
    }

    @Test
    void theOtherStringMethodsAgreeWhereTheyCompile() {
        for (String[] input : new String[][]{{"é😀", "😀"}, {"a", "a"}, {"", ""}, {"é", "é"}}) {
            assertAgree("public static boolean m(String a, String b) { return a.equals(b); }", text(input[0]), text(input[1]));
            assertAgree("public static String m(String a, String b) { return a + b; }", text(input[0]), text(input[1]));
            assertAgree("public static long m(String a, String b) { return (a + b).length(); }", text(input[0]), text(input[1]));
            assertAgree("public static byte[] m(String a, String b) { return a.getBytes(); }", text(input[0]), text(input[1]));
        }
    }

    /** No {@code String} method of Java reads another way here: the rest stop, so none is lowered to a wrong count. */
    @ParameterizedTest
    @ValueSource(strings = {"boolean:a.isEmpty()", "String:a.concat(a)", "boolean:a.isBlank()", "int:a.indexOf(a)", "String:a.substring(1)", "int:(int) a.charAt(0)",
            "int:a.codePointAt(0)", "int:a.codePointCount(0, 1)", "int:a.compareTo(a)", "int:a.hashCode()", "boolean:a.equalsIgnoreCase(a)",
            "byte[]:a.getBytes(\"UTF-16\")"})
    void theRestStop(String row) {
        var parts = row.split(":", 2);
        var source = "public class Case { public static " + parts[0] + " m(String a) throws Exception { return " + parts[1] + "; } }";
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions());
        assertNotNull(assertThrows(CompilerException.class, () -> compiler.compileMethod(source, "m"), row));
    }
}
