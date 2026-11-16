package org.julclang.tools.params;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * Reads Plutus data in the canonical CBOR encoding that {@link PlutusDataCborEncoder} writes (Plutus's
 * {@code encodeData}), with an explicit stack, so that a deep value needs no Java stack (ADR-077 M2).
 * <p>
 * It reads the CBOR shapes of Plutus data, then requires the bytes to equal the canonical encoding of what it read.
 * So it accepts exactly the canonical encodings; anything else is refused, never normalised. It is not a general
 * Plutus data decoder: the VM keeps {@code PlutusDataCborDecoder}, which also reads non-canonical encodings.
 */
final class CanonicalDataCbor {

    private CanonicalDataCbor() {}

    /** Why bytes are refused; the caller words the error for its context. */
    static final class NotCanonical extends RuntimeException {
        NotCanonical(String message) {
            super(message);
        }
    }

    /** One value that fills {@code bytes}, in the canonical encoding. */
    static PlutusData read(byte[] bytes) {
        var reader = new Reader(bytes);
        PlutusData data = reader.readItem();
        if (reader.pos != bytes.length) {
            throw new NotCanonical((bytes.length - reader.pos) + " bytes follow the Plutus data item");
        }
        requireCanonical(data, bytes);
        return data;
    }

    private static void requireCanonical(PlutusData data, byte[] bytes) {
        byte[] canonical = PlutusDataCborEncoder.encode(data);
        if (!Arrays.equals(bytes, canonical)) {
            String expected = canonical.length <= 64 ? " (expected " + HexFormat.of().formatHex(canonical) + ")" : "";
            throw new NotCanonical("not in the canonical encoding Plutus writes" + expected);
        }
    }

    /** An open array or map: its items so far, and how many it still needs (-1 when indefinite). */
    private static final class Frame {
        final int kind;
        final BigInteger constructor;
        long remaining;
        final List<PlutusData> items = new ArrayList<>();

        Frame(int kind, BigInteger constructor, long remaining) {
            this.kind = kind;
            this.constructor = constructor;
            this.remaining = remaining;
        }

        PlutusData build() {
            return switch (kind) {
                case LIST -> new PlutusData.ListData(items);
                case FIELDS -> PlutusData.ConstrData.fromUnsignedTag(constructor, items);
                default -> {
                    if (items.size() % 2 != 0) throw new NotCanonical("a map ends between a key and its value");
                    var pairs = new ArrayList<PlutusData.Pair>(items.size() / 2);
                    for (int i = 0; i < items.size(); i += 2) pairs.add(new PlutusData.Pair(items.get(i), items.get(i + 1)));
                    yield new PlutusData.MapData(pairs);
                }
            };
        }
    }

    private static final int LIST = 0;
    private static final int FIELDS = 1;
    private static final int MAP = 2;
    private static final long INDEFINITE = -1;

    private static final class Reader {
        final byte[] bytes;
        int pos;

        Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        PlutusData readItem() {
            var stack = new ArrayDeque<Frame>();
            while (true) {
                PlutusData value = null;
                int initial = next();
                Frame top = stack.peek();
                if (initial == 0xff) {
                    if (top == null || top.remaining != INDEFINITE) throw new NotCanonical("unexpected break byte");
                    stack.pop();
                    value = top.build();
                } else {
                    int major = initial >>> 5;
                    int info = initial & 31;
                    switch (major) {
                        case 0 -> value = new PlutusData.IntData(argument(info));
                        case 1 -> value = new PlutusData.IntData(argument(info).negate().subtract(BigInteger.ONE));
                        case 2 -> value = new PlutusData.BytesData(byteString(info));
                        case 4 -> value = open(stack, LIST, null, length(info));
                        case 5 -> {
                            long length = length(info);
                            value = open(stack, MAP, null, length == INDEFINITE ? INDEFINITE : 2 * length);
                        }
                        case 6 -> value = tagged(stack, argument(info));
                        default -> throw new NotCanonical("CBOR major type " + major + " is not Plutus data");
                    }
                }
                // Hand the finished value to the arrays and maps it completes.
                while (value != null) {
                    Frame parent = stack.peek();
                    if (parent == null) return value;
                    parent.items.add(value);
                    value = null;
                    if (parent.remaining != INDEFINITE && parent.items.size() == parent.remaining) {
                        stack.pop();
                        value = parent.build();
                    }
                }
            }
        }

        private PlutusData tagged(ArrayDeque<Frame> stack, BigInteger tag) {
            long t = tag.bitLength() > 31 ? -1 : tag.longValue();
            if (t == 2 || t == 3) {
                int initial = next();
                if (initial >>> 5 != 2) throw new NotCanonical("a bignum tag must wrap a byte string");
                BigInteger magnitude = new BigInteger(1, byteString(initial & 31));
                return new PlutusData.IntData(t == 2 ? magnitude : magnitude.negate().subtract(BigInteger.ONE));
            }
            BigInteger constructor;
            if (t >= 121 && t <= 127) {
                constructor = BigInteger.valueOf(t - 121);
            } else if (t >= 1280 && t <= 1400) {
                constructor = BigInteger.valueOf(t - 1280 + 7);
            } else if (t == 102) {
                // [constructor index, fields], the index an unsigned integer.
                if (next() != 0x82) throw new NotCanonical("tag 102 must wrap a definite array of two items");
                int initial = next();
                if (initial >>> 5 != 0) throw new NotCanonical("the tag 102 constructor index must be an unsigned integer");
                constructor = argument(initial & 31);
            } else {
                throw new NotCanonical("CBOR tag " + tag + " is not Plutus data");
            }
            int initial = next();
            if (initial >>> 5 != 4) throw new NotCanonical("a constructor's fields must be an array");
            return open(stack, FIELDS, constructor, length(initial & 31));
        }

        /** Opens an array or map; an empty definite one is complete at once. */
        private PlutusData open(ArrayDeque<Frame> stack, int kind, BigInteger constructor, long remaining) {
            var frame = new Frame(kind, constructor, remaining);
            if (remaining == 0) return frame.build();
            stack.push(frame);
            return null;
        }

        /** A definite length, bounded by the bytes left, or {@link #INDEFINITE}. */
        private long length(int info) {
            if (info == 31) return INDEFINITE;
            BigInteger length = argument(info);
            if (length.compareTo(BigInteger.valueOf(bytes.length - pos)) > 0) {
                throw new NotCanonical("a length exceeds the bytes left");
            }
            return length.longValue();
        }

        private byte[] byteString(int info) {
            if (info != 31) return take(length(info));
            var out = new ByteArrayOutputStream();
            while (true) {
                int initial = next();
                if (initial == 0xff) return out.toByteArray();
                if (initial >>> 5 != 2 || (initial & 31) == 31) {
                    throw new NotCanonical("an indefinite byte string's chunks must be definite byte strings");
                }
                out.writeBytes(take(length(initial & 31)));
            }
        }

        private byte[] take(long length) {
            byte[] out = Arrays.copyOfRange(bytes, pos, pos + (int) length);
            pos += (int) length;
            return out;
        }

        private BigInteger argument(int info) {
            if (info < 24) return BigInteger.valueOf(info);
            int size = switch (info) {
                case 24 -> 1;
                case 25 -> 2;
                case 26 -> 4;
                case 27 -> 8;
                default -> throw new NotCanonical("CBOR additional information " + info + " is not allowed here");
            };
            if (pos + size > bytes.length) throw new NotCanonical("the CBOR item is truncated");
            BigInteger value = new BigInteger(1, Arrays.copyOfRange(bytes, pos, pos + size));
            pos += size;
            return value;
        }

        private int next() {
            if (pos >= bytes.length) throw new NotCanonical("the CBOR item is truncated");
            return bytes[pos++] & 0xFF;
        }
    }

    /**
     * The reader the FLAT decoder uses for the script's data constants: each must be one canonical item with nothing
     * after it. The error names the constant by its position among the script's data constants.
     */
    static final class ScriptConstants implements Function<byte[], PlutusData> {
        private int count;

        @Override
        public PlutusData apply(byte[] cbor) {
            count++;
            try {
                return read(cbor);
            } catch (NotCanonical e) {
                throw ApplyParamsException.invalidRequest("The script's data constant number " + count
                        + " is not in the canonical Data encoding: " + e.getMessage()
                        + ". A non-canonical script is refused rather than silently normalised.");
            }
        }
    }
}
