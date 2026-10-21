package org.julclang.compiler.backend;

import org.julclang.core.Program;

import java.util.Objects;

/** A compiled {@link ValidatorProgram}: the lowered script and its neutral ABI (ADR-059). */
public record ValidatorResult(PirBackend.Result backend, ValidatorAbi abi) {
    public ValidatorResult {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(abi, "abi");
    }

    /** The target-valid UPLC program; apply parameters with {@link Program#applyParams}. */
    public Program program() {
        return backend.program();
    }
}
