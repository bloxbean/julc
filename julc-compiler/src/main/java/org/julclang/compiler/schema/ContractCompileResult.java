package org.julclang.compiler.schema;

import org.julclang.compiler.CompileResult;

import java.util.Objects;

/** A normal compilation result paired with opt-in contract interface metadata. */
public record ContractCompileResult(CompileResult compileResult, ContractSchema contractSchema) {
    public ContractCompileResult {
        compileResult = Objects.requireNonNull(compileResult, "compileResult");
        contractSchema = Objects.requireNonNull(contractSchema, "contractSchema");
    }
}
