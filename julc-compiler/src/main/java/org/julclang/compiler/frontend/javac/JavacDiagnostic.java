package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticInfo;

/**
 * One rejection of the javac frontend (ADR-068 D5): {@code JULC0085} for a javac error, with javac's key, or a
 * JuLC check that runs before javac ({@code JULC0081}, {@code JULC0089}), with no key.
 *
 * @param code     the JuLC code
 * @param key      javac's diagnostic key, such as {@code compiler.err.cant.resolve.location}, or null
 * @param message  the message, formatted from the code's template
 * @param fileName the unit's file name, or null
 * @param line     the 1-based line, or 0
 * @param column   the 1-based column as a character offset from the line start (I7), or 0
 * @param start    javac's start offset in the unit, or -1
 * @param end      javac's end offset in the unit, or -1
 * @param hint     JuLC's hint, or null
 */
public record JavacDiagnostic(String code, String key, String message, String fileName, long line, long column,
                              long start, long end, String hint) {

    static JavacDiagnostic of(DiagnosticInfo info, String key, String message, String fileName, long line,
                              long column, long start, long end, String hint) {
        return new JavacDiagnostic(info.code(), key, message, fileName, line, column, start, end, hint);
    }

    /** The diagnostic as JuLC reports it. */
    public CompilerDiagnostic toCompilerDiagnostic() {
        return new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, message, fileName, (int) line, (int) column,
                hint, code);
    }
}
