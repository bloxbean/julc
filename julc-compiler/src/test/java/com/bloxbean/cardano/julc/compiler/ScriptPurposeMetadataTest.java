package com.bloxbean.cardano.julc.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptPurposeMetadataTest {

    @Test
    void textEnvelopeTypeUsesCurrentPlutusTarget() {
        assertEquals("PlutusScriptV3", ScriptPurposeMetadata.textEnvelopeType());
    }

    @Test
    void jsonPurposeMapsAllCompilerPurposes() {
        assertEquals("spending", ScriptPurposeMetadata.jsonPurpose(JulcCompiler.ScriptPurpose.SPENDING));
        assertEquals("minting", ScriptPurposeMetadata.jsonPurpose(JulcCompiler.ScriptPurpose.MINTING));
        assertEquals("withdraw", ScriptPurposeMetadata.jsonPurpose(JulcCompiler.ScriptPurpose.WITHDRAW));
        assertEquals("certifying", ScriptPurposeMetadata.jsonPurpose(JulcCompiler.ScriptPurpose.CERTIFYING));
        assertEquals("voting", ScriptPurposeMetadata.jsonPurpose(JulcCompiler.ScriptPurpose.VOTING));
        assertEquals("proposing", ScriptPurposeMetadata.jsonPurpose(JulcCompiler.ScriptPurpose.PROPOSING));
        assertEquals("multi", ScriptPurposeMetadata.jsonPurpose(JulcCompiler.ScriptPurpose.MULTI));
    }
}
