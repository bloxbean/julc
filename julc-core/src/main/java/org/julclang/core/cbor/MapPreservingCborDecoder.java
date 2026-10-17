package org.julclang.core.cbor;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.decoder.AbstractDecoder;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Special;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;

/**
 * Keeps CBOR map associations intact before PlutusData conversion, and applies the CBOR
 * rules of Plutus {@code decodeData} that the converted items no longer show: the 64-byte
 * byte string limit and the header forms of integers, arrays and tags. All other CBOR
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
        // A header is at most 9 bytes; checkHeader also reads a tag's content byte.
        input.mark(10);
        int initialByte = input.read();
        if (initialByte != -1) {
            switch (MajorType.ofByte(initialByte)) {
                case MAP:
                    return entriesDecoder.decode(initialByte);
                case BYTE_STRING:
                    return entriesDecoder.decodeBytes(initialByte);
                case UNSIGNED_INTEGER, NEGATIVE_INTEGER, ARRAY, TAG:
                    entriesDecoder.checkHeader(initialByte);
                    break;
                default:
                    break;
            }
        }
        // Reset before delegation: the parent owns consumption of the other headers.
        input.reset();
        return super.decodeNext();
    }

    private static final class EntriesDecoder extends AbstractDecoder<DataItem> {
        private static final BigInteger MAX_BYTES = BigInteger.valueOf(64);
        private static final int BIGNUM_POSITIVE = 0xc2;
        private static final int BIGNUM_NEGATIVE = 0xc3;
        private static final int BREAK = 0xff;

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

        /**
         * Plutus {@code decodeBoundedBytes}: a definite byte string is at most 64 bytes, and an
         * indefinite one is a sequence of such definite chunks.
         */
        ByteString decodeBytes(int initialByte) throws CborException {
            if ((initialByte & 31) != 31) {
                return new ByteString(chunk(initialByte));
            }
            var bytes = new ByteArrayOutputStream();
            for (int chunkByte = nextSymbol(); chunkByte != BREAK; chunkByte = nextSymbol()) {
                if (MajorType.ofByte(chunkByte) != MajorType.BYTE_STRING || (chunkByte & 31) == 31) {
                    throw new CborException("Indefinite byte string chunk must be a definite byte string");
                }
                bytes.writeBytes(chunk(chunkByte));
            }
            return new ByteString(bytes.toByteArray());
        }

        private byte[] chunk(int initialByte) throws CborException {
            BigInteger length = getLengthAsBigInteger(initialByte);
            if (length.compareTo(MAX_BYTES) > 0) {
                throw new CborException("ByteString exceeds 64 bytes");
            }
            byte[] bytes = new byte[length.intValue()];
            if (input.readNBytes(bytes, 0, bytes.length) != bytes.length) {
                throw new CborException("Unexpected end of stream in byte string");
            }
            return bytes;
        }

        /**
         * Reads (and consumes) an integer, array or tag header and rejects what cborg rejects
         * or reads differently from cbor-java: an indefinite integer or tag, an array longer
         * than the remaining input (cbor-java reads 64-bit lengths as signed), and a tag that
         * does not wrap a byte string (a bignum, recognised only in the one-byte headers
         * {@code c2}/{@code c3}) or an array (a constructor). Tag numbers above 3 are checked
         * by {@link PlutusDataCborDecoder#fromDataItem}.
         */
        void checkHeader(int initialByte) throws CborException {
            MajorType type = MajorType.ofByte(initialByte);
            boolean indefinite = (initialByte & 31) == 31;
            if (type == MajorType.ARRAY) {
                if (!indefinite && getLengthAsBigInteger(initialByte)
                        .compareTo(BigInteger.valueOf(input.available())) > 0) {
                    throw new CborException("Array length exceeds remaining CBOR input");
                }
                return;
            }
            if (indefinite) {
                throw new CborException("Indefinite length is not allowed for " + type);
            }
            if (type != MajorType.TAG) {
                return;
            }
            BigInteger value = getLengthAsBigInteger(initialByte);
            // cborg reads tags 2 and 3 as bignums (TypeInteger) only in the headers c2/c3. In
            // any other header they are plain tags, which decodeConstr rejects.
            boolean bignum = initialByte == BIGNUM_POSITIVE || initialByte == BIGNUM_NEGATIVE;
            if (!bignum && value.compareTo(BigInteger.valueOf(3)) <= 0) {
                throw new CborException("Unrecognized tag " + value);
            }
            int content = input.read();
            MajorType expected = bignum ? MajorType.BYTE_STRING : MajorType.ARRAY;
            if (content == -1 || MajorType.ofByte(content) != expected) {
                throw new CborException("Tag " + value + (bignum ? " must wrap a byte string" : " must wrap an array"));
            }
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
