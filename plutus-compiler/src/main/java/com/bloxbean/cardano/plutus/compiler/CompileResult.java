package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.plutus.core.Program;

import java.util.List;

/**
 * The result of compiling a Java validator to UPLC.
 */
public record CompileResult(Program program, List<CompilerDiagnostic> diagnostics) {

    public CompileResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(CompilerDiagnostic::isError);
    }
}
