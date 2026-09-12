package org.julclang.core.cbor;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MapDecodingFidelityTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final PlutusData DUPLICATES = PlutusData.map(
            new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(10)),
            new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(20)));

    @ParameterizedTest
    @ValueSource(strings = {"a2010a0114", "bf010a0114ff", "b802010a0114",
            "b90002010a0114", "ba00000002010a0114", "bb0000000000000002010a0114",
            "a2010a180114", "bf18010a0114ff"})
    void preservesDuplicateAssociations(String hex) {
        assertEquals(DUPLICATES, PlutusDataCborDecoder.decode(HEX.parseHex(hex)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a2010a0114", "81a2010a0114", "9fbf010a0114ffff",
            "d87981a2010a0114", "d86682188081a2010a0114", "a1a2010a0114a2010a0114"})
    void canonicalFlatReadBackPreservesData(String hex) {
        // Build expectations without routing duplicate maps through the defective decoder.
        PlutusData data = switch (hex) {
            case "a2010a0114" -> DUPLICATES;
            case "81a2010a0114", "9fbf010a0114ffff" -> PlutusData.list(DUPLICATES);
            case "d87981a2010a0114" -> PlutusData.constr(0, DUPLICATES);
            case "d86682188081a2010a0114" -> PlutusData.constr(128, DUPLICATES);
            default -> PlutusData.map(new PlutusData.Pair(DUPLICATES, DUPLICATES));
        };
        assertEquals(data, PlutusDataCborDecoder.decode(HEX.parseHex(hex)));
        var program = Program.plutusV3(Term.const_(Constant.data(data)));
        byte[] flat = UplcFlatEncoder.encodeProgram(program);
        var decoded = UplcFlatDecoder.decodeProgram(flat);
        assertEquals(program, decoded);
        assertArrayEquals(flat, UplcFlatEncoder.encodeProgram(decoded));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a1", "a101", "a2010a01", "bf", "bf01", "bf01ff", "bf010a",
            "a1ff00", "a100ff", "bc", "bd", "be", "b8", "b901", "ba0000", "bb00000000",
            "bbffffffffffffffff", "bb8000000000000000", "ba7fffffff", "a18101",
            "a1f500", "a1f600", "bf01a1ff00ff"})
    void rejectsMalformedMaps(String hex) {
        assertThrows(CborDecodingException.class,
                () -> PlutusDataCborDecoder.decode(HEX.parseHex(hex)));
    }

    @Test
    void rejectsEveryTruncatedPrefix() {
        byte[] bytes = HEX.parseHex("bfa2010a0114d8799f0102ff03bf04050406ffff");
        assertDoesNotThrow(() -> PlutusDataCborDecoder.decode(bytes));
        for (int length = 0; length < bytes.length; length++) {
            byte[] prefix = Arrays.copyOf(bytes, length);
            assertThrows(CborDecodingException.class, () -> PlutusDataCborDecoder.decode(prefix),
                    "prefix length " + length);
        }
    }

    @Test
    void compoundAndDuplicateContainingMapKeysRemainDistinctAssociations() {
        var other = PlutusData.map(new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(20)));
        var data = PlutusData.map(
                new PlutusData.Pair(DUPLICATES, PlutusData.integer(1)),
                new PlutusData.Pair(other, PlutusData.integer(2)),
                new PlutusData.Pair(DUPLICATES, PlutusData.integer(3)));
        assertEquals(data, PlutusDataCborDecoder.decode(PlutusDataCborEncoder.encode(data)));
        var lists = PlutusData.map(
                new PlutusData.Pair(PlutusData.list(DUPLICATES), PlutusData.constr(0, DUPLICATES)),
                new PlutusData.Pair(PlutusData.list(DUPLICATES), PlutusData.constr(1, DUPLICATES)));
        assertEquals(lists, PlutusDataCborDecoder.decode(PlutusDataCborEncoder.encode(lists)));
    }

    @Test
    void mapHeaderBoundariesPreserveEveryEntry() {
        for (int size : new int[]{0, 1, 23, 24, 255, 256, 65535, 65536}) {
            var entries = new ArrayList<PlutusData.Pair>();
            for (int i = 0; i < size; i++) {
                entries.add(new PlutusData.Pair(PlutusData.integer(i % 3), PlutusData.integer(i)));
            }
            var data = new PlutusData.MapData(entries);
            assertEquals(data, PlutusDataCborDecoder.decode(PlutusDataCborEncoder.encode(data)),
                    "map size " + size);
        }
    }

    @Test
    void generatedNestedDataRoundTrips() {
        var random = new Random(370054);
        for (int i = 0; i < 500; i++) {
            var data = generatedData(random, 4);
            assertEquals(data, PlutusDataCborDecoder.decode(PlutusDataCborEncoder.encode(data)),
                    "generated case " + i);
        }
    }

    private static PlutusData generatedData(Random random, int depth) {
        if (depth == 0) return PlutusData.integer(random.nextLong());
        return switch (random.nextInt(5)) {
            case 0 -> PlutusData.constr(random.nextInt(200), generatedData(random, depth - 1));
            case 1 -> PlutusData.list(generatedData(random, depth - 1), generatedData(random, depth - 1));
            case 2 -> {
                var key = generatedData(random, depth - 1);
                yield PlutusData.map(new PlutusData.Pair(key, generatedData(random, depth - 1)),
                        new PlutusData.Pair(key, generatedData(random, depth - 1)));
            }
            case 3 -> {
                byte[] bytes = new byte[random.nextInt(150)];
                random.nextBytes(bytes);
                yield PlutusData.bytes(bytes);
            }
            default -> DUPLICATES;
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"a0", "bfff", "a203181e010a", "bf03181e010aff", "a1019f0203ff",
            "00", "20", "1bffffffffffffffff", "3bffffffffffffffff", "c249010000000000000000",
            "c349010000000000000000", "5f41014102ff", "d8799fa10304ff", "d86682188080",
            "d866821bffffffffffffffff80", "9f01029f03ffff", "c240", "c34100", "d903e8a10102",
            "0102"})
    void retainsLegacyValuesForDuplicateFreeInputs(String hex) throws CborException {
        byte[] bytes = HEX.parseHex(hex);
        var legacy = PlutusDataCborDecoder.fromDataItem(CborDecoder.decode(bytes).getFirst());
        assertEquals(legacy, PlutusDataCborDecoder.decode(bytes));
    }

    @Test
    void malformedTrailingCborStillFails() {
        assertThrows(CborDecodingException.class,
                () -> PlutusDataCborDecoder.decode(HEX.parseHex("00a1")));
    }
}
