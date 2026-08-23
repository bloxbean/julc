package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicDslSchemaOneTest {
    @Test
    void canonicalEnvelopeHasExplicitFormatAndSchemaOne() {
        var schema = DslPropertySet.schema1(
                com.bloxbean.cardano.julc.verification.dsl.ir.DslPurpose.SPENDING,
                "0".repeat(64),
                VerificationDsl.property("stable", DslDomain.NONE,
                        VerificationDsl.bool(true)));

        String canonical = PropertyIrCodec.canonicalJson(schema);

        assertEquals(DslPropertySet.FORMAT, schema.format());
        assertEquals(1, schema.schemaVersion());
        assertTrue(canonical.startsWith("{\"contractSchemaSha256\":"));
        assertTrue(canonical.contains("\"format\":\"julc.verification.dsl\""));
        assertTrue(canonical.endsWith("\"schemaVersion\":1}"));
    }

    @Test
    void unreleasedMilestoneSchemasAreNotReadableAsPublicSchemaOne() {
        String legacy = """
                {"schemaVersion":10,"purpose":"SPENDING",\
                "contractSchemaSha256":"%s",\
                "properties":[{"id":"legacy","domain":"NONE",\
                "expression":{"op":"bool-literal","value":true}}]}
                """.formatted("0".repeat(64)).replace("\\\n", "");
        String wrongFormat = legacy
                .replace("\"schemaVersion\":10",
                        "\"format\":\"another.dsl\",\"schemaVersion\":1");

        assertThrows(IOException.class,
                () -> PropertyIrCodec.readCanonical(legacy, 1_000_000));
        assertThrows(IOException.class,
                () -> PropertyIrCodec.readCanonical(wrongFormat, 1_000_000));
    }
}
