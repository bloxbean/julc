package com.bloxbean.julc.cli.output;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;

import java.util.List;

/**
 * Formats CompilerDiagnostic messages as javac-style output.
 */
public final class DiagnosticFormatter {

    private DiagnosticFormatter() {}

    public static String format(CompilerDiagnostic d) {
        var sb = new StringBuilder();
        String levelStr = switch (d.level()) {
            case ERROR -> AnsiColors.red("error");
            case WARNING -> AnsiColors.yellow("warning");
            case INFO -> AnsiColors.blue("info");
        };
        sb.append(d.fileName()).append(':').append(d.line()).append(':').append(d.column())
                .append(": ").append(levelStr).append(": ").append(d.message());
        if (d.hasSuggestion()) {
            sb.append("\n  ").append(AnsiColors.dim("suggestion: " + d.suggestion()));
        }
        return sb.toString();
    }

    public static String formatAll(List<CompilerDiagnostic> diagnostics) {
        var sb = new StringBuilder();
        long errors = diagnostics.stream().filter(CompilerDiagnostic::isError).count();
        long warnings = diagnostics.stream().filter(d -> d.level() == CompilerDiagnostic.Level.WARNING).count();

        for (var d : diagnostics) {
            sb.append(format(d)).append('\n');
        }

        if (errors > 0 || warnings > 0) {
            sb.append('\n');
            if (errors > 0) sb.append(AnsiColors.red(errors + " error" + (errors > 1 ? "s" : "")));
            if (errors > 0 && warnings > 0) sb.append(", ");
            if (warnings > 0) sb.append(AnsiColors.yellow(warnings + " warning" + (warnings > 1 ? "s" : "")));
            sb.append('\n');
        }
        return sb.toString();
    }
}
