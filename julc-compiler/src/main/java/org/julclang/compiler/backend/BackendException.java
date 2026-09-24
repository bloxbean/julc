package org.julclang.compiler.backend;

import org.julclang.compiler.error.DiagnosticInfo;

import java.util.Objects;

/**
 * A catalogued neutral-backend rejection that names the producer symbol or descriptor
 * (ADR-059). It extends {@link IllegalArgumentException} so revision-1 callers that
 * catch the historical exception type keep working.
 */
public final class BackendException extends IllegalArgumentException {
    private final DiagnosticInfo diagnostic;
    private final String subject;

    /**
     * @param diagnostic the catalogued diagnostic
     * @param subject    the producer symbol or descriptor the diagnostic is about
     * @param arguments  the template arguments; the first is conventionally the subject
     */
    public BackendException(DiagnosticInfo diagnostic, String subject, Object... arguments) {
        super("[" + diagnostic.code() + "] " + diagnostic.format(arguments));
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.subject = Objects.requireNonNull(subject, "subject");
    }

    /** The stable catalog code, for example {@code JULC0049}. */
    public String code() {
        return diagnostic.code();
    }

    public DiagnosticInfo diagnostic() {
        return diagnostic;
    }

    /** The producer symbol or descriptor path the diagnostic identifies. */
    public String subject() {
        return subject;
    }
}
