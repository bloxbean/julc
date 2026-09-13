package org.julclang.analyzer.cli;

import org.julclang.decompiler.JulcDecompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test to verify the decompiler works from this module's classpath.
 */
class DecompilerSmokeTest {

    @Test
    void decompileDirectly() {
        var result = JulcDecompiler.decompile(TestData.SAMPLE_HEX);
        assertNotNull(result);
        assertNotNull(result.stats());
    }
}
