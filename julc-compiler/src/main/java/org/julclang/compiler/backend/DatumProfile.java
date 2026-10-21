package org.julclang.compiler.backend;

/**
 * How a spending handler receives the datum of the output it spends (ADR-059). Other
 * purposes have no datum and use {@link #ABSENT}.
 */
public enum DatumProfile {
    /** The datum must be present; a missing datum fails. The handler receives the decoded datum. */
    REQUIRED,
    /** The handler receives the ledger {@code Maybe} as a strictly checked {@code Optional}. */
    OPTIONAL,
    /** The handler receives no datum argument and the datum is never inspected. */
    ABSENT
}
