package com.bloxbean.cardano.julc.verification.dsl;

import java.util.function.Function;

/** Deterministic construction-time names; parent canonicalization remains authoritative. */
final class BinderScope {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private BinderScope() { }

    static <T> T bind(Function<String, T> body) {
        int depth = DEPTH.get();
        if (depth >= 32) throw new IllegalArgumentException("DSL binder depth exceeds 32");
        DEPTH.set(depth + 1);
        try {
            return body.apply("v" + depth);
        } finally {
            if (depth == 0) DEPTH.remove();
            else DEPTH.set(depth);
        }
    }
}
