package org.julclang.tools.uplc;

import com.bloxbean.cardano.client.plutus.spec.serializers.PlutusDataJsonConverter;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.tools.model.MockTransaction.DataInput;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Characterizes the legacy boundary before ADR-057 replaces its JSON parser. */
class DataInputCompatibilityTest {

    @Test
    void jsonConstructorTagDoesNotWrapAtSignedLongBoundary() {
        var data = DataInputs.parse(new DataInput("json",
                "{\"constructor\":18446744073709551615,\"fields\":[]}"));
        var constructor = assertInstanceOf(PlutusData.ConstrData.class, data);
        assertEquals(new BigInteger("18446744073709551615"), constructor.constructorTag());
    }

    @Test
    void legacyJsonIntegerRemainsExactAboveUnsignedLong() {
        var value = BigInteger.ONE.shiftLeft(80).add(BigInteger.ONE);
        var data = DataInputs.parse(new DataInput("json", "{\"int\":" + value + "}"));
        assertEquals(PlutusData.integer(value), data);
    }

    @Test
    void validJsonCorpusMatchesPinnedClientLibrary() throws Exception {
        var corpus = List.of("{\"int\":-1}", "{\"int\":18446744073709551617}",
                "{\"bytes\":\"aAbb00\"}", "{\"list\":[]}", "{\"map\":[]}",
                "{\"constructor\":9223372036854775807,\"fields\":[{\"int\":12}]}",
                "{\"map\":[{\"k\":{\"int\":1},\"v\":{\"int\":2}},"
                        + "{\"k\":{\"int\":0},\"v\":{\"int\":3}},"
                        + "{\"k\":{\"int\":1},\"v\":{\"int\":4}}]}");
        for (String json : corpus) assertEquivalent(json);
        var random = new Random(57);
        for (int i = 0; i < 200; i++) assertEquivalent(dataJson(random, 4));
    }

    @Test
    void correctedTagsSurviveCborRoundTrip() {
        for (String tag : List.of("9007199254740993", "9223372036854775808", "18446744073709551615")) {
            var data = DataInputs.parse(new DataInput("json", "{\"constructor\":" + tag + ",\"fields\":[]}"));
            assertEquals(new BigInteger(tag), ((PlutusData.ConstrData) data).constructorTag());
            assertEquals(data, PlutusDataCborDecoder.decode(PlutusDataCborEncoder.encode(data)));
        }
    }

    @Test
    void invalidShapesFailInsteadOfInventingData() {
        for (String json : List.of("{}", "[]", "{\"list\":{}}", "{\"map\":[{}]}",
                "{\"constructor\":1}", "{\"bytes\":\"zz\"}")) {
            assertThrows(IllegalArgumentException.class, () -> DataInputs.parse(new DataInput("json", json)), json);
        }
    }

    private static void assertEquivalent(String json) throws Exception {
        assertEquals(PlutusDataAdapter.fromClientLib(PlutusDataJsonConverter.toPlutusData(json)),
                PlutusDataJson.parse(json), json);
    }

    private static String dataJson(Random random, int depth) {
        return switch (depth == 0 ? random.nextInt(2) : random.nextInt(5)) {
            case 0 -> "{\"int\":" + new BigInteger(100, random).subtract(BigInteger.ONE.shiftLeft(99)) + "}";
            case 1 -> "{\"bytes\":\"00aaff\"}";
            case 2 -> "{\"list\":[" + dataJson(random, depth - 1) + "," + dataJson(random, depth - 1) + "]}";
            case 3 -> "{\"map\":[{\"k\":" + dataJson(random, depth - 1)
                    + ",\"v\":" + dataJson(random, depth - 1) + "}]}";
            default -> "{\"constructor\":" + random.nextInt(1000) + ",\"fields\":[" + dataJson(random, depth - 1) + "]}";
        };
    }
}
