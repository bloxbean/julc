package com.bloxbean.cardano.julc.analysis;

/**
 * An individual security finding from analysis.
 *
 * @param severity       how critical the finding is
 * @param category       vulnerability category
 * @param title          short summary of the finding
 * @param description    detailed explanation
 * @param location       where in the code (e.g., "line 42" or "Switch branch 3")
 * @param recommendation how to fix
 */
public record Finding(
        Severity severity,
        Category category,
        String title,
        String description,
        String location,
        String recommendation
) {}
