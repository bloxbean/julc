package org.julclang.compiler;

import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #299 changes only diamond tuples and raw tuples of non-Data components. An explicit constructor assigned to a raw
 * local (review F712) keeps B0's bytes: FLAT SHA-256 prefixes captured at B0 (`42e7bd51`).
 */
class DiamondTupleBytesTest {

    @Test
    void explicitConstructorAtARawDestinationKeepsB0sBytes() throws Exception {
        String source = """
                import java.math.BigInteger;
                import org.julclang.core.types.Tuple2;
                class P {
                    static BigInteger m(BigInteger x, BigInteger y) {
                        Tuple2 raw = new Tuple2<BigInteger, BigInteger>(x, y);
                        Tuple2<BigInteger, BigInteger> t = raw;
                        return t.second();
                    }
                }
                """;
        var b0 = Map.of(OptimizationLevel.NONE, "1b1bbe1f6fcbcbaa", OptimizationLevel.BASELINE, "eee430cff383e62b",
                OptimizationLevel.PV11_SAFE, "eee430cff383e62b", OptimizationLevel.PV11_COSTED, "eee430cff383e62b");
        for (var level : OptimizationLevel.values()) {
            var bytes = UplcFlatEncoder.encodeProgram(new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "m").program());
            var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
            assertEquals(b0.get(level), sha, level.toString());
        }
    }
}
