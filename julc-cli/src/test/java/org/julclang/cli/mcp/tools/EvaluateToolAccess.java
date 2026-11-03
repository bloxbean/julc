package org.julclang.cli.mcp.tools;

import org.julclang.compiler.frontend.javac.SourceReader;

import org.julclang.cli.mcp.lint.LintEngine;

/** Test access to {@link EvaluateTool}'s arity check, for the ADR-068 tools gate in another package. */
public final class EvaluateToolAccess {

    private EvaluateToolAccess() {
    }

    public static int arity(String source, String methodName, SourceReader reader) {
        return EvaluateTool.expectedMethodArity(source, methodName, reader);
    }
}
