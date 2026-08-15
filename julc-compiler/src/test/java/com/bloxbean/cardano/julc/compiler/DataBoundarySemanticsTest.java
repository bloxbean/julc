package com.bloxbean.cardano.julc.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataBoundarySemanticsTest {
    @Test
    void embedsAndClassifiesStrictSemanticsInCompilerIdentity() {
        assertEquals("1.2.3+boundary.strict-data-v1",
                DataBoundarySemantics.compilerIdentityVersion("1.2.3"));
        assertEquals("1.2.3+build.boundary.strict-data-v1",
                DataBoundarySemantics.compilerIdentityVersion("1.2.3+build"));
        assertEquals(DataBoundarySemantics.STRICT_V1,
                DataBoundarySemantics.fromCompilerIdentity(
                        "julc", "1.2.3+boundary.strict-data-v1"));
        assertEquals(DataBoundarySemantics.LEGACY_V0,
                DataBoundarySemantics.fromCompilerIdentity("julc", "1.2.3"));
        assertEquals(DataBoundarySemantics.EXTERNAL_UNCLASSIFIED,
                DataBoundarySemantics.fromCompilerIdentity(
                        "another-compiler", "1.2.3+boundary.strict-data-v1"));
        assertThrows(IllegalArgumentException.class,
                () -> DataBoundarySemantics.fromCompilerIdentity(
                        "julc", "1.2.3+boundary.strict-data-v10"));
        assertThrows(IllegalArgumentException.class,
                () -> DataBoundarySemantics.fromCompilerIdentity(
                        "julc", "1.2.3+boundary.strict-data-v2"));
    }
}
