package com.bloxbean.julc.cli.repl;

import com.bloxbean.cardano.julc.vm.ExBudget;

import java.util.List;

/**
 * Sealed result type for REPL evaluation outcomes.
 */
public sealed interface ReplResult {

    /**
     * Successful evaluation with extracted value and budget.
     */
    record Success(String formattedValue, ExBudget budget, List<String> traces,
                   String uplc, String pir) implements ReplResult {
        public Success {
            traces = List.copyOf(traces);
        }
    }

    /**
     * Evaluation or compilation error.
     */
    record Error(String message, ExBudget budget, List<String> traces) implements ReplResult {
        public Error {
            traces = List.copyOf(traces);
        }

        public Error(String message) {
            this(message, ExBudget.ZERO, List.of());
        }
    }

    /**
     * Output from a meta-command (:help, :libs, :methods, etc.).
     */
    record MetaOutput(String text) implements ReplResult {}
}
