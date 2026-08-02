package com.bloxbean.cardano.julc.vm.java.cost;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PinnedPv11CostModelTest {

    @Test
    void defaultsMatchCardanoNode1101PlutusV3Pv11Exactly() throws IOException {
        var stream = getClass().getResourceAsStream(
                "/cost-model/cardano-node-11.0.1-plutus-v3-pv11.params");
        assertNotNull(stream);

        long[] expected;
        try (var reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            expected = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .mapToLong(Long::parseLong)
                    .toArray();
        }

        assertArrayEquals(expected, CostModelParser.defaultToFlatArray(11));
    }
}
