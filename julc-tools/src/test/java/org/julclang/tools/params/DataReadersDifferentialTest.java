package org.julclang.tools.params;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.text.UplcParser;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-077 M2: vm.applyParams' stack-safe readers against julc-core's. {@link CanonicalDataCbor} must read every
 * canonical encoding as {@code PlutusDataCborDecoder} does, and accept nothing else; {@link UplcDataText} must read
 * data notation as {@code UplcParser} does, within the parser's range (constructor indices below 2^31).
 */
class DataReadersDifferentialTest {

    static PlutusData random(Random random, int depth, boolean wideTags) {
        int kind = depth == 0 ? random.nextInt(2) : random.nextInt(5);
        return switch (kind) {
            case 0 -> PlutusData.integer(switch (random.nextInt(3)) {
                case 0 -> BigInteger.valueOf(random.nextInt(60) - 30);
                case 1 -> BigInteger.ONE.shiftLeft(64).subtract(BigInteger.valueOf(random.nextInt(3))).negate();
                default -> new BigInteger(random.nextInt(800), random).subtract(BigInteger.ONE.shiftLeft(400));
            });
            case 1 -> {
                byte[] bytes = new byte[random.nextInt(3) == 0 ? 60 + random.nextInt(140) : random.nextInt(8)];
                random.nextBytes(bytes);
                yield PlutusData.bytes(bytes);
            }
            case 2 -> new PlutusData.ListData(children(random, depth, wideTags));
            case 3 -> {
                var pairs = new ArrayList<PlutusData.Pair>();
                for (int i = random.nextInt(3); i > 0; i--) {
                    pairs.add(new PlutusData.Pair(random(random, depth - 1, wideTags), random(random, depth - 1, wideTags)));
                }
                yield new PlutusData.MapData(pairs);
            }
            default -> new PlutusData.ConstrData(switch (random.nextInt(wideTags ? 5 : 3)) {
                case 0 -> BigInteger.valueOf(random.nextInt(7));
                case 1 -> BigInteger.valueOf(7 + random.nextInt(121));
                case 2 -> BigInteger.valueOf(128 + random.nextInt(Integer.MAX_VALUE - 128));
                case 3 -> BigInteger.ONE.shiftLeft(63);
                default -> BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
            }, children(random, depth, wideTags));
        };
    }

    static List<PlutusData> children(Random random, int depth, boolean wideTags) {
        var items = new ArrayList<PlutusData>();
        for (int i = random.nextInt(4); i > 0; i--) items.add(random(random, depth - 1, wideTags));
        return items;
    }

    @Test
    void canonicalEncodingsReadAsTheCoreDecoderReadsThem() {
        var random = new Random(41);
        for (int i = 0; i < 5_000; i++) {
            PlutusData value = random(random, 5, true);
            byte[] bytes = PlutusDataCborEncoder.encode(value);
            assertEquals(PlutusDataCborDecoder.decode(bytes), CanonicalDataCbor.read(bytes));
        }
    }

    /** Any byte change gives bytes the reader refuses, or another canonical value the core decoder agrees on. */
    @Test
    void mutatedEncodingsAreRefusedOrCanonical() {
        var random = new Random(42);
        int accepted = 0;
        for (int i = 0; i < 20_000; i++) {
            byte[] bytes = PlutusDataCborEncoder.encode(random(random, 3, true));
            byte[] mutated = mutate(bytes, random);
            PlutusData read;
            try {
                read = CanonicalDataCbor.read(mutated);
            } catch (CanonicalDataCbor.NotCanonical e) {
                continue;
            }
            accepted++;
            assertArrayEquals(mutated, PlutusDataCborEncoder.encode(read));
            assertEquals(PlutusDataCborDecoder.decode(mutated), read);
        }
        assertEquals(true, accepted > 0, "some mutations still give canonical bytes");
    }

    static byte[] mutate(byte[] bytes, Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> {
                byte[] copy = bytes.clone();
                copy[random.nextInt(copy.length)] ^= (byte) (1 << random.nextInt(8));
                yield copy;
            }
            case 1 -> {
                byte[] copy = bytes.clone();
                copy[random.nextInt(copy.length)] = (byte) random.nextInt(256);
                yield copy;
            }
            case 2 -> Arrays.copyOf(bytes, random.nextInt(bytes.length + 1));
            default -> {
                byte[] copy = Arrays.copyOf(bytes, bytes.length + 1);
                copy[bytes.length] = (byte) random.nextInt(256);
                yield copy;
            }
        };
    }

    @Test
    void dataNotationReadsAsUplcParserReadsIt() {
        var random = new Random(43);
        for (int i = 0; i < 3_000; i++) {
            PlutusData value = random(random, 5, false);
            String text = text(value, random);
            // UplcParser reads a data constant's value with at most one pair of parentheses around it.
            String constant = text.startsWith("(") ? "(con data " + text + ")" : "(con data (" + text + "))";
            var term = (Term.Const) UplcParser.parseTerm(constant);
            assertEquals(((Constant.DataConst) term.value()).value(), UplcDataText.parse(text), text);
            assertEquals(value, UplcDataText.parse(text), text);
        }
    }

    /** Data notation with random spacing, comments and optional parentheses. */
    static String text(PlutusData value, Random random) {
        String body = switch (value) {
            case PlutusData.IntData i -> "I " + i.value();
            case PlutusData.BytesData b -> "B #" + HexFormat.of().formatHex(b.value());
            case PlutusData.ListData l -> "List [" + join(l.items(), random) + "]";
            case PlutusData.ConstrData c -> "Constr " + c.constructorTag() + " [" + join(c.fields(), random) + "]";
            case PlutusData.MapData m -> {
                var pairs = new ArrayList<String>();
                for (var pair : m.entries()) pairs.add("(" + text(pair.key(), random) + "," + space(random)
                        + text(pair.value(), random) + ")");
                yield "Map [" + String.join("," + space(random), pairs) + "]";
            }
        };
        return random.nextInt(5) == 0 ? "(" + space(random) + body + space(random) + ")" : body;
    }

    static String join(List<PlutusData> items, Random random) {
        var parts = new ArrayList<String>();
        for (PlutusData item : items) parts.add(space(random) + text(item, random) + space(random));
        return String.join(",", parts);
    }

    static String space(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> "";
            case 1 -> " ";
            case 2 -> "\n  ";
            default -> " -- note (with ] parens [\n";
        };
    }
}
