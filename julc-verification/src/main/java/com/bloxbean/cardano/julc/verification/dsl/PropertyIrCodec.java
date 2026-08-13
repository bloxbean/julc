package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Strict canonical serialization for worker output and certificate hashing. */
public final class PropertyIrCodec {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private PropertyIrCodec() { }

    public static byte[] canonicalBytes(DslPropertySet properties) {
        try {
            return JSON.writeValueAsBytes(properties);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize property IR", e);
        }
    }

    public static String canonicalJson(DslPropertySet properties) {
        return new String(canonicalBytes(properties), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void write(Path output, DslPropertySet properties) throws IOException {
        Files.write(output, canonicalBytes(properties));
    }

    public static DslPropertySet read(Path input, long maxBytes) throws IOException {
        long size = Files.size(input);
        if (size <= 0 || size > maxBytes) {
            throw new IOException("Property worker output size " + size
                    + " is outside 1.." + maxBytes + " bytes");
        }
        return JSON.readValue(Files.readAllBytes(input), DslPropertySet.class);
    }
}
