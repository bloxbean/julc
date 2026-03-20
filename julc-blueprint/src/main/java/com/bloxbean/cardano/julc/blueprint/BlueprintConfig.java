package com.bloxbean.cardano.julc.blueprint;

/**
 * Configuration for CIP-57 blueprint generation.
 *
 * @param projectName    project title for the blueprint preamble
 * @param projectVersion project version for the blueprint preamble
 */
public record BlueprintConfig(String projectName, String projectVersion) {}
