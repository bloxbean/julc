package com.bloxbean.cardano.julc.verification.capability;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Machine-readable authority and discrepancy audit for schema-10 raw adapters. */
public record ReviewedRawDataAdapterAudit(
        int schemaVersion,
        String proofModelRevision,
        String plutusRevision,
        String cardanoLedgerRevision,
        String julcRevision,
        List<Source> sources,
        List<Adapter> adapters) {

    public static final int SCHEMA_VERSION = 1;

    public ReviewedRawDataAdapterAudit {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported reviewed-adapter audit schema " + schemaVersion);
        }
        requireCommit("proofModelRevision", proofModelRevision);
        requireCommit("plutusRevision", plutusRevision);
        requireCommit("cardanoLedgerRevision", cardanoLedgerRevision);
        requireCommit("julcRevision", julcRevision);
        sources = List.copyOf(sources == null ? List.of() : sources);
        adapters = List.copyOf(adapters == null ? List.of() : adapters);
        if (sources.isEmpty() || adapters.isEmpty()) {
            throw new IllegalArgumentException(
                    "Reviewed-adapter audit requires sources and adapters");
        }
        var sourceIds = new HashSet<String>();
        for (Source source : sources) {
            Objects.requireNonNull(source, "source");
            if (!sourceIds.add(source.id())) {
                throw new IllegalArgumentException("Duplicate audit source " + source.id());
            }
        }
        var adapterIds = new HashSet<String>();
        for (Adapter adapter : adapters) {
            Objects.requireNonNull(adapter, "adapter");
            if (!adapterIds.add(adapter.id())) {
                throw new IllegalArgumentException("Duplicate adapter audit " + adapter.id());
            }
            for (String sourceId : adapter.sourceIds()) {
                if (!sourceIds.contains(sourceId)) {
                    throw new IllegalArgumentException(
                            "Adapter " + adapter.id() + " has unknown source " + sourceId);
                }
            }
        }
    }

    public Adapter require(String id) {
        return adapters.stream().filter(adapter -> adapter.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unclassified reviewed raw-data adapter: " + id));
    }

    private static void requireCommit(String name, String revision) {
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(name + " must be a full lowercase commit ID");
        }
    }

    public record Source(String id, String repository, String revision, String path,
                         String sha256, String authority) {
        public Source {
            if (id == null || !id.matches("[a-z][a-z0-9.-]+")) {
                throw new IllegalArgumentException("Invalid audit source ID " + id);
            }
            repository = Objects.requireNonNull(repository, "repository");
            revision = Objects.requireNonNull(revision, "revision");
            path = Objects.requireNonNull(path, "path");
            authority = Objects.requireNonNull(authority, "authority");
            if (sha256 != null && !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid source SHA-256 for " + id);
            }
        }
    }

    public record Adapter(String id, String rawFieldCapability, AdapterStatus status,
                          String acceptedShape, String canonicalForm,
                          List<String> constraints, List<String> discrepancies,
                          List<String> sourceIds) {
        public Adapter {
            if (id == null || !id.matches("adapter\\.[a-z][a-z0-9.-]+")) {
                throw new IllegalArgumentException("Invalid adapter ID " + id);
            }
            rawFieldCapability = Objects.requireNonNull(
                    rawFieldCapability, "rawFieldCapability");
            status = Objects.requireNonNull(status, "status");
            acceptedShape = Objects.requireNonNull(acceptedShape, "acceptedShape");
            canonicalForm = Objects.requireNonNull(canonicalForm, "canonicalForm");
            constraints = List.copyOf(constraints == null ? List.of() : constraints);
            discrepancies = List.copyOf(discrepancies == null ? List.of() : discrepancies);
            sourceIds = List.copyOf(sourceIds == null ? List.of() : sourceIds);
            if (sourceIds.isEmpty()) {
                throw new IllegalArgumentException("Adapter " + id + " has no authority source");
            }
        }
    }

    public enum AdapterStatus {
        READY_FOR_IMPLEMENTATION,
        DEFERRED
    }
}
