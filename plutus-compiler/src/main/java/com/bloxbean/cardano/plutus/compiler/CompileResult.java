package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.plutus.core.Program;

import java.util.List;

/**
 * The result of compiling a Java validator to UPLC.
 */
public record CompileResult(Program program, List<CompilerDiagnostic> diagnostics, List<ParamInfo> params) {

    public CompileResult {
        diagnostics = List.copyOf(diagnostics);
        params = List.copyOf(params);
    }

    /**
     * Backward-compatible constructor for non-parameterized validators.
     */
    public CompileResult(Program program, List<CompilerDiagnostic> diagnostics) {
        this(program, diagnostics, List.of());
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
     * Metadata about a contract parameter declared with {@code @Param}.
     *
     * @param name the parameter field name
     * @param type the Java type name (e.g. "byte[]", "BigInteger")
     */
    public record ParamInfo(String name, String type) {}
}
