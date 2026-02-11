package com.bloxbean.cardano.julc.onchain.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Transaction information available to validators (V3, 16 fields).
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public record TxInfo(
        List<TxInInfo> inputs,                      // 0
        List<TxInInfo> referenceInputs,              // 1
        List<TxOut> outputs,                         // 2
        BigInteger fee,                              // 3
        Value mint,                                  // 4
        List<PlutusData> certificates,               // 5
        Map<PlutusData, BigInteger> withdrawals,     // 6
        Interval validRange,                         // 7
        List<byte[]> signatories,                    // 8
        Map<PlutusData, PlutusData> redeemers,       // 9
        Map<PlutusData, PlutusData> datums,          // 10
        byte[] id,                                   // 11
        Map<PlutusData, PlutusData> votes,           // 12
        List<PlutusData> proposalProcedures,         // 13
        Optional<BigInteger> currentTreasuryAmount,  // 14
        Optional<BigInteger> treasuryDonation        // 15
) {}
