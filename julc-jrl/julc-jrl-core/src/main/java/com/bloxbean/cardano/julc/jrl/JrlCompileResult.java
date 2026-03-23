package com.bloxbean.cardano.julc.jrl;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.jrl.ast.ContractNode;
import com.bloxbean.cardano.julc.jrl.check.JrlDiagnostic;

import java.util.List;

/**
 * Result of compiling a JRL source file through the full pipeline:
 * JRL parse -> type check -> Java transpile -> JulcCompiler -> UPLC.
 *
 * @param compileResult      UPLC compilation result from JulcCompiler (null if failed before compilation)
 * @param generatedJavaSource the intermediate Java source (for debugging)
 * @param ast                 parsed JRL AST (null if parse failed)
 * @param jrlDiagnostics      JRL-level errors/warnings (parse + type check)
 */
public record JrlCompileResult(
        CompileResult compileResult,
        String generatedJavaSource,
        ContractNode ast,
        List<JrlDiagnostic> jrlDiagnostics
) {
    public boolean hasErrors() {
        if (jrlDiagnostics.stream().anyMatch(JrlDiagnostic::isError)) return true;
        return compileResult != null && compileResult.hasErrors();
    }
}
