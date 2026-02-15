package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.ledger.*;

/**
 * Identity adapter for ledger types.
 * <p>
 * Previously converted from ledger-api (rich wrapper) types to onchain-api (raw stub) types.
 * Since the onchain stubs have been removed and both sides now use the same ledger-api types,
 * all methods are identity functions. Retained for backward compatibility with test code.
 */
public final class LedgerTypeAdapter {

    private LedgerTypeAdapter() {}

    public static ScriptContext toOnchain(ScriptContext ctx) { return ctx; }
    public static TxInfo toOnchain(TxInfo txInfo) { return txInfo; }
    public static TxInInfo toOnchain(TxInInfo txInInfo) { return txInInfo; }
    public static TxOut toOnchain(TxOut txOut) { return txOut; }
    public static TxOutRef toOnchain(TxOutRef txOutRef) { return txOutRef; }
    public static Address toOnchain(Address address) { return address; }
    public static Credential toOnchain(Credential credential) { return credential; }
    public static Value toOnchain(Value value) { return value; }
    public static Interval toOnchain(Interval interval) { return interval; }
    public static IntervalBound toOnchain(IntervalBound bound) { return bound; }
    public static IntervalBoundType toOnchain(IntervalBoundType boundType) { return boundType; }
    public static ScriptInfo toOnchain(ScriptInfo scriptInfo) { return scriptInfo; }
    public static OutputDatum toOnchain(OutputDatum outputDatum) { return outputDatum; }
}
