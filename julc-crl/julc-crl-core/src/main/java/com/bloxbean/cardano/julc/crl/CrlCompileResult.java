package com.bloxbean.cardano.julc.crl;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.crl.ast.ContractNode;
import com.bloxbean.cardano.julc.crl.check.CrlDiagnostic;

import java.util.List;

/**
 * Result of compiling a CRL source file through the full pipeline:
 * CRL parse -> type check -> Java transpile -> JulcCompiler -> UPLC.
 *
 * @param compileResult      UPLC compilation result from JulcCompiler (null if failed before compilation)
 * @param generatedJavaSource the intermediate Java source (for debugging)
 * @param ast                 parsed CRL AST (null if parse failed)
 * @param crlDiagnostics      CRL-level errors/warnings (parse + type check)
 */
public record CrlCompileResult(
        CompileResult compileResult,
        String generatedJavaSource,
        ContractNode ast,
        List<CrlDiagnostic> crlDiagnostics
) {
    public boolean hasErrors() {
        if (crlDiagnostics.stream().anyMatch(CrlDiagnostic::isError)) return true;
        return compileResult != null && compileResult.hasErrors();
    }
}
