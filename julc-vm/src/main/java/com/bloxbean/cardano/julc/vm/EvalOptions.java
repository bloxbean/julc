package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.source.SourceMap;

/**
 * Per-evaluation configuration options.
 * <p>
 * Passed to {@link JulcVmProvider#evaluate} and {@link JulcVmProvider#evaluateWithArgs}
 * to configure source maps, execution tracing, and builtin trace collection for a
 * single evaluation. This makes each evaluation self-contained and thread-safe —
 * no shared mutable state on the provider.
 *
 * @param sourceMap           the source map for debugging support (nullable — null disables)
 * @param tracingEnabled      true to enable per-step execution tracing (requires source map)
 * @param builtinTraceEnabled true to enable builtin execution trace collection (default: true)
 */
public record EvalOptions(SourceMap sourceMap, boolean tracingEnabled, boolean builtinTraceEnabled) {

    /** Default options: no source map, no execution tracing, builtin trace enabled. */
    public static final EvalOptions DEFAULT = new EvalOptions(null, false, true);

    /** Return a copy with the given source map. */
    public EvalOptions withSourceMap(SourceMap sourceMap) {
        return new EvalOptions(sourceMap, this.tracingEnabled, this.builtinTraceEnabled);
    }

    /** Return a copy with execution tracing enabled or disabled. */
    public EvalOptions withTracing(boolean enabled) {
        return new EvalOptions(this.sourceMap, enabled, this.builtinTraceEnabled);
    }

    /** Return a copy with builtin trace collection enabled or disabled. */
    public EvalOptions withBuiltinTrace(boolean enabled) {
        return new EvalOptions(this.sourceMap, this.tracingEnabled, enabled);
    }
}
