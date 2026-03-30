package com.bloxbean.cardano.julc.vm.trace;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.vm.ExBudget;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Structured report of a validator failure, bundling all diagnostic context.
 *
 * @param errorMessage   the error message from the VM (e.g., "Error term encountered")
 * @param sourceLocation the Java source location that caused the failure (nullable)
 * @param lastBuiltins   the last N builtin executions before the failure
 * @param lastSteps      the last N execution trace entries before the failure
 * @param consumed       the budget consumed before the failure
 * @param traceMessages  trace messages emitted via {@code Builtins.trace()} before the failure
 */
public record FailureReport(
        String errorMessage,
        SourceLocation sourceLocation,
        List<BuiltinExecution> lastBuiltins,
        List<ExecutionTraceEntry> lastSteps,
        ExBudget consumed,
        List<String> traceMessages
) {
    /** Builtin functions that are comparison/equality operations. */
    public static final Set<DefaultFun> COMPARISON_BUILTINS = Set.of(
            DefaultFun.EqualsInteger, DefaultFun.LessThanInteger,
            DefaultFun.LessThanEqualsInteger,
            DefaultFun.EqualsByteString, DefaultFun.LessThanByteString,
            DefaultFun.LessThanEqualsByteString,
            DefaultFun.EqualsString, DefaultFun.EqualsData,
            DefaultFun.VerifyEd25519Signature,
            DefaultFun.VerifyEcdsaSecp256k1Signature,
            DefaultFun.VerifySchnorrSecp256k1Signature
    );

    public FailureReport {
        Objects.requireNonNull(errorMessage);
        lastBuiltins = lastBuiltins != null ? List.copyOf(lastBuiltins) : List.of();
        lastSteps = lastSteps != null ? List.copyOf(lastSteps) : List.of();
        Objects.requireNonNull(consumed);
        traceMessages = traceMessages != null ? List.copyOf(traceMessages) : List.of();
    }

    /**
     * Find the last comparison/equality builtin that returned False — the likely "cause"
     * of a boolean validator failure.
     *
     * @return the cause builtin execution, or null if none found
     */
    public BuiltinExecution findCauseBuiltin() {
        for (int i = lastBuiltins.size() - 1; i >= 0; i--) {
            var exec = lastBuiltins.get(i);
            if (COMPARISON_BUILTINS.contains(exec.fun())
                    && "False".equals(exec.resultSummary())) {
                return exec;
            }
        }
        return null;
    }
}
