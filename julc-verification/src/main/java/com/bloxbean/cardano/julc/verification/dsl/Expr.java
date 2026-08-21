package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

/** A symbolic value. It is intentionally not convertible to a Java runtime value. */
public sealed interface Expr permits BoolExpr, ByteStringExpr, IntegerExpr, DatumExpr,
        ContextExpr, TxInfoExpr, ByteStringListExpr, TxOutExpr, TxOutListExpr,
        ValueExpr, AddressExpr, CredentialExpr, PolicyIdExpr, MintValueExpr,
        TxOutRefExpr, TxInInfoListExpr, WithdrawalsExpr, WithdrawalEntryExpr,
        TxCertExpr, TxCertListExpr, TypedValueExpr, TypedOptionExpr,
        TypedListExpr, TypedAssocMapExpr, StringExpr, LedgerContextExpr,
        LedgerTxInfoExpr, LedgerTxInInfoExpr, LedgerTxInInfoListExpr,
        LedgerTxInInfoOptionExpr, LedgerTxOutRefExpr, LedgerTxIdExpr,
        LedgerTxOutExpr, LedgerTxOutListExpr, LedgerTxOutOptionExpr,
        LedgerAddressExpr, LedgerCredentialExpr, LedgerOutputDatumExpr,
        LedgerScriptPurposeExpr, LedgerStakingCredentialExpr, LedgerValueExpr,
        LedgerByteAliasExpr,
        LedgerStakingCredentialOptionExpr {
    PropertyNode node();
}
