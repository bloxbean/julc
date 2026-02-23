package com.bloxbean.cardano.julc.analyzer.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared test data for CLI tests.
 */
final class TestData {

    private TestData() {}

    /**
     * Real on-chain script hex (double-CBOR wrapped).
     * Same script used in julc-analysis RealScriptAnalysisTest.
     */
    static final String SAMPLE_HEX;

    static {
        try (var is = TestData.class.getResourceAsStream("/sample-script.hex")) {
            if (is == null) throw new RuntimeException("sample-script.hex not found on classpath");
            SAMPLE_HEX = new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load sample-script.hex", e);
        }
    }
}
