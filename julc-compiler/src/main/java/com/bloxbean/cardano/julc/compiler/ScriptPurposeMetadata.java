package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.PlutusTarget;

/**
 * Output metadata formatting for JuLC validator purposes.
 */
public final class ScriptPurposeMetadata {

    private ScriptPurposeMetadata() {}

    public static String textEnvelopeType() {
        return PlutusTarget.CURRENT.textEnvelopeType();
    }

    public static String jsonPurpose(JulcCompiler.ScriptPurpose purpose) {
        return switch (purpose) {
            case SPENDING -> "spending";
            case MINTING -> "minting";
            case WITHDRAW -> "withdraw";
            case CERTIFYING -> "certifying";
            case VOTING -> "voting";
            case PROPOSING -> "proposing";
            case MULTI -> "multi";
        };
    }
}
