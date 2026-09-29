package org.julclang.clientlib.eval;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The parts of a transaction's original CBOR that cardano-ledger hashes or reads exactly: the body bytes (the
 * transaction id), the witness datums (keyed by the hash of their own bytes), and body fields whose presence CCL's
 * model does not keep. Re-serialising the CCL {@code Transaction} can change these bytes (CCL re-encodes canonically).
 */
final class OriginalTxBytes {

    private static final int MAJOR_ARRAY = 4;
    private static final int MAJOR_MAP = 5;
    private static final int WITNESS_DATUMS = 4;

    private final byte[] tx;
    private final byte[] body;
    private Map bodyMap;

    OriginalTxBytes(byte[] txCbor) {
        this.tx = txCbor.clone();
        this.body = TransactionUtil.extractTransactionBodyFromTx(tx);
    }

    /** The transaction body exactly as encoded. */
    byte[] body() {
        return body;
    }

    /** A body field as a CBOR item, or null when the body does not have it. */
    DataItem bodyField(int key) {
        if (bodyMap == null) {
            try {
                CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(body));
                decoder.setAutoDecodeRationalNumbers(false);
                bodyMap = (Map) decoder.decodeNext();
            } catch (CborException e) {
                throw new IllegalStateException("Cannot decode the transaction body", e);
            }
        }
        return bodyMap.get(new UnsignedInteger(key));
    }

    /** A body field holding an unsigned integer (a slot), or null when absent. */
    BigInteger bodyUnsigned(int key) {
        DataItem item = bodyField(key);
        return item == null ? null : ((UnsignedInteger) item).getValue();
    }

    /** The witness-set datums (key 4), each exactly as encoded, in witness order. */
    List<byte[]> witnessDatums() {
        var in = new ByteArrayInputStream(tx);
        var decoder = new CborDecoder(in);
        var datums = new ArrayList<byte[]>();
        try {
            header(in, MAJOR_ARRAY);            // [body, witnesses, isValid, auxiliaryData]
            decoder.decodeNext();               // body
            long entries = header(in, MAJOR_MAP);
            for (long i = 0; entries < 0 ? !atBreak(in) : i < entries; i++) {
                DataItem key = decoder.decodeNext();
                if (!(key instanceof UnsignedInteger u) || u.getValue().intValue() != WITNESS_DATUMS) {
                    decoder.decodeNext();
                    continue;
                }
                skipSetTag(in);
                long count = header(in, MAJOR_ARRAY);
                for (long j = 0; count < 0 ? !atBreak(in) : j < count; j++) {
                    int start = tx.length - in.available();
                    decoder.decodeNext();
                    datums.add(Arrays.copyOfRange(tx, start, tx.length - in.available()));
                }
            }
        } catch (CborException e) {
            throw new IllegalStateException("Cannot read the witness datums", e);
        }
        return datums;
    }

    /** Reads a definite (length) or indefinite (-1) header of the given major type. */
    private static long header(ByteArrayInputStream in, int major) throws CborException {
        int initial = in.read();
        if (initial < 0 || initial >>> 5 != major) {
            throw new CborException("Expected CBOR major type " + major);
        }
        int info = initial & 31;
        if (info < 24) return info;
        if (info == 31) return -1;
        int size = switch (info) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw new CborException("Invalid CBOR length " + info);
        };
        long length = 0;
        for (int i = 0; i < size; i++) {
            length = (length << 8) | in.read();
        }
        return length;
    }

    /** Consumes the break of an indefinite container, if it is next. */
    private static boolean atBreak(ByteArrayInputStream in) {
        in.mark(1);
        if (in.read() == 0xff) return true;
        in.reset();
        return false;
    }

    /** Skips the Conway set tag 258 ({@code d9 0102}), if present. */
    private static void skipSetTag(ByteArrayInputStream in) {
        in.mark(3);
        if (in.read() == 0xd9 && in.read() == 0x01 && in.read() == 0x02) return;
        in.reset();
    }
}
