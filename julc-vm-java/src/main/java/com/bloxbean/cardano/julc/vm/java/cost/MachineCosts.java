package com.bloxbean.cardano.julc.vm.java.cost;

/**
 * Per-step CEK machine costs.
 * <p>
 * Each step type has a fixed CPU and memory cost charged whenever the machine
 * enters that evaluation state.
 *
 * @param startupCpu  one-time startup CPU cost
 * @param startupMem  one-time startup memory cost
 * @param varCpu      cost of variable lookup
 * @param varMem      memory cost of variable lookup
 * @param lamCpu      cost of lambda creation
 * @param lamMem      memory cost of lambda creation
 * @param applyCpu    cost of function application
 * @param applyMem    memory cost of function application
 * @param forceCpu    cost of forcing a delayed value
 * @param forceMem    memory cost of forcing
 * @param delayCpu    cost of creating a delay
 * @param delayMem    memory cost of creating a delay
 * @param constCpu    cost of constant evaluation
 * @param constMem    memory cost of constant evaluation
 * @param builtinCpu  cost of builtin reference (not execution)
 * @param builtinMem  memory cost of builtin reference
 * @param constrCpu   cost of constructor creation
 * @param constrMem   memory cost of constructor creation
 * @param caseCpu     cost of case dispatch
 * @param caseMem     memory cost of case dispatch
 */
public record MachineCosts(
        long startupCpu, long startupMem,
        long varCpu, long varMem,
        long lamCpu, long lamMem,
        long applyCpu, long applyMem,
        long forceCpu, long forceMem,
        long delayCpu, long delayMem,
        long constCpu, long constMem,
        long builtinCpu, long builtinMem,
        long constrCpu, long constrMem,
        long caseCpu, long caseMem
) {
    /**
     * The step kind, used to index into the machine costs.
     */
    public enum StepKind {
        STARTUP, VAR, LAM, APPLY, FORCE, DELAY, CONST, BUILTIN, CONSTR, CASE
    }

    /** Get the CPU cost for a given step kind. */
    public long cpuFor(StepKind kind) {
        return switch (kind) {
            case STARTUP -> startupCpu;
            case VAR -> varCpu;
            case LAM -> lamCpu;
            case APPLY -> applyCpu;
            case FORCE -> forceCpu;
            case DELAY -> delayCpu;
            case CONST -> constCpu;
            case BUILTIN -> builtinCpu;
            case CONSTR -> constrCpu;
            case CASE -> caseCpu;
        };
    }

    /** Get the memory cost for a given step kind. */
    public long memFor(StepKind kind) {
        return switch (kind) {
            case STARTUP -> startupMem;
            case VAR -> varMem;
            case LAM -> lamMem;
            case APPLY -> applyMem;
            case FORCE -> forceMem;
            case DELAY -> delayMem;
            case CONST -> constMem;
            case BUILTIN -> builtinMem;
            case CONSTR -> constrMem;
            case CASE -> caseMem;
        };
    }
}
