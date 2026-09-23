package org.julclang.compiler;

import org.julclang.core.debug.DebugMetadata;

import java.util.Objects;

/** Result of the explicit source-debug compilation path. */
public record DebugCompileResult(CompileResult compileResult, DebugMetadata debugMetadata) {
    public DebugCompileResult {
        Objects.requireNonNull(compileResult, "compileResult");
        Objects.requireNonNull(debugMetadata, "debugMetadata");
    }
}
