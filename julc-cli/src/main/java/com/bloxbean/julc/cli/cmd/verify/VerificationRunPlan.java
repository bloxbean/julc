package com.bloxbean.julc.cli.cmd.verify;

import java.util.List;

/** Versioned, tokenized execution plan for a trusted verification workspace. */
public record VerificationRunPlan(
        int schemaVersion,
        String kind,
        String manifest,
        String resultManifest,
        int timeoutSeconds,
        List<Step> acquire,
        List<Step> verify) {

    public record Step(
            String id,
            List<String> command,
            String executableSha256,
            Integer maxAttempts,
            List<Integer> expectedExitCodes,
            String requiredOutput,
            String propertyId,
            String result,
            String reason,
            List<ObservedOutcome> outcomes) {
    }

    /** An authenticated process observation mapped to one verification result. */
    public record ObservedOutcome(
            int exitCode,
            String requiredOutput,
            String result,
            String reason) {
    }
}
