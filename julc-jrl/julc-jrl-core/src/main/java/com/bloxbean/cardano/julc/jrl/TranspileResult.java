package com.bloxbean.cardano.julc.jrl;

import com.bloxbean.cardano.julc.jrl.ast.ContractNode;
import com.bloxbean.cardano.julc.jrl.check.JrlDiagnostic;

import java.util.List;

/**
 * Result of transpiling JRL to Java source (without UPLC compilation).
 *
 * @param javaSource  generated Java source string
 * @param ast         parsed JRL AST
 * @param diagnostics JRL-level errors/warnings
 */
public record TranspileResult(
        String javaSource,
        ContractNode ast,
        List<JrlDiagnostic> diagnostics
) {
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(JrlDiagnostic::isError);
    }
}
