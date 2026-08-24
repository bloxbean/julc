package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerFieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.type.AssocMapTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.ListTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef;

import java.util.Objects;

public record LedgerTxInfoExpr(PropertyNode node) implements Expr {
    public LedgerTxInfoExpr { node = Objects.requireNonNull(node, "node"); }

    public LedgerTxInInfoListExpr inputs() {
        return inputsField("inputs");
    }
    public LedgerTxInInfoListExpr referenceInputs() {
        return inputsField("referenceInputs");
    }
    public LedgerTxOutListExpr outputs() {
        return new LedgerTxOutListExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_INFO,
                "outputs", new ListTypeRef(LedgerTypeAuthority.TX_OUT)));
    }
    public IntegerExpr fee() {
        return new IntegerExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_INFO,
                "fee", LedgerTypeAuthority.INTEGER));
    }
    public LedgerMintValueExpr mint() {
        return new LedgerMintValueExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.TX_INFO, "mint", LedgerTypeAuthority.MINT_VALUE));
    }
    public LedgerTxIdExpr id() {
        return new LedgerTxIdExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_INFO,
                "id", LedgerTypeAuthority.TX_ID));
    }
    public LedgerTxCertListExpr certificates() {
        return new LedgerTxCertListExpr(new LedgerFieldNode(
                node, LedgerTypeAuthority.TX_INFO, "certificates",
                new ListTypeRef(LedgerTypeAuthority.TX_CERT)));
    }
    public TypedAssocMapExpr datums() {
        return new TypedAssocMapExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_INFO,
                "datums", new AssocMapTypeRef(
                        LedgerTypeAuthority.DATUM_HASH, LedgerTypeAuthority.DATA)),
                LedgerTypeAuthority.DATUM_HASH, LedgerTypeAuthority.DATA);
    }
    public TypedAssocMapExpr redeemers() {
        return new TypedAssocMapExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_INFO,
                "redeemers", new AssocMapTypeRef(
                        LedgerTypeAuthority.SCRIPT_PURPOSE, LedgerTypeAuthority.DATA)),
                LedgerTypeAuthority.SCRIPT_PURPOSE, LedgerTypeAuthority.DATA);
    }
    public TypedAssocMapExpr withdrawals() {
        return new TypedAssocMapExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.TX_INFO, "withdrawals",
                new AssocMapTypeRef(LedgerTypeAuthority.CREDENTIAL,
                        LedgerTypeAuthority.INTEGER)),
                LedgerTypeAuthority.CREDENTIAL, LedgerTypeAuthority.INTEGER);
    }
    public VoterMapExpr votes() {
        var inner = new AssocMapTypeRef(LedgerTypeAuthority.GOVERNANCE_ACTION_ID,
                LedgerTypeAuthority.VOTE);
        return new VoterMapExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_INFO,
                "votes", new AssocMapTypeRef(LedgerTypeAuthority.VOTER, inner)));
    }
    public ProposalProcedureListExpr proposals() {
        return new ProposalProcedureListExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.TX_INFO, "proposals",
                new ListTypeRef(LedgerTypeAuthority.PROPOSAL_PROCEDURE)));
    }
    public ByteStringListExpr signatories() {
        return new ByteStringListExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.TX_INFO, "signatories",
                new ListTypeRef(LedgerTypeAuthority.PUB_KEY_HASH)));
    }
    public ValidityRangeExpr validityRangeReviewed() {
        return new ValidityRangeExpr(node);
    }
    public StrictTreasuryExpr currentTreasuryStrict() {
        return new StrictTreasuryExpr(node, StrictTreasuryExpr.TreasuryField.CURRENT_AMOUNT);
    }
    public StrictTreasuryExpr treasuryDonationStrict() {
        return new StrictTreasuryExpr(node, StrictTreasuryExpr.TreasuryField.DONATION);
    }

    private LedgerTxInInfoListExpr inputsField(String name) {
        return new LedgerTxInInfoListExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.TX_INFO, name,
                new ListTypeRef(LedgerTypeAuthority.TX_IN_INFO)));
    }
}
