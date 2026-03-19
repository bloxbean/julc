package com.bloxbean.cardano.julc.vm.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;

/**
 * Minimal TruffleLanguage registration for Untyped Plutus Core.
 * <p>
 * Required for Truffle's instrumentation framework to discover and attach to
 * UPLC nodes. Evaluation is done via {@link TruffleVmProvider} — the
 * {@link #parse(ParsingRequest)} method is intentionally unsupported.
 */
@TruffleLanguage.Registration(id = UplcTruffleLanguage.ID, name = "Untyped Plutus Core", version = "1.1.0")
public final class UplcTruffleLanguage extends TruffleLanguage<UplcTruffleLanguage.Ctx> {

    public static final String ID = "uplc";

    record Ctx(Env env) {}

    @Override
    protected Ctx createContext(Env env) {
        return new Ctx(env);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        throw new UnsupportedOperationException("Use TruffleVmProvider for evaluation");
    }
}
