package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.BuiltinSemanticsVariant;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PinnedPv11CostModelTest {

    @Test
    void v3DefaultsMatchAdr033PinnedProfiles() throws IOException {
        for (var profile : List.of(
                new PinnedProfile("v3-pv10-C-f92b7d7d8.txt", 10),
                new PinnedProfile("v3-pv11-E-f92b7d7d8.txt", 11))) {
            assertArrayEquals(
                    readParams("/costmodels/" + profile.resource()),
                    CostModelParser.defaultToFlatArray(profile.protocolMajor()),
                    profile.resource());
        }
    }

    @Test
    void defaultsMatchCardanoNode1101PlutusV3Pv11Exactly() throws IOException {
        var expected = readParams(
                "/cost-model/cardano-node-11.0.1-plutus-v3-pv11.params");

        assertArrayEquals(expected, CostModelParser.defaultToFlatArray(11));

        assertPinnedDivisionShapes(
                DefaultCostModel.defaultBuiltinCostModel(BuiltinSemanticsVariant.E));
        assertPinnedDivisionShapes(
                CostModelParser.parse(expected, 11).builtinCostModel());
    }

    private long[] readParams(String resource) throws IOException {
        var stream = getClass().getResourceAsStream(resource);
        assertNotNull(stream, resource);
        try (var reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .mapToLong(Long::parseLong)
                    .toArray();
        }
    }

    private static void assertPinnedDivisionShapes(BuiltinCostModel model) {
        var divide = model.get(DefaultFun.DivideInteger).cpu();
        var mod = model.get(DefaultFun.ModInteger).cpu();
        var quotient = model.get(DefaultFun.QuotientInteger).cpu();
        var remainder = model.get(DefaultFun.RemainderInteger).cpu();

        assertInstanceOf(CostFunction.AboveAndBelowDiagonal.class, divide);
        assertInstanceOf(CostFunction.AboveAndBelowDiagonal.class, mod);
        assertInstanceOf(CostFunction.ConstAboveDiagonal.class, quotient);
        assertInstanceOf(CostFunction.ConstAboveDiagonal.class, remainder);

        // Wide size gaps expose the shape difference hidden by the 85848
        // minimum clamp on small/near-diagonal examples.
        assertEquals(187_016, divide.apply(1, 16));
        assertEquals(187_016, mod.apply(1, 16));
        assertEquals(85_848, quotient.apply(1, 16));
        assertEquals(85_848, remainder.apply(1, 16));
    }

    private record PinnedProfile(String resource, int protocolMajor) {}
}
