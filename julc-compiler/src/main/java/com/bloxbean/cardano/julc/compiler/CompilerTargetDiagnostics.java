package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.compiler.error.DiagnosticCodes;
import com.bloxbean.cardano.julc.compiler.error.DiagnosticInfo;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.vm.ProtocolCapability;
import com.bloxbean.cardano.julc.vm.UplcVersion;

/** Catalog-backed diagnostics shared by target-aware compiler stages. */
public final class CompilerTargetDiagnostics {

    private CompilerTargetDiagnostics() {
    }

    public static CompilerException unavailableBuiltin(
            CompilationContext context,
            DefaultFun builtin,
            SourceLocation location) {
        var display = builtin + " (FLAT tag " + builtin.flatCode() + ")";
        var suffix = builtin == DefaultFun.MultiIndexArray
                ? "; it is a future/unreleased CIP-156 builtin at protocol version 11. "
                        + "Use IndexArray repeatedly for PV11"
                : "";
        var info = DiagnosticCodes.COMPILER_BUILTIN_UNAVAILABLE;
        var message = info.format(display, context.target().profileId()) + suffix;
        return exception(info, message, location);
    }

    public static CompilerException unavailableCapability(
            CompilationContext context,
            ProtocolCapability capability,
            SourceLocation location) {
        var info = DiagnosticCodes.COMPILER_FEATURE_UNAVAILABLE;
        var message = info.format(capability, context.target().profileId());
        return exception(info, message, location);
    }

    public static CompilerException programVersionMismatch(
            CompilationContext context,
            UplcVersion actual) {
        var info = DiagnosticCodes.COMPILER_PROGRAM_VERSION_MISMATCH;
        var message = info.format(
                actual, context.target().profileId(), context.target().uplcVersion());
        return exception(info, message, null);
    }

    public static CompilerException invariantViolation(
            CompilationContext context,
            String stage,
            String feature) {
        var info = DiagnosticCodes.COMPILER_TARGET_INVARIANT_VIOLATION;
        var message = info.format(stage, feature, context.target().profileId());
        return exception(info, message, null);
    }

    private static CompilerException exception(
            DiagnosticInfo info,
            String message,
            SourceLocation location) {
        var diagnostic = new CompilerDiagnostic(
                info.level(),
                message,
                location != null && location.fileName() != null
                        ? location.fileName() : "<compiler>",
                location != null ? location.line() : 0,
                location != null ? location.column() : 0,
                info.fix(),
                info.code());
        return new CompilerException(message, diagnostic);
    }
}
