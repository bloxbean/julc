package com.bloxbean.cardano.julc.verification.capability;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Loads the authority audit bundled with the verification module. */
public final class ReviewedRawDataAdapterAudits {
    public static final String V1_RESOURCE =
            "/com/bloxbean/cardano/julc/verification/reviewed-raw-data-adapters-v1.json";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private ReviewedRawDataAdapterAudits() {
    }

    public static ReviewedRawDataAdapterAudit v1() {
        try (var input = ReviewedRawDataAdapterAudits.class.getResourceAsStream(V1_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing reviewed-adapter audit " + V1_RESOURCE);
            }
            return JSON.readValue(input, ReviewedRawDataAdapterAudit.class);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid reviewed-adapter audit: " + e.getMessage(), e);
        }
    }

    public static byte[] v1Bytes() {
        try (var input = ReviewedRawDataAdapterAudits.class.getResourceAsStream(V1_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing reviewed-adapter audit " + V1_RESOURCE);
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read reviewed-adapter audit", e);
        }
    }
}
