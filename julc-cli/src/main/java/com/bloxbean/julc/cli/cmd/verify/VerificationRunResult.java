package com.bloxbean.julc.cli.cmd.verify;

import com.fasterxml.jackson.annotation.JsonInclude;

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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Property(
            String id,
            String outcome,
            String reason,
            String domain,
            String guaranteeSha256,
            String envelopeSha256,
            List<String> capabilities,
            String counterexampleDomain,
            Boolean ledgerValidCounterexampleEstablished,
            Boolean concreteVmCounterexampleReproduced) {
        public Property(String id, String outcome, String reason) {
            this(id, outcome, reason, null, null, null, null, null, null, null);
        }
    }
}
