package org.julclang.wasm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-068 M1: {@link JavacGateProbe} on the JVM, whose lines the Web Image must reproduce. */
class JavacGateProbeTest {

    @Test
    void probeAcceptsJavaAndRejectsDialect() throws Exception {
        var lines = JavacGateProbe.run();
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("environment supported=true platform=c45dd2df"), lines.get(0));
        assertTrue(lines.get(1).startsWith("accepted FeeGate "), lines.get(1));
        assertTrue(lines.get(2).startsWith("accepted OwnerGate "), lines.get(2));
        assertTrue(lines.get(3).startsWith("rejected JULC0085 FeeGate.java:13:"), lines.get(3));
        assertTrue(lines.get(3).endsWith("(compiler.err.operator.cant.be.applied.1)"), lines.get(3));
    }
}
