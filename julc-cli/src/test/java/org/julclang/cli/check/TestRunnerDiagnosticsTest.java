package org.julclang.cli.check;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-068 M5 (review F5): {@code julc check} reports a rejection's code, position and fix, not its words alone. */
class TestRunnerDiagnosticsTest {

    @Test
    void aRejectionShowsItsCodeAndFix() {
        String source = """
                class T {
                    static boolean m() { byte[] a = new byte[]{1}; return a.equals(new byte[]{1}); }
                }
                """;
        var result = new TestRunner(Map.of()).run(new TestDiscovery.TestMethod("T.java", "T", "m", source));
        assertFalse(result.passed());
        assertTrue(result.error().contains("JULC0087"), result.error());
        assertTrue(result.error().contains("Builtins.equalsByteString(a, b)"), result.error());
    }
}
