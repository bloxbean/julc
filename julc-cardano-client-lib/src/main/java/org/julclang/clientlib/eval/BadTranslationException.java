package org.julclang.clientlib.eval;

/**
 * The ledger cannot translate the transaction into the context of a script's Plutus language: cardano-ledger's
 * {@code BadTranslation} collect error ({@code Alonzo/Plutus/Context.hs}), which rejects the transaction before any
 * script runs. {@link #contextError()} names the era's {@code ContextError} constructor.
 * <p>
 * It extends {@link UnsupportedOperationException}, which the V1/V2 context builder threw for these cases before.
 */
public final class BadTranslationException extends UnsupportedOperationException {

    /** The {@code ContextError} constructors (Alonzo, Babbage and Conway {@code TxInfo.hs}) JuLC detects. */
    public enum ContextError {
        /** A Byron address in an input, reference input or output ({@code transTxOutV1}/{@code transTxOutV2}). */
        ByronTxOutInContext,
        /** PlutusV1: an inline datum in an input, reference input or output ({@code transTxOutV1}). */
        InlineDatumsNotSupported,
        /** PlutusV1 before Conway: a reference script in an input or output (Babbage's {@code transTxOutV1}). */
        ReferenceScriptsNotSupported,
        /** PlutusV1 before Conway: any reference input (Babbage's PlutusV1 {@code toPlutusTxInfo}). */
        ReferenceInputsNotSupported,
        /** PlutusV1/V2: a certificate a {@code DCert} cannot express ({@code transTxCertV1V2}). */
        CertificateNotSupported,
        /** PlutusV1/V2: a voting or proposing purpose ({@code transPlutusPurposeV1V2}). */
        PlutusPurposeNotSupported,
        /** PlutusV1/V2: voting procedures ({@code guardConwayFeaturesForPlutusV1V2}). */
        VotingProceduresFieldNotSupported,
        /** PlutusV1/V2: proposal procedures ({@code guardConwayFeaturesForPlutusV1V2}). */
        ProposalProceduresFieldNotSupported,
        /** PlutusV1/V2: a non-zero treasury donation ({@code guardConwayFeaturesForPlutusV1V2}). */
        TreasuryDonationFieldNotSupported,
        /** PlutusV1/V2: a current treasury value ({@code guardConwayFeaturesForPlutusV1V2}). */
        CurrentTreasuryFieldNotSupported
    }

    private final ContextError contextError;

    BadTranslationException(ContextError contextError, String detail) {
        super("BadTranslation " + contextError + ": " + detail);
        this.contextError = contextError;
    }

    /** @return the ledger's {@code ContextError} */
    public ContextError contextError() {
        return contextError;
    }
}
