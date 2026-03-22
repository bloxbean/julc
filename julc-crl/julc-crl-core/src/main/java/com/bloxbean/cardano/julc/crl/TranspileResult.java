package com.bloxbean.cardano.julc.crl;

import com.bloxbean.cardano.julc.crl.ast.ContractNode;
import com.bloxbean.cardano.julc.crl.check.CrlDiagnostic;

import java.util.List;

/**
 * Result of transpiling CRL to Java source (without UPLC compilation).
 *
 * @param javaSource  generated Java source string
 * @param ast         parsed CRL AST
 * @param diagnostics CRL-level errors/warnings
 */
public record TranspileResult(
        String javaSource,
        ContractNode ast,
        List<CrlDiagnostic> diagnostics
) {
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(CrlDiagnostic::isError);
    }
}
