package org.julclang.clientlib.eval;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.Special;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * The parts of a transaction's original CBOR that cardano-ledger hashes or reads exactly: the body bytes (the
 * transaction id), the witness datums (keyed by the hash of their own bytes), and body fields whose presence CCL's
 * model does not keep. Re-serialising the CCL {@code Transaction} can change these bytes (CCL re-encodes canonically).
 */
final class OriginalTxBytes {

    private static final int MAJOR_ARRAY = 4;
    private static final int MAJOR_MAP = 5;
    private static final int MAJOR_TAG = 6;
    private static final int SET_TAG = 258;
    private static final int BODY_OUTPUTS = 1;
    private static final int OUTPUT_DATUM = 2;
    private static final int WITNESS_DATUMS = 4;
    private static final int WITNESS_REDEEMERS = 5;

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

    /**
     * Each output's inline datum ({@code datum_option = [1, #6.24(bytes)]}) exactly as encoded, or null when the
     * output has none; in output order.
     */
    List<byte[]> outputInlineDatums() {
        var datums = new ArrayList<byte[]>();
        DataItem outputs = bodyField(BODY_OUTPUTS);
        if (outputs == null) {
            return datums;
        }
        for (DataItem output : ((Array) outputs).getDataItems()) {
            if (Special.BREAK.equals(output)) {
                continue;
            }
            byte[] datum = null;
            if (output instanceof Map map && map.get(new UnsignedInteger(OUTPUT_DATUM)) instanceof Array option
                    && option.getDataItems().get(0) instanceof UnsignedInteger kind && kind.getValue().intValue() == 1) {
                datum = ((ByteString) option.getDataItems().get(1)).getBytes();
            }
            datums.add(datum);
        }
        return datums;
    }

    /** The witness-set datums (key 4), each exactly as encoded, in witness order. */
    List<byte[]> witnessDatums() {
        var datums = new ArrayList<byte[]>();
        readWitnessField(WITNESS_DATUMS, (in, decoder) -> {
            skipSetTag(in);
            long count = header(in, MAJOR_ARRAY);
            for (long i = 0; more(in, count, i); i++) {
                datums.add(item(in, decoder));
            }
        });
        return datums;
    }

    /**
     * The redeemers' data (key 5), each exactly as encoded, by pointer {@code tag << 32 | index}. Both the map and
     * the legacy list form; a later duplicate replaces an earlier one, as the ledger's {@code Map.fromList} does.
     */
    java.util.Map<Long, byte[]> redeemerData() {
        var data = new HashMap<Long, byte[]>();
        readWitnessField(WITNESS_REDEEMERS, (in, decoder) -> {
            if (peekMajor(in) == MAJOR_MAP) {
                long entries = header(in, MAJOR_MAP);
                for (long i = 0; more(in, entries, i); i++) {
                    long keyLength = header(in, MAJOR_ARRAY);
                    long pointer = pointer(decoder);
                    end(in, keyLength);
                    long valueLength = header(in, MAJOR_ARRAY);
                    data.put(pointer, item(in, decoder));
                    decoder.decodeNext(); // ex units
                    end(in, valueLength);
                }
            } else {
                long count = header(in, MAJOR_ARRAY);
                for (long i = 0; more(in, count, i); i++) {
                    long length = header(in, MAJOR_ARRAY);
                    long pointer = pointer(decoder);
                    data.put(pointer, item(in, decoder));
                    decoder.decodeNext(); // ex units
                    end(in, length);
                }
            }
        });
        return data;
    }

    /** A redeemer pointer as {@code tag << 32 | index}. */
    static long pointer(int tag, long index) {
        return ((long) tag << 32) | index;
    }

    @FunctionalInterface
    private interface FieldReader {
        void read(ByteArrayInputStream in, CborDecoder decoder) throws CborException;
    }

    /** Positions a reader at the value of witness-set field {@code field}, if present. */
    private void readWitnessField(int field, FieldReader reader) {
        var in = new ByteArrayInputStream(tx);
        var decoder = new CborDecoder(in);
        try {
            header(in, MAJOR_ARRAY);            // [body, witnesses, isValid, auxiliaryData]
            decoder.decodeNext();               // body
            long entries = header(in, MAJOR_MAP);
            for (long i = 0; more(in, entries, i); i++) {
                DataItem key = decoder.decodeNext();
                if (key instanceof UnsignedInteger u && u.getValue().intValue() == field) {
                    reader.read(in, decoder);
                } else {
                    decoder.decodeNext();
                }
            }
        } catch (CborException e) {
            throw new IllegalStateException("Cannot read witness-set field " + field, e);
        }
    }

    /** The next item exactly as encoded. */
    private byte[] item(ByteArrayInputStream in, CborDecoder decoder) throws CborException {
        int start = tx.length - in.available();
        decoder.decodeNext();
        return Arrays.copyOfRange(tx, start, tx.length - in.available());
    }

    private static long pointer(CborDecoder decoder) throws CborException {
        int tag = ((UnsignedInteger) decoder.decodeNext()).getValue().intValueExact();
        long index = ((UnsignedInteger) decoder.decodeNext()).getValue().longValueExact();
        return pointer(tag, index);
    }

    /** Whether a container of {@code length} (-1: indefinite) has an element after the first {@code read}. */
    private static boolean more(ByteArrayInputStream in, long length, long read) {
        return length < 0 ? !atBreak(in) : read < length;
    }

    /** Consumes the break that ends an indefinite container. */
    private static void end(ByteArrayInputStream in, long length) throws CborException {
        if (length < 0 && !atBreak(in)) {
            throw new CborException("Expected the end of an indefinite container");
        }
    }

    private static int peekMajor(ByteArrayInputStream in) {
        in.mark(1);
        int initial = in.read();
        in.reset();
        return initial >>> 5;
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

    /** Skips the Conway set tag 258 ({@code #6.258}), however wide its argument is encoded, if present. */
    private static void skipSetTag(ByteArrayInputStream in) throws CborException {
        if (peekMajor(in) != MAJOR_TAG) return;
        in.mark(9);
        if (header(in, MAJOR_TAG) != SET_TAG) in.reset();
    }
}
