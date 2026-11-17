package org.julclang.core.cbor;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.FlatDecodeLimits;
import org.julclang.core.flat.FlatReader;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-077 M2 moved {@link PlutusDataCborEncoder} to an explicit stack. Its bytes must equal those of the former
 * recursive encoder ({@link RecursivePlutusDataCborEncoder}, kept unchanged in test code) for every value, and it must
 * encode values far deeper than any Java stack would allow.
 */
class PlutusDataCborEncoderDifferentialTest {

    static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

    static PlutusData random(Random random, int depth) {
        int kind = depth == 0 ? random.nextInt(2) : random.nextInt(5);
        return switch (kind) {
            case 0 -> {
                // Small, 64-bit edge and bignum integers of both signs, including chunked (> 64 byte) bignums.
                BigInteger value = switch (random.nextInt(5)) {
                    case 0 -> BigInteger.valueOf(random.nextInt(50) - 25);
                    case 1 -> TWO_64.subtract(BigInteger.valueOf(random.nextInt(3)));
                    case 2 -> TWO_64.negate().add(BigInteger.valueOf(random.nextInt(3)));
                    case 3 -> new BigInteger(random.nextInt(700), random);
                    default -> new BigInteger(random.nextInt(700), random).negate();
                };
                yield PlutusData.integer(value);
            }
            case 1 -> {
                byte[] bytes = new byte[random.nextInt(4) == 0 ? 60 + random.nextInt(150) : random.nextInt(10)];
                random.nextBytes(bytes);
                yield PlutusData.bytes(bytes);
            }
            case 2 -> new PlutusData.ListData(children(random, depth));
            case 3 -> {
                var pairs = new ArrayList<PlutusData.Pair>();
                PlutusData key = random(random, 0);
                for (int i = random.nextInt(4); i > 0; i--) {
                    if (random.nextBoolean()) key = random(random, depth - 1);
                    pairs.add(new PlutusData.Pair(key, random(random, depth - 1)));
                }
                yield new PlutusData.MapData(pairs);
            }
            default -> {
                BigInteger tag = switch (random.nextInt(6)) {
                    case 0 -> BigInteger.valueOf(random.nextInt(7));
                    case 1 -> BigInteger.valueOf(7 + random.nextInt(121));
                    case 2 -> BigInteger.valueOf(128 + random.nextInt(100_000));
                    case 3 -> BigInteger.ONE.shiftLeft(63).add(BigInteger.valueOf(random.nextInt(3) - 1));
                    case 4 -> TWO_64.subtract(BigInteger.ONE);
                    default -> BigInteger.valueOf(random.nextLong() & Long.MAX_VALUE);
                };
                yield new PlutusData.ConstrData(tag, children(random, depth));
            }
        };
    }

    static List<PlutusData> children(Random random, int depth) {
        var items = new ArrayList<PlutusData>();
        for (int i = random.nextInt(4); i > 0; i--) items.add(random(random, depth - 1));
        return items;
    }

    @Test
    void bytesEqualTheRecursiveEncoderOnRandomValues() {
        var random = new Random(77_002);
        for (int i = 0; i < 20_000; i++) {
            PlutusData value = random(random, 1 + random.nextInt(6));
            assertArrayEquals(RecursivePlutusDataCborEncoder.encode(value), PlutusDataCborEncoder.encode(value),
                    () -> value.toString());
        }
    }

    @Test
    void bytesEqualTheRecursiveEncoderOnModeratelyDeepValues() {
        var random = new Random(3);
        for (int i = 0; i < 50; i++) {
            PlutusData value = random(random, 2);
            for (int depth = 0; depth < 300; depth++) {
                value = switch (random.nextInt(3)) {
                    case 0 -> new PlutusData.ListData(List.of(value, PlutusData.integer(depth)));
                    case 1 -> new PlutusData.MapData(List.of(new PlutusData.Pair(PlutusData.integer(depth), value)));
                    default -> new PlutusData.ConstrData(BigInteger.valueOf(random.nextInt(200)), List.of(value));
                };
            }
            PlutusData finalValue = value;
            assertArrayEquals(RecursivePlutusDataCborEncoder.encode(finalValue), PlutusDataCborEncoder.encode(finalValue));
        }
    }

    @Test
    void deepValuesNeedNoJavaStack() {
        int depth = 200_000;
        PlutusData value = PlutusData.integer(0);
        for (int i = 0; i < depth; i++) value = new PlutusData.ListData(List.of(value));
        byte[] bytes = PlutusDataCborEncoder.encode(value);
        assertEquals(2 * depth + 1, bytes.length);
        for (int i = 0; i < depth; i++) {
            assertEquals((byte) 0x9f, bytes[i]);
            assertEquals((byte) 0xff, bytes[depth + 1 + i]);
        }
        assertEquals(0, bytes[depth]);
        // A FLAT data constant goes through the same encoder.
        var program = new Program(1, 1, 0, new Term.Const(Constant.data(value)));
        UplcFlatEncoder.encodeProgram(program);
    }

    @Test
    void flatDecoderUsesTheGivenDataReader() {
        var program = new Program(1, 1, 0, new Term.Const(Constant.data(PlutusData.integer(42))));
        byte[] flat = UplcFlatEncoder.encodeProgram(program);
        var seen = new ArrayList<String>();
        var decoded = new UplcFlatDecoder(new FlatReader(flat), FlatDecodeLimits.UNBOUNDED, cbor -> {
            seen.add(HexFormat.of().formatHex(cbor));
            return PlutusData.integer(7);
        }).readProgram();
        assertEquals(List.of("182a"), seen);
        assertEquals(new Term.Const(Constant.data(PlutusData.integer(7))), decoded.term());
        // The default reader is unchanged.
        assertEquals(program.term(), UplcFlatDecoder.decodeProgram(flat).term());
    }
}
