package com.bloxbean.julc.cli.cmd.verify;

import java.util.List;
import java.util.Map;

/** Canonical machine-readable result produced by {@code julc verify run}. */
public record VerificationRunResult(
        int schemaVersion,
        String generatedBy,
        String outcome,
        String reason,
        String backend,
        String backendIdentity,
        Map<String, String> toolchain,
        Map<String, String> dependencyCommits,
        boolean dependencyDownloadsDisabledDuringVerification,
        boolean ledgerValidityModeled,
        Map<String, Object> artifact,
        Map<String, String> inputs,
        List<Phase> phases,
        List<Property> properties) {

    public record Phase(
            String id,
            String phase,
            String status,
            Integer exitCode,
            String log,
            String logSha256) {
    }

    public record Property(String id, String outcome, String reason) {
    }
}
