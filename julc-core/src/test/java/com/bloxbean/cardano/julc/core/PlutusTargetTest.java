package com.bloxbean.cardano.julc.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlutusTargetTest {

    @Test
    void currentTargetIsPlutusV3() {
        assertEquals(PlutusTarget.V3, PlutusTarget.CURRENT);
        assertEquals("v3", PlutusTarget.CURRENT.languageVersion());
        assertEquals("PlutusScriptV3", PlutusTarget.CURRENT.textEnvelopeType());

        var program = PlutusTarget.CURRENT.program(Term.const_(Constant.unit()));
        assertEquals("1.1.0", program.versionString());
    }
}
