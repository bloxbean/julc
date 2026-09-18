package org.julclang.vm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Exact registry of immutable evaluation and benchmark cost snapshots shipped by JuLC. */
public final class OptimizationCostProfiles {

    public static final String PLUTUS_V3_PV11_COSTS_V1_ID = "plutus-v3-pv11-costs-v1";
    public static final String PLUTUS_V3_PV11_COSTS_V1_PARAMETER_HASH =
            "40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8";

    private static final String PV11_RESOURCE =
            "/cost-model/plutus-v3-pv11-costs-v1.params";

    public static final OptimizationCostProfile PLUTUS_V3_PV11_COSTS_V1 =
            loadProfile(
                    PLUTUS_V3_PV11_COSTS_V1_ID,
                    LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                    "bundled:" + PLUTUS_V3_PV11_COSTS_V1_ID,
                    PLUTUS_V3_PV11_COSTS_V1_PARAMETER_HASH,
                    PV11_RESOURCE);

    /** @deprecated Use {@link #PLUTUS_V3_PV11_COSTS_V1_ID}. Accepted as a lookup alias. */
    @Deprecated(forRemoval = false)
    public static final String CARDANO_NODE_11_0_1_PLUTUS_V3_PV11_ID =
            "cardano-node-11.0.1-plutus-v3-pv11";

    /** @deprecated Use {@link #PLUTUS_V3_PV11_COSTS_V1_PARAMETER_HASH}. */
    @Deprecated(forRemoval = false)
    public static final String CARDANO_NODE_11_0_1_PLUTUS_V3_PV11_PARAMETER_HASH =
            PLUTUS_V3_PV11_COSTS_V1_PARAMETER_HASH;

    /** @deprecated Use {@link #PLUTUS_V3_PV11_COSTS_V1}. This is the same snapshot. */
    @Deprecated(forRemoval = false)
    public static final OptimizationCostProfile CARDANO_NODE_11_0_1_PLUTUS_V3_PV11 =
            PLUTUS_V3_PV11_COSTS_V1;

    private static final Map<String, OptimizationCostProfile> PROFILES = profilesById();

    private OptimizationCostProfiles() {
    }

    /** Resolve an exact, case-sensitive ID, including the deprecated immutable alias. */
    public static OptimizationCostProfile forId(String profileId) {
        Objects.requireNonNull(profileId, "profileId");
        var profile = PROFILES.get(profileId);
        if (profile == null) {
            throw new IllegalArgumentException(
                    "Unknown optimization cost profile " + profileId
                            + "; supported profiles: " + supportedProfileIds());
        }
        return profile;
    }

    public static Set<String> supportedProfileIds() {
        return Collections.unmodifiableSet(new TreeSet<>(PROFILES.keySet()));
    }

    private static Map<String, OptimizationCostProfile> profilesById() {
        var profiles = new LinkedHashMap<String, OptimizationCostProfile>();
        profiles.put(PLUTUS_V3_PV11_COSTS_V1_ID, PLUTUS_V3_PV11_COSTS_V1);
        profiles.put(CARDANO_NODE_11_0_1_PLUTUS_V3_PV11_ID, PLUTUS_V3_PV11_COSTS_V1);
        return Collections.unmodifiableMap(profiles);
    }

    private static OptimizationCostProfile loadProfile(
            String profileId,
            LedgerEvaluationTarget target,
            String source,
            String expectedHash,
            String resource) {
        var stream = OptimizationCostProfiles.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new ExceptionInInitializerError("Missing cost profile resource " + resource);
        }

        List<String> values;
        try (var reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            values = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }

        var canonical = String.join("\n", values) + "\n";
        var actualHash = sha256(canonical.getBytes(StandardCharsets.UTF_8));
        if (!expectedHash.equals(actualHash)) {
            throw new ExceptionInInitializerError(
                    "Cost profile " + profileId + " parameter hash mismatch: expected "
                            + expectedHash + ", got " + actualHash);
        }

        long[] parameters;
        try {
            parameters = values.stream().mapToLong(Long::parseLong).toArray();
        } catch (NumberFormatException e) {
            throw new ExceptionInInitializerError(
                    "Invalid numeric parameter in cost profile " + profileId + ": "
                            + e.getMessage());
        }
        return new OptimizationCostProfile(
                profileId, target, source, expectedHash, parameters);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
