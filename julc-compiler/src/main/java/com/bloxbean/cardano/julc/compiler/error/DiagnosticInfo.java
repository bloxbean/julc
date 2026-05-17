package com.bloxbean.cardano.julc.compiler.error;

import java.text.MessageFormat;

/**
 * Catalog-backed diagnostic metadata generated into {@link DiagnosticCodes}.
 *
 * @param code     stable public diagnostic code, e.g. {@code JULC0015}
 * @param constant generated Java constant name
 * @param level    diagnostic severity
 * @param category catalog category
 * @param template exact compiler-emitted message template
 * @param fix      canonical fix guidance
 */
public record DiagnosticInfo(String code, String constant, CompilerDiagnostic.Level level,
                             String category, String template, String fix) {

    public String format(Object... args) {
        if (args == null || args.length == 0) {
            return template;
        }
        return MessageFormat.format(template, args);
    }
}
