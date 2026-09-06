package com.bloxbean.cardano.julc.core.cbor;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.decoder.AbstractDecoder;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Special;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;

/**
 * Keeps CBOR map associations intact before PlutusData conversion. All other CBOR
 * parsing stays with cbor-java; its array/tag decoders recurse through decodeNext.
 */
final class MapPreservingCborDecoder extends CborDecoder {
    private final ByteArrayInputStream input;
    private final EntriesDecoder entriesDecoder;

    MapPreservingCborDecoder(ByteArrayInputStream input) {
        super(input);
        this.input = input;
        this.entriesDecoder = new EntriesDecoder(this, input);
    }

    @Override
    public DataItem decodeNext() throws CborException {
        input.mark(1);
        int initialByte = input.read();
        if (initialByte != -1 && MajorType.ofByte(initialByte) == MajorType.MAP) {
            return entriesDecoder.decode(initialByte);
        }
        // Reset before delegation: the parent owns consumption of non-map headers.
        input.reset();
        return super.decodeNext();
    }

    private static final class EntriesDecoder extends AbstractDecoder<DataItem> {
        private final ByteArrayInputStream input;

        EntriesDecoder(CborDecoder decoder, ByteArrayInputStream input) {
            super(decoder, input);
            this.input = input;
        }

        @Override
        public DataItem decode(int initialByte) throws CborException {
            boolean indefinite = (initialByte & 31) == 31;
            BigInteger length = getLengthAsBigInteger(initialByte);
            // A pair needs at least one byte for each member. Check unsigned lengths
            // before narrowing, and never preallocate from an untrusted header.
            if (!indefinite && length.compareTo(BigInteger.valueOf(input.available() / 2)) > 0) {
                throw new CborException("Map length exceeds remaining CBOR input");
            }
            int count = indefinite ? 0 : length.intValueExact();
            var keys = new ArrayList<DataItem>();
            var values = new ArrayList<DataItem>();
            for (int i = 0; indefinite || i < count; i++) {
                DataItem key = decoder.decodeNext();
                if (indefinite && Special.BREAK.equals(key)) {
                    break;
                }
                requireMember(key);
                DataItem value = decoder.decodeNext();
                requireMember(value);
                keys.add(key);
                values.add(value);
            }
            return new PlutusDataCborEncoder.OrderedMap(keys, values);
        }

        private static void requireMember(DataItem item) throws CborException {
            if (item == null) {
                throw new CborException("Unexpected end of stream in map entry");
            }
            if (Special.BREAK.equals(item)) {
                throw new CborException("Unexpected break in map entry");
            }
        }
    }
}
