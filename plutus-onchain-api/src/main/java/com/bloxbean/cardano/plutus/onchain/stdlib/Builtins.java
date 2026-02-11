package com.bloxbean.cardano.plutus.onchain.stdlib;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Raw UPLC builtin operations exposed as Java static methods.
 * <p>
 * These methods bridge the gap between Java stdlib source files and the UPLC machine.
 * On-chain, calls to these methods are replaced by their corresponding UPLC builtins
 * via the {@code StdlibRegistry}. Off-chain, these JVM implementations provide
 * executable behavior for testing and debugging.
 * <p>
 * All on-chain stdlib libraries ({@code MathLib}, {@code ListsLib}, etc.) are built
 * on top of these primitives.
 */
public final class Builtins {

    private Builtins() {}

    // =========================================================================
    // List operations
    // =========================================================================

    /** Return the first element of a Data list. */
    public static PlutusData headList(PlutusData list) {
        return toList(list).getFirst();
    }

    /** Return all elements except the first. */
    public static PlutusData tailList(PlutusData list) {
        var items = toList(list);
        return new PlutusData.ListData(new ArrayList<>(items.subList(1, items.size())));
    }

    /** Return true if the list is empty. */
    public static boolean nullList(PlutusData list) {
        return toList(list).isEmpty();
    }

    /** Prepend an element to a Data list. */
    public static PlutusData mkCons(PlutusData elem, PlutusData list) {
        var items = new ArrayList<PlutusData>();
        items.add(elem);
        items.addAll(toList(list));
        return new PlutusData.ListData(items);
    }

    /** Create an empty Data list. */
    public static PlutusData mkNilData() {
        return new PlutusData.ListData(List.of());
    }

    // =========================================================================
    // Pair operations
    // =========================================================================

    /** Return the first element of a pair (encoded as Constr(0, [fst, snd])). */
    public static PlutusData fstPair(PlutusData pair) {
        if (pair instanceof PlutusData.Constr c && c.fields().size() >= 2) {
            return c.fields().get(0);
        }
        throw new IllegalArgumentException("fstPair: expected pair, got " + pair);
    }

    /** Return the second element of a pair. */
    public static PlutusData sndPair(PlutusData pair) {
        if (pair instanceof PlutusData.Constr c && c.fields().size() >= 2) {
            return c.fields().get(1);
        }
        throw new IllegalArgumentException("sndPair: expected pair, got " + pair);
    }

    /** Create a pair of Data values as a Map entry. */
    public static PlutusData mkPairData(PlutusData fst, PlutusData snd) {
        return new PlutusData.Constr(0, List.of(fst, snd)); // Pair encoding
    }

    /** Create an empty list of pairs (for map construction). */
    public static PlutusData mkNilPairData() {
        return new PlutusData.ListData(List.of()); // Empty pair list
    }

    // =========================================================================
    // Data encode
    // =========================================================================

    /** Construct a Data value from tag and fields. */
    public static PlutusData constrData(long tag, PlutusData fields) {
        return new PlutusData.Constr((int) tag, toList(fields));
    }

    /** Wrap an integer as IntData. */
    public static PlutusData iData(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    /** Wrap a byte string as BytesData. */
    public static PlutusData bData(PlutusData bs) {
        if (bs instanceof PlutusData.BytesData) {
            return bs;
        }
        throw new IllegalArgumentException("bData: expected BytesData, got " + bs);
    }

    /** Wrap a list as ListData. */
    public static PlutusData listData(PlutusData list) {
        return list; // already a ListData
    }

    /** Wrap a map as MapData. */
    public static PlutusData mapData(PlutusData map) {
        return map; // already a Map
    }

    // =========================================================================
    // Data decode
    // =========================================================================

    /** Deconstruct a Constr into (tag, fields). Returns Constr(0, [IntData(tag), ListData(fields)]). */
    public static PlutusData unConstrData(PlutusData data) {
        if (data instanceof PlutusData.Constr c) {
            return new PlutusData.Constr(0, List.of(
                    new PlutusData.IntData(BigInteger.valueOf(c.tag())),
                    new PlutusData.ListData(c.fields())));
        }
        throw new IllegalArgumentException("unConstrData: expected Constr, got " + data);
    }

    /** Extract integer value from IntData. */
    public static long unIData(PlutusData data) {
        if (data instanceof PlutusData.IntData i) {
            return i.value().longValueExact();
        }
        throw new IllegalArgumentException("unIData: expected IntData, got " + data);
    }

    /** Extract byte string from BytesData. */
    public static PlutusData unBData(PlutusData data) {
        if (data instanceof PlutusData.BytesData) {
            return data;
        }
        throw new IllegalArgumentException("unBData: expected BytesData, got " + data);
    }

    /** Extract list from ListData. */
    public static PlutusData unListData(PlutusData data) {
        if (data instanceof PlutusData.ListData) {
            return data;
        }
        throw new IllegalArgumentException("unListData: expected ListData, got " + data);
    }

    /** Extract map from Map. */
    public static PlutusData unMapData(PlutusData data) {
        if (data instanceof PlutusData.Map) {
            return data;
        }
        throw new IllegalArgumentException("unMapData: expected Map, got " + data);
    }

    // =========================================================================
    // Data comparison
    // =========================================================================

    /** Check structural equality of two Data values. */
    public static boolean equalsData(PlutusData a, PlutusData b) {
        return a.equals(b);
    }

    // =========================================================================
    // Error / Trace
    // =========================================================================

    /** Unconditionally abort execution. */
    public static PlutusData error() {
        throw new RuntimeException("Plutus script error");
    }

    /** Trace a message and return the second argument. */
    public static PlutusData trace(String message, PlutusData value) {
        System.out.println("[TRACE] " + message);
        return value;
    }

    // =========================================================================
    // Data decomposition helpers
    // =========================================================================

    /** Extract the constructor tag from a Constr Data value. On-chain: FstPair(UnConstrData(data)). */
    public static long constrTag(PlutusData data) {
        return unIData(fstPair(unConstrData(data)));
    }

    /** Extract the constructor fields list from a Constr Data value. On-chain: SndPair(UnConstrData(data)). */
    public static PlutusData constrFields(PlutusData data) {
        return sndPair(unConstrData(data));
    }

    // =========================================================================
    // ByteString operations
    // =========================================================================

    /** Get the byte at a given index in a bytestring. Returns 0-255. */
    public static long indexByteString(PlutusData bs, long index) {
        return toBytes(bs)[(int) index] & 0xFF;
    }

    /** Prepend a byte (0-255) to a bytestring. */
    public static PlutusData consByteString(long byte_, PlutusData bs) {
        var bytes = toBytes(bs);
        var result = new byte[bytes.length + 1];
        result[0] = (byte) byte_;
        System.arraycopy(bytes, 0, result, 1, bytes.length);
        return new PlutusData.BytesData(result);
    }

    /** Extract a slice of a bytestring: slice(start, length, bs). */
    public static PlutusData sliceByteString(long start, long length, PlutusData bs) {
        var bytes = toBytes(bs);
        int s = Math.max(0, (int) start);
        int len = Math.max(0, Math.min((int) length, bytes.length - s));
        var result = new byte[len];
        if (len > 0) System.arraycopy(bytes, s, result, 0, len);
        return new PlutusData.BytesData(result);
    }

    /** Get the length of a bytestring. */
    public static long lengthOfByteString(PlutusData bs) {
        return toBytes(bs).length;
    }

    /** Concatenate two bytestrings. */
    public static PlutusData appendByteString(PlutusData a, PlutusData b) {
        var ba = toBytes(a);
        var bb = toBytes(b);
        var result = new byte[ba.length + bb.length];
        System.arraycopy(ba, 0, result, 0, ba.length);
        System.arraycopy(bb, 0, result, ba.length, bb.length);
        return new PlutusData.BytesData(result);
    }

    /** Compare two bytestrings for equality. */
    public static boolean equalsByteString(PlutusData a, PlutusData b) {
        return Arrays.equals(toBytes(a), toBytes(b));
    }

    /** Lexicographic comparison: a < b. */
    public static boolean lessThanByteString(PlutusData a, PlutusData b) {
        return Arrays.compare(toBytes(a), toBytes(b)) < 0;
    }

    /** Lexicographic comparison: a <= b. */
    public static boolean lessThanEqualsByteString(PlutusData a, PlutusData b) {
        return Arrays.compare(toBytes(a), toBytes(b)) <= 0;
    }

    /** Convert integer to bytestring with given endianness and width. */
    public static PlutusData integerToByteString(boolean bigEndian, long width, long i) {
        var bi = BigInteger.valueOf(i);
        byte[] raw = bi.toByteArray();
        // Strip leading zero sign byte if present
        if (raw.length > 1 && raw[0] == 0) {
            raw = Arrays.copyOfRange(raw, 1, raw.length);
        }
        int w = (int) width;
        if (w > 0 && raw.length < w) {
            byte[] padded = new byte[w];
            System.arraycopy(raw, 0, padded, w - raw.length, raw.length);
            raw = padded;
        }
        if (!bigEndian) {
            reverseInPlace(raw);
        }
        return new PlutusData.BytesData(raw);
    }

    /** Convert bytestring to integer with given endianness. */
    public static long byteStringToInteger(boolean bigEndian, PlutusData bs) {
        byte[] bytes = toBytes(bs).clone();
        if (!bigEndian) {
            reverseInPlace(bytes);
        }
        return new BigInteger(1, bytes).longValue();
    }

    /** Encode a string as UTF-8 bytestring. */
    public static PlutusData encodeUtf8(PlutusData s) {
        throw new UnsupportedOperationException("Builtins.encodeUtf8() not supported in JVM mode");
    }

    /** Decode a UTF-8 bytestring to a string. */
    public static PlutusData decodeUtf8(PlutusData bs) {
        throw new UnsupportedOperationException("Builtins.decodeUtf8() not supported in JVM mode");
    }

    /** Serialise a Data value to its CBOR-encoded bytestring. */
    public static PlutusData serialiseData(PlutusData d) {
        throw new UnsupportedOperationException("Builtins.serialiseData() not supported in JVM mode");
    }

    /** Create a bytestring of n copies of a given byte value. */
    public static PlutusData replicateByte(long n, long byte_) {
        byte[] result = new byte[(int) n];
        Arrays.fill(result, (byte) byte_);
        return new PlutusData.BytesData(result);
    }

    /** Return an empty bytestring. */
    public static PlutusData emptyByteString() {
        return new PlutusData.BytesData(new byte[0]);
    }

    // =========================================================================
    // Crypto operations
    // =========================================================================

    /** SHA2-256 hash. Delegates to CryptoLib provider. */
    public static PlutusData sha2_256(PlutusData bs) {
        return CryptoLib.sha2_256(bs);
    }

    /** Blake2b-256 hash. Delegates to CryptoLib provider. */
    public static PlutusData blake2b_256(PlutusData bs) {
        return CryptoLib.blake2b_256(bs);
    }

    /** Verify an Ed25519 signature. Delegates to CryptoLib provider. */
    public static boolean verifyEd25519Signature(PlutusData key, PlutusData msg, PlutusData sig) {
        return CryptoLib.verifyEd25519Signature(key, msg, sig);
    }

    /** SHA3-256 hash. Delegates to CryptoLib provider. */
    public static PlutusData sha3_256(PlutusData bs) {
        return CryptoLib.sha3_256(bs);
    }

    /** Blake2b-224 hash. Delegates to CryptoLib provider. */
    public static PlutusData blake2b_224(PlutusData bs) {
        return CryptoLib.blake2b_224(bs);
    }

    /** Keccak-256 hash. Delegates to CryptoLib provider. */
    public static PlutusData keccak_256(PlutusData bs) {
        return CryptoLib.keccak_256(bs);
    }

    /** Verify ECDSA secp256k1 signature. */
    public static boolean verifyEcdsaSecp256k1Signature(PlutusData key, PlutusData msg, PlutusData sig) {
        throw new UnsupportedOperationException("Builtins.verifyEcdsaSecp256k1Signature() not supported in JVM mode");
    }

    /** Verify Schnorr secp256k1 signature. */
    public static boolean verifySchnorrSecp256k1Signature(PlutusData key, PlutusData msg, PlutusData sig) {
        throw new UnsupportedOperationException("Builtins.verifySchnorrSecp256k1Signature() not supported in JVM mode");
    }

    /** RIPEMD-160 hash. */
    public static PlutusData ripemd_160(PlutusData bs) {
        throw new UnsupportedOperationException("Builtins.ripemd_160() not supported in JVM mode");
    }

    // =========================================================================
    // Bitwise operations
    // =========================================================================

    /** Bitwise AND of two bytestrings with padding semantics. */
    public static PlutusData andByteString(boolean padding, PlutusData a, PlutusData b) {
        return bitwiseBinaryOp(padding, toBytes(a), toBytes(b), (x, y) -> (byte)(x & y));
    }

    /** Bitwise OR of two bytestrings with padding semantics. */
    public static PlutusData orByteString(boolean padding, PlutusData a, PlutusData b) {
        return bitwiseBinaryOp(padding, toBytes(a), toBytes(b), (x, y) -> (byte)(x | y));
    }

    /** Bitwise XOR of two bytestrings with padding semantics. */
    public static PlutusData xorByteString(boolean padding, PlutusData a, PlutusData b) {
        return bitwiseBinaryOp(padding, toBytes(a), toBytes(b), (x, y) -> (byte)(x ^ y));
    }

    /** Bitwise complement of a bytestring. */
    public static PlutusData complementByteString(PlutusData bs) {
        var bytes = toBytes(bs);
        var result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) result[i] = (byte) ~bytes[i];
        return new PlutusData.BytesData(result);
    }

    /** Read a bit at a given index. */
    public static boolean readBit(PlutusData bs, long index) {
        var bytes = toBytes(bs);
        int byteIdx = (int)(index / 8);
        int bitIdx = (int)(index % 8);
        return ((bytes[bytes.length - 1 - byteIdx] >> bitIdx) & 1) == 1;
    }

    /** Write bits at given indices. */
    public static PlutusData writeBits(PlutusData bs, PlutusData indices, boolean value) {
        var bytes = toBytes(bs).clone();
        var idxList = toList(indices);
        for (var idx : idxList) {
            long i = ((PlutusData.IntData) idx).value().longValue();
            int byteIdx = (int)(i / 8);
            int bitIdx = (int)(i % 8);
            int pos = bytes.length - 1 - byteIdx;
            if (value) {
                bytes[pos] |= (byte)(1 << bitIdx);
            } else {
                bytes[pos] &= (byte) ~(1 << bitIdx);
            }
        }
        return new PlutusData.BytesData(bytes);
    }

    /** Shift a bytestring by n bits. */
    public static PlutusData shiftByteString(PlutusData bs, long n) {
        throw new UnsupportedOperationException("Builtins.shiftByteString() not fully supported in JVM mode");
    }

    /** Rotate a bytestring by n bits. */
    public static PlutusData rotateByteString(PlutusData bs, long n) {
        throw new UnsupportedOperationException("Builtins.rotateByteString() not fully supported in JVM mode");
    }

    /** Count the number of set bits (1s) in a bytestring. */
    public static long countSetBits(PlutusData bs) {
        long count = 0;
        for (byte b : toBytes(bs)) count += Integer.bitCount(b & 0xFF);
        return count;
    }

    /** Find the index of the first set bit, or -1 if none. */
    public static long findFirstSetBit(PlutusData bs) {
        var bytes = toBytes(bs);
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] != 0) {
                int bitInByte = Integer.numberOfTrailingZeros(bytes[i] & 0xFF);
                return (long)(bytes.length - 1 - i) * 8 + bitInByte;
            }
        }
        return -1;
    }

    // =========================================================================
    // Math operations
    // =========================================================================

    /** Modular exponentiation: (base^exp) mod modulus. */
    public static long expModInteger(long base, long exp, long mod) {
        return BigInteger.valueOf(base).modPow(BigInteger.valueOf(exp), BigInteger.valueOf(mod)).longValue();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static List<PlutusData> toList(PlutusData data) {
        if (data instanceof PlutusData.ListData ld) {
            return ld.items();
        }
        throw new IllegalArgumentException("Expected ListData, got " + data.getClass().getSimpleName());
    }

    private static byte[] toBytes(PlutusData data) {
        if (data instanceof PlutusData.BytesData bd) {
            return bd.value();
        }
        throw new IllegalArgumentException("Expected BytesData, got " + data.getClass().getSimpleName());
    }

    private static void reverseInPlace(byte[] arr) {
        for (int i = 0; i < arr.length / 2; i++) {
            byte tmp = arr[i];
            arr[i] = arr[arr.length - 1 - i];
            arr[arr.length - 1 - i] = tmp;
        }
    }

    @FunctionalInterface
    private interface ByteOp {
        byte apply(byte a, byte b);
    }

    private static PlutusData bitwiseBinaryOp(boolean padding, byte[] a, byte[] b, ByteOp op) {
        int maxLen = Math.max(a.length, b.length);
        int minLen = Math.min(a.length, b.length);
        int resultLen = padding ? maxLen : minLen;
        var result = new byte[resultLen];
        // Align from the right (LSB)
        for (int i = 0; i < resultLen; i++) {
            int ai = a.length - 1 - i;
            int bi = b.length - 1 - i;
            byte av = (ai >= 0) ? a[ai] : 0;
            byte bv = (bi >= 0) ? b[bi] : 0;
            result[resultLen - 1 - i] = op.apply(av, bv);
        }
        return new PlutusData.BytesData(result);
    }
}
