package com.bloxbean.cardano.julc.vm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;

/** Exact registry of pinned optimization cost profiles shipped by JuLC. */
public final class OptimizationCostProfiles {

    public static final String CARDANO_NODE_11_0_1_PLUTUS_V3_PV11_ID =
            "cardano-node-11.0.1-plutus-v3-pv11";
    public static final String CARDANO_NODE_11_0_1_PLUTUS_V3_PV11_PARAMETER_HASH =
            "40ea9e0b7df77a7bd2cb7d4e4d9da040f8bee7ff0324a7cdb7e51702330e43a8";

    private static final String PV11_RESOURCE =
            "/cost-model/cardano-node-11.0.1-plutus-v3-pv11.params";
    private static final String PV11_SOURCE =
            "cardano-node 11.0.1 / Plutus 1.63.0.0 "
                    + "(f92b7d7d82622a26caf456a6be33859f697e2cfc)";

    public static final OptimizationCostProfile CARDANO_NODE_11_0_1_PLUTUS_V3_PV11 =
            loadProfile(
                    CARDANO_NODE_11_0_1_PLUTUS_V3_PV11_ID,
                    LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                    PV11_SOURCE,
                    CARDANO_NODE_11_0_1_PLUTUS_V3_PV11_PARAMETER_HASH,
                    PV11_RESOURCE);

    private static final Map<String, OptimizationCostProfile> PROFILES = profilesById();

    private OptimizationCostProfiles() {
    }

    /** Resolve an exact, case-sensitive profile ID without aliases or fallback. */
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
        return Collections.unmodifiableSet(new java.util.TreeSet<>(PROFILES.keySet()));
    }

    private static Map<String, OptimizationCostProfile> profilesById() {
        var profiles = new LinkedHashMap<String, OptimizationCostProfile>();
        profiles.put(CARDANO_NODE_11_0_1_PLUTUS_V3_PV11.profileId(),
                CARDANO_NODE_11_0_1_PLUTUS_V3_PV11);
        return java.util.Collections.unmodifiableMap(profiles);
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
