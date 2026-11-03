package org.julclang.compiler.frontend.javac.lower;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.stdlib.lib.ByteStringLib;
import org.julclang.stdlib.lib.MathLib;
import org.julclang.testkit.CallResult;
import org.julclang.testkit.JulcEval;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-068 D8: stdlib library methods, each compiled with its unit as the root and the class path's library sources as the
 * pool, against the method's own Java body on the JVM, on random inputs, on both frontends.
 */
class StdlibLibrariesPropertyTest {

    private static final List<JulcEval> MATH = frontends("MathLib");
    private static final List<JulcEval> BYTES = frontends("ByteStringLib");

    private static List<JulcEval> frontends(String library) {
        String source = JavacLibrariesTest.stdlibSource(library);
        return List.of(JulcEval.forSource(source), JulcEval.forSource(source).frontend(CompilerFrontend.JAVAC));
    }

    private static <T> void agrees(List<JulcEval> evals, T expected, Function<CallResult, T> read, String method, Object... arguments) {
        for (var eval : evals) assertEquals(expected, read.apply(eval.call(method, arguments)), method);
    }

    @Property(tries = 20)
    void mathOfOneInteger(@ForAll @BigRange(min = "-1000000000000000000000", max = "1000000000000000000000") BigInteger x) {
        agrees(MATH, MathLib.abs(x), CallResult::asInteger, "abs", x);
        agrees(MATH, MathLib.sign(x), CallResult::asInteger, "sign", x);
    }

    @Property(tries = 20)
    void mathOfTwoIntegers(@ForAll @BigRange(min = "-1000000000000", max = "1000000000000") BigInteger a,
                           @ForAll @BigRange(min = "-1000000000000", max = "1000000000000") BigInteger b) {
        agrees(MATH, MathLib.max(a, b), CallResult::asInteger, "max", a, b);
        agrees(MATH, MathLib.min(a, b), CallResult::asInteger, "min", a, b);
        if (b.signum() != 0) {
            agrees(MATH, MathLib.floorDiv(a, b), CallResult::asInteger, "floorDiv", a, b);
            agrees(MATH, MathLib.floorMod(a, b), CallResult::asInteger, "floorMod", a, b);
        }
    }

    @Property(tries = 15)
    void powers(@ForAll @BigRange(min = "-50", max = "50") BigInteger base, @ForAll @IntRange(min = 0, max = 12) int exp) {
        agrees(MATH, MathLib.pow(base, BigInteger.valueOf(exp)), CallResult::asInteger, "pow", base, BigInteger.valueOf(exp));
    }

    @Property(tries = 20)
    void bytes(@ForAll @Size(max = 12) byte[] a, @ForAll @Size(max = 12) byte[] b, @ForAll @IntRange(min = 0, max = 14) int n) {
        agrees(BYTES, ByteStringLib.length(a), CallResult::asLong, "length", a);
        agrees(BYTES, ByteStringLib.equals(a, b), CallResult::asBoolean, "equals", a, b);
        agrees(BYTES, ByteStringLib.lessThan(a, b), CallResult::asBoolean, "lessThan", a, b);
        for (var eval : BYTES) {
            assertArrayEquals(ByteStringLib.append(a, b), eval.call("append", a, b).asByteString());
            assertArrayEquals(ByteStringLib.take(a, n), eval.call("take", a, (long) n).asByteString());
            assertArrayEquals(ByteStringLib.drop(a, n), eval.call("drop", a, (long) n).asByteString());
            assertArrayEquals(ByteStringLib.toHex(a), eval.call("toHex", a).asByteString());
        }
    }
}
