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

    @Override
    public String toString() {
        return ledgerLanguage + "/PV" + protocolVersion.major() + "." + protocolVersion.minor();
    }
}
