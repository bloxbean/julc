package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.compiler.pir.PirFormatter;
import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;

import java.util.List;

/**
 * The result of compiling a Java validator to UPLC.
 * <p>
 * When created via {@link JulcCompiler#compileWithDetails(String)}, the
 * {@code pirTerm} and {@code uplcTerm} fields are populated for inspection.
 * When created via the standard {@link JulcCompiler#compile(String)}, they are null.
 */
public record CompileResult(Program program, List<CompilerDiagnostic> diagnostics, List<ParamInfo> params,
                             PirTerm pirTerm, Term uplcTerm, SourceMap sourceMap) {

    public CompileResult {
        diagnostics = List.copyOf(diagnostics);
        params = List.copyOf(params);
    }

    /**
     * Backward-compatible constructor (no PIR/UPLC/sourceMap).
     */
    public CompileResult(Program program, List<CompilerDiagnostic> diagnostics, List<ParamInfo> params,
                         PirTerm pirTerm, Term uplcTerm) {
        this(program, diagnostics, params, pirTerm, uplcTerm, null);
    }

    /**
     * Backward-compatible constructor (no PIR/UPLC).
     */
    public CompileResult(Program program, List<CompilerDiagnostic> diagnostics, List<ParamInfo> params) {
        this(program, diagnostics, params, null, null, null);
    }

    /**
     * Backward-compatible constructor for non-parameterized validators.
     */
    public CompileResult(Program program, List<CompilerDiagnostic> diagnostics) {
        this(program, diagnostics, List.of(), null, null, null);
    }

    /**
     * Whether this compile result includes a source map for error location tracking.
     */
    public boolean hasSourceMap() {
        return sourceMap != null && !sourceMap.isEmpty();
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(CompilerDiagnostic::isError);
    }

    /**
     * Whether this validator has parameters that must be applied before deployment.
     */
    public boolean isParameterized() {
        return !params.isEmpty();
    }

    /**
     * The FLAT-encoded script size in bytes.
     * This is the on-chain size — the number that matters for the 16 KB script size limit.
     */
    public int scriptSizeBytes() {
        return UplcFlatEncoder.encodeProgram(program).length;
    }

    /**
     * Human-readable script size, e.g. "342 B" or "14.2 KB".
     */
    public String scriptSizeFormatted() {
        int bytes = scriptSizeBytes();
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 10) {
            return String.format("%.1f KB", kb);
        }
        return String.format("%.0f KB", kb);
    }

    /**
     * Formatted PIR output (null if PIR not captured).
     * Returns the compact single-line PIR text.
     */
    public String pirFormatted() {
        return pirTerm != null ? PirFormatter.format(pirTerm) : null;
    }

    /**
     * Pretty-printed PIR output with indentation (null if PIR not captured).
     */
    public String pirPretty() {
        return pirTerm != null ? PirFormatter.formatPretty(pirTerm) : null;
    }

    /**
     * Formatted UPLC output (null if program is null).
     */
    public String uplcFormatted() {
        return program != null ? UplcPrinter.print(program) : null;
    }

    public record ParamInfo(String name, String type) {}
}
