package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;

/**
 * Shared helper methods for stdlib test files.
 */
final class StdlibTestHelpers {

    private StdlibTestHelpers() {}

    static byte[] makeBytes(int len, int fill) {
        var bs = new byte[len];
        Arrays.fill(bs, (byte) fill);
        return bs;
    }

    static Address pubKeyAddress(byte[] pkh) {
        return new Address(
                new Credential.PubKeyCredential(new PubKeyHash(pkh)),
                Optional.empty());
    }

    static Address scriptAddress(byte[] hash) {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(hash)),
                Optional.empty());
    }

    static TxOut simpleTxOut(Address addr, long lovelace) {
        return new TxOut(addr, Value.lovelace(BigInteger.valueOf(lovelace)),
                new OutputDatum.NoOutputDatum(), Optional.empty());
    }

    static PlutusData txOutList(TxOut... outs) {
        PlutusData[] items = new PlutusData[outs.length];
        for (int i = 0; i < outs.length; i++) {
            items[i] = outs[i].toPlutusData();
        }
        return PlutusData.list(items);
    }

    static PlutusData txInInfoList(TxInInfo... ins) {
        PlutusData[] items = new PlutusData[ins.length];
        for (int i = 0; i < ins.length; i++) {
            items[i] = ins[i].toPlutusData();
        }
        return PlutusData.list(items);
    }
}
