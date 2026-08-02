package com.bloxbean.cardano.julc.vm;

import java.util.Objects;

/** The ledger language and Cardano protocol version for one evaluation. */
public record LedgerEvaluationTarget(
        PlutusLanguage ledgerLanguage,
        ProtocolVersion protocolVersion) {

    public LedgerEvaluationTarget {
        Objects.requireNonNull(ledgerLanguage, "ledgerLanguage");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
    }

    public static LedgerEvaluationTarget pv10(PlutusLanguage language) {
        return new LedgerEvaluationTarget(language, ProtocolVersion.PV10);
    }

    public static LedgerEvaluationTarget pv11(PlutusLanguage language) {
        return new LedgerEvaluationTarget(language, ProtocolVersion.PV11);
    }

    /**
     * Whether two targets select the same Plutus evaluator behavior.
     * cardano-node supplies only the major protocol version to Plutus; the
     * minor component is retained as provenance but is not a semantics key.
     */
    public boolean hasSamePlutusSemantics(LedgerEvaluationTarget other) {
        return other != null
                && ledgerLanguage == other.ledgerLanguage
                && protocolVersion.major() == other.protocolVersion.major();
    }

    @Override
    public String toString() {
        return ledgerLanguage + "/PV" + protocolVersion.major() + "." + protocolVersion.minor();
    }
}
