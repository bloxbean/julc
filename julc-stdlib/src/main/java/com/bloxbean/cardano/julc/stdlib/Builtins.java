package com.bloxbean.cardano.julc.stdlib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcArrayList;
import com.bloxbean.cardano.julc.core.types.JulcAssocMap;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.PlutusDataConvertible;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
    // CryptoProvider SPI
    // =========================================================================

    private static volatile CryptoProvider cryptoProvider;

    /** Set the crypto provider for off-chain execution. */
    public static void setCryptoProvider(CryptoProvider provider) {
        cryptoProvider = provider;
    }

    /** Get the current crypto provider, or throw if not set. */
    private static CryptoProvider requireCryptoProvider() {
        CryptoProvider p = cryptoProvider;
        if (p == null) {
            throw new UnsupportedOperationException(
                    "Builtins crypto operations require a CryptoProvider for off-chain use. "
                    + "Call Builtins.setCryptoProvider(new JvmCryptoProvider()) in test setup, "
                    + "or extend ContractTest which sets it up automatically.");
        }
        return p;
    }

    // =========================================================================
    // List operations
    // =========================================================================

    /** Return the first element of a Data list. */
    public static PlutusData headList(PlutusData list) {
        return toList(list).getFirst();
    }

    /** Return all elements except the first. */
    public static PlutusData.ListData tailList(PlutusData list) {
        var items = toList(list);
        return new PlutusData.ListData(new ArrayList<>(items.subList(1, items.size())));
    }

    /** Return true if the list is empty. */
    public static boolean nullList(PlutusData list) {
        return toList(list).isEmpty();
    }

    /** Prepend an element to a Data list. */
    public static PlutusData.ListData mkCons(PlutusData elem, PlutusData list) {
        var items = new ArrayList<PlutusData>();
        items.add(elem);
        items.addAll(toList(list));
        return new PlutusData.ListData(items);
    }

    /** Create an empty Data list. */
    public static PlutusData.ListData mkNilData() {
        return new PlutusData.ListData(List.of());
    }

    // =========================================================================
    // Pair operations
    // =========================================================================

    /** Return the first element of a pair (encoded as ConstrData(0, [fst, snd])). */
    public static PlutusData fstPair(PlutusData pair) {
        if (pair instanceof PlutusData.ConstrData c && c.fields().size() >= 2) {
            return c.fields().get(0);
        }
        throw new IllegalArgumentException("fstPair: expected pair, got " + pair);
    }

    /** Return the second element of a pair. */
    public static PlutusData sndPair(PlutusData pair) {
        if (pair instanceof PlutusData.ConstrData c && c.fields().size() >= 2) {
            return c.fields().get(1);
        }
        throw new IllegalArgumentException("sndPair: expected pair, got " + pair);
    }

    /** Create a pair of Data values as a Map entry. */
    public static PlutusData.ConstrData mkPairData(PlutusData fst, PlutusData snd) {
        return new PlutusData.ConstrData(0, List.of(fst, snd)); // Pair encoding
    }

    /** Create an empty list of pairs (for map construction). */
    public static PlutusData.ListData mkNilPairData() {
        return new PlutusData.ListData(List.of()); // Empty pair list
    }

    // =========================================================================
    // Data encode
    // =========================================================================

    /** Construct a Data value from tag and fields. */
    public static PlutusData.ConstrData constrData(long tag, PlutusData fields) {
        return new PlutusData.ConstrData((int) tag, toList(fields));
    }

    /** Wrap an integer as IntData. */
    public static PlutusData.IntData iData(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    /** Wrap a BigInteger as IntData. */
    public static PlutusData.IntData iData(BigInteger value) {
        return new PlutusData.IntData(value);
    }

    /** Wrap a byte string as BytesData. */
    public static PlutusData.BytesData bData(PlutusData.BytesData bs) {
        if (bs instanceof PlutusData.BytesData bd) {
            return bd;
        }
        throw new IllegalArgumentException("bData: expected BytesData, got " + bs);
    }

    /** Wrap a raw byte array as BytesData. */
    public static PlutusData.BytesData bData(byte[] bs) {
        return new PlutusData.BytesData(bs);
    }

    /** Wrap a list as ListData. */
    public static PlutusData.ListData listData(PlutusData list) {
        return (PlutusData.ListData) list; // already a ListData
    }

    /** Wrap a map as MapData. */
    public static PlutusData.MapData mapData(PlutusData map) {
        return (PlutusData.MapData) map; // already a MapData
    }

    // =========================================================================
    // Data decode
    // =========================================================================

    /** Deconstruct a Constr into (tag, fields). Returns ConstrData(0, [IntData(tag), ListData(fields)]). */
    public static PlutusData.ConstrData unConstrData(PlutusData data) {
        if (data instanceof PlutusData.ConstrData c) {
            return new PlutusData.ConstrData(0, List.of(
                    new PlutusData.IntData(BigInteger.valueOf(c.tag())),
                    new PlutusData.ListData(c.fields())));
        }
        throw new IllegalArgumentException("unConstrData: expected Constr, got " + data);
    }

    /** Extract integer value from IntData. */
    public static BigInteger unIData(PlutusData data) {
        if (data instanceof PlutusData.IntData i) {
            return i.value();
        }
        throw new IllegalArgumentException("unIData: expected IntData, got " + data);
    }

    /** Extract byte string from BytesData. */
    public static byte[] unBData(PlutusData data) {
        if (data instanceof PlutusData.BytesData bd) {
            return bd.value();
        }
        throw new IllegalArgumentException("unBData: expected BytesData, got " + data);
    }

    /** Extract list from ListData. */
    public static PlutusData.ListData unListData(PlutusData data) {
        if (data instanceof PlutusData.ListData ld) {
            return ld;
        }
        throw new IllegalArgumentException("unListData: expected ListData, got " + data);
    }

    /** Extract map from MapData. */
    public static PlutusData.MapData unMapData(PlutusData data) {
        if (data instanceof PlutusData.MapData md) {
            return md;
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
        return unIData(fstPair(unConstrData(data))).longValueExact();
    }

    /** Extract the constructor fields list from a Constr Data value. On-chain: SndPair(UnConstrData(data)). */
    public static PlutusData.ListData constrFields(PlutusData data) {
        return (PlutusData.ListData) sndPair(unConstrData(data));
    }

    // =========================================================================
    // ByteString operations
    // =========================================================================

    /** Get the byte at a given index in a bytestring. Returns 0-255. */
    public static long indexByteString(PlutusData.BytesData bs, long index) {
        return toBytes(bs)[(int) index] & 0xFF;
    }

    /** Prepend a byte (0-255) to a bytestring. */
    public static PlutusData.BytesData consByteString(long byte_, PlutusData.BytesData bs) {
        var bytes = toBytes(bs);
        var result = new byte[bytes.length + 1];
        result[0] = (byte) byte_;
        System.arraycopy(bytes, 0, result, 1, bytes.length);
        return new PlutusData.BytesData(result);
    }

    /** Extract a slice of a bytestring: slice(start, length, bs). */
    public static PlutusData.BytesData sliceByteString(long start, long length, PlutusData.BytesData bs) {
        var bytes = toBytes(bs);
        int s = Math.max(0, (int) start);
        int len = Math.max(0, Math.min((int) length, bytes.length - s));
        var result = new byte[len];
        if (len > 0) System.arraycopy(bytes, s, result, 0, len);
        return new PlutusData.BytesData(result);
    }

    /** Get the length of a bytestring. */
    public static long lengthOfByteString(PlutusData.BytesData bs) {
        return toBytes(bs).length;
    }

    /** Concatenate two bytestrings. */
    public static PlutusData.BytesData appendByteString(PlutusData.BytesData a, PlutusData.BytesData b) {
        var ba = toBytes(a);
        var bb = toBytes(b);
        var result = new byte[ba.length + bb.length];
        System.arraycopy(ba, 0, result, 0, ba.length);
        System.arraycopy(bb, 0, result, ba.length, bb.length);
        return new PlutusData.BytesData(result);
    }

    /** Compare two bytestrings for equality. */
    public static boolean equalsByteString(PlutusData.BytesData a, PlutusData.BytesData b) {
        return Arrays.equals(toBytes(a), toBytes(b));
    }

    /** Lexicographic comparison: a < b. */
    public static boolean lessThanByteString(PlutusData.BytesData a, PlutusData.BytesData b) {
        return Arrays.compare(toBytes(a), toBytes(b)) < 0;
    }

    /** Lexicographic comparison: a <= b. */
    public static boolean lessThanEqualsByteString(PlutusData.BytesData a, PlutusData.BytesData b) {
        return Arrays.compare(toBytes(a), toBytes(b)) <= 0;
    }

    /** Convert integer to bytestring with given endianness and width. */
    public static byte[] integerToByteString(boolean bigEndian, long width, long i) {
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
        return raw;
    }

    /** Convert bytestring to integer with given endianness. Returns arbitrary-precision BigInteger. */
    public static BigInteger byteStringToInteger(boolean bigEndian, PlutusData.BytesData bs) {
        byte[] bytes = toBytes(bs).clone();
        if (!bigEndian) {
            reverseInPlace(bytes);
        }
        return new BigInteger(1, bytes);
    }

    /** Encode a string as UTF-8 bytestring. */
    public static byte[] encodeUtf8(PlutusData s) {
        if (s instanceof PlutusData.BytesData bd) {
            // On-chain, strings are ByteStrings; off-chain treat as pass-through
            return bd.value();
        }
        throw new IllegalArgumentException("encodeUtf8: expected BytesData (string), got " + s);
    }

    /** Encode a Java string as UTF-8 bytestring. */
    public static PlutusData.BytesData encodeUtf8(String s) {
        return new PlutusData.BytesData(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Decode a UTF-8 bytestring to a string. */
    public static PlutusData decodeUtf8(PlutusData.BytesData bs) {
        // Return as BytesData — on-chain strings are ByteStrings
        return bs;
    }

    /** Decode a UTF-8 bytestring to a Java String. */
    public static String decodeUtf8String(PlutusData.BytesData bs) {
        return new String(toBytes(bs), StandardCharsets.UTF_8);
    }

    /** Serialise a Data value to its CBOR-encoded bytestring. */
    public static byte[] serialiseData(PlutusData d) {
        return com.bloxbean.cardano.julc.core.cbor.PlutusDataCborEncoder.encode(d);
    }

    /** Create a bytestring of n copies of a given byte value. */
    public static byte[] replicateByte(long n, long byte_) {
        byte[] result = new byte[(int) n];
        Arrays.fill(result, (byte) byte_);
        return result;
    }

    /** Return an empty bytestring. */
    public static byte[] emptyByteString() {
        return new byte[0];
    }

    // =========================================================================
    // ByteString operations — byte[] overloads
    // =========================================================================

    /** @see #indexByteString(PlutusData.BytesData, long) */
    public static long indexByteString(byte[] bs, long index) {
        return indexByteString(new PlutusData.BytesData(bs), index);
    }

    /** @see #consByteString(long, PlutusData.BytesData) */
    public static byte[] consByteString(long byte_, byte[] bs) {
        return consByteString(byte_, new PlutusData.BytesData(bs)).value();
    }

    /** @see #sliceByteString(long, long, PlutusData.BytesData) */
    public static byte[] sliceByteString(long start, long length, byte[] bs) {
        return sliceByteString(start, length, new PlutusData.BytesData(bs)).value();
    }

    /** @see #lengthOfByteString(PlutusData.BytesData) */
    public static long lengthOfByteString(byte[] bs) {
        return lengthOfByteString(new PlutusData.BytesData(bs));
    }

    /** @see #appendByteString(PlutusData.BytesData, PlutusData.BytesData) */
    public static byte[] appendByteString(byte[] a, byte[] b) {
        return appendByteString(new PlutusData.BytesData(a), new PlutusData.BytesData(b)).value();
    }

    /** @see #equalsByteString(PlutusData.BytesData, PlutusData.BytesData) */
    public static boolean equalsByteString(byte[] a, byte[] b) {
        return equalsByteString(new PlutusData.BytesData(a), new PlutusData.BytesData(b));
    }

    /** @see #equalsByteString(PlutusData.BytesData, PlutusData.BytesData) */
    public static boolean equalsByteString(byte[] a, PlutusData.BytesData b) {
        return Arrays.equals(a, toBytes(b));
    }

    /** @see #lessThanByteString(PlutusData.BytesData, PlutusData.BytesData) */
    public static boolean lessThanByteString(byte[] a, byte[] b) {
        return lessThanByteString(new PlutusData.BytesData(a), new PlutusData.BytesData(b));
    }

    /** @see #lessThanEqualsByteString(PlutusData.BytesData, PlutusData.BytesData) */
    public static boolean lessThanEqualsByteString(byte[] a, byte[] b) {
        return lessThanEqualsByteString(new PlutusData.BytesData(a), new PlutusData.BytesData(b));
    }

    /** @see #byteStringToInteger(boolean, PlutusData.BytesData) */
    public static BigInteger byteStringToInteger(boolean bigEndian, byte[] bs) {
        return byteStringToInteger(bigEndian, new PlutusData.BytesData(bs));
    }

    /** @see #decodeUtf8(PlutusData.BytesData) */
    public static PlutusData decodeUtf8(byte[] bs) {
        return decodeUtf8(new PlutusData.BytesData(bs));
    }

    // =========================================================================
    // Crypto operations
    // =========================================================================

    /** SHA2-256 hash. */
    public static PlutusData.BytesData sha2_256(PlutusData.BytesData bs) {
        return new PlutusData.BytesData(requireCryptoProvider().sha2_256(toBytes(bs)));
    }

    /** Blake2b-256 hash. */
    public static PlutusData.BytesData blake2b_256(PlutusData.BytesData bs) {
        return new PlutusData.BytesData(requireCryptoProvider().blake2b_256(toBytes(bs)));
    }

    /** Verify an Ed25519 signature. */
    public static boolean verifyEd25519Signature(PlutusData.BytesData key, PlutusData.BytesData msg, PlutusData.BytesData sig) {
        return requireCryptoProvider().verifyEd25519Signature(toBytes(key), toBytes(msg), toBytes(sig));
    }

    /** SHA3-256 hash. */
    public static PlutusData.BytesData sha3_256(PlutusData.BytesData bs) {
        return new PlutusData.BytesData(requireCryptoProvider().sha3_256(toBytes(bs)));
    }

    /** Blake2b-224 hash. */
    public static PlutusData.BytesData blake2b_224(PlutusData.BytesData bs) {
        return new PlutusData.BytesData(requireCryptoProvider().blake2b_224(toBytes(bs)));
    }

    /** Keccak-256 hash. */
    public static PlutusData.BytesData keccak_256(PlutusData.BytesData bs) {
        return new PlutusData.BytesData(requireCryptoProvider().keccak_256(toBytes(bs)));
    }

    /** Verify ECDSA secp256k1 signature. */
    public static boolean verifyEcdsaSecp256k1Signature(PlutusData.BytesData key, PlutusData.BytesData msg, PlutusData.BytesData sig) {
        return requireCryptoProvider().verifyEcdsaSecp256k1Signature(toBytes(key), toBytes(msg), toBytes(sig));
    }

    /** Verify Schnorr secp256k1 signature. */
    public static boolean verifySchnorrSecp256k1Signature(PlutusData.BytesData key, PlutusData.BytesData msg, PlutusData.BytesData sig) {
        return requireCryptoProvider().verifySchnorrSecp256k1Signature(toBytes(key), toBytes(msg), toBytes(sig));
    }

    /** RIPEMD-160 hash. */
    public static PlutusData.BytesData ripemd_160(PlutusData.BytesData bs) {
        return new PlutusData.BytesData(requireCryptoProvider().ripemd_160(toBytes(bs)));
    }

    // =========================================================================
    // Crypto operations — byte[] overloads
    // =========================================================================

    /** @see #sha2_256(PlutusData.BytesData) */
    public static byte[] sha2_256(byte[] bs) {
        return sha2_256(new PlutusData.BytesData(bs)).value();
    }

    /** @see #blake2b_256(PlutusData.BytesData) */
    public static byte[] blake2b_256(byte[] bs) {
        return blake2b_256(new PlutusData.BytesData(bs)).value();
    }

    /** @see #verifyEd25519Signature(PlutusData.BytesData, PlutusData.BytesData, PlutusData.BytesData) */
    public static boolean verifyEd25519Signature(byte[] key, byte[] msg, byte[] sig) {
        return verifyEd25519Signature(new PlutusData.BytesData(key), new PlutusData.BytesData(msg), new PlutusData.BytesData(sig));
    }

    /** @see #sha3_256(PlutusData.BytesData) */
    public static byte[] sha3_256(byte[] bs) {
        return sha3_256(new PlutusData.BytesData(bs)).value();
    }

    /** @see #blake2b_224(PlutusData.BytesData) */
    public static byte[] blake2b_224(byte[] bs) {
        return blake2b_224(new PlutusData.BytesData(bs)).value();
    }

    /** @see #keccak_256(PlutusData.BytesData) */
    public static byte[] keccak_256(byte[] bs) {
        return keccak_256(new PlutusData.BytesData(bs)).value();
    }

    /** @see #verifyEcdsaSecp256k1Signature(PlutusData.BytesData, PlutusData.BytesData, PlutusData.BytesData) */
    public static boolean verifyEcdsaSecp256k1Signature(byte[] key, byte[] msg, byte[] sig) {
        return verifyEcdsaSecp256k1Signature(new PlutusData.BytesData(key), new PlutusData.BytesData(msg), new PlutusData.BytesData(sig));
    }

    /** @see #verifySchnorrSecp256k1Signature(PlutusData.BytesData, PlutusData.BytesData, PlutusData.BytesData) */
    public static boolean verifySchnorrSecp256k1Signature(byte[] key, byte[] msg, byte[] sig) {
        return verifySchnorrSecp256k1Signature(new PlutusData.BytesData(key), new PlutusData.BytesData(msg), new PlutusData.BytesData(sig));
    }

    /** @see #ripemd_160(PlutusData.BytesData) */
    public static byte[] ripemd_160(byte[] bs) {
        return ripemd_160(new PlutusData.BytesData(bs)).value();
    }

    // =========================================================================
    // Bitwise operations
    // =========================================================================

    /** Bitwise AND of two bytestrings with padding semantics. */
    public static PlutusData.BytesData andByteString(boolean padding, PlutusData.BytesData a, PlutusData.BytesData b) {
        return bitwiseBinaryOp(padding, toBytes(a), toBytes(b), (x, y) -> (byte)(x & y));
    }

    /** Bitwise OR of two bytestrings with padding semantics. */
    public static PlutusData.BytesData orByteString(boolean padding, PlutusData.BytesData a, PlutusData.BytesData b) {
        return bitwiseBinaryOp(padding, toBytes(a), toBytes(b), (x, y) -> (byte)(x | y));
    }

    /** Bitwise XOR of two bytestrings with padding semantics. */
    public static PlutusData.BytesData xorByteString(boolean padding, PlutusData.BytesData a, PlutusData.BytesData b) {
        return bitwiseBinaryOp(padding, toBytes(a), toBytes(b), (x, y) -> (byte)(x ^ y));
    }

    /** Bitwise complement of a bytestring. */
    public static PlutusData.BytesData complementByteString(PlutusData.BytesData bs) {
        var bytes = toBytes(bs);
        var result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) result[i] = (byte) ~bytes[i];
        return new PlutusData.BytesData(result);
    }

    /** Read a bit at a given index. */
    public static boolean readBit(PlutusData.BytesData bs, long index) {
        var bytes = toBytes(bs);
        int byteIdx = (int)(index / 8);
        int bitIdx = (int)(index % 8);
        return ((bytes[bytes.length - 1 - byteIdx] >> bitIdx) & 1) == 1;
    }

    /** Write bits at given indices. */
    public static PlutusData.BytesData writeBits(PlutusData.BytesData bs, PlutusData.ListData indices, boolean value) {
        var bytes = toBytes(bs).clone();
        var idxList = indices.items();
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

    /** Shift a bytestring by n bits. Positive = left shift, negative = right shift. Zero bits fill. */
    public static PlutusData.BytesData shiftByteString(PlutusData.BytesData bs, long n) {
        var bytes = toBytes(bs);
        if (bytes.length == 0) return bs;
        int totalBits = bytes.length * 8;
        var result = new byte[bytes.length];
        if (Math.abs(n) >= totalBits) {
            return new PlutusData.BytesData(result); // all zeros
        }
        if (n > 0) {
            // Left shift by n bits
            int byteShift = (int)(n / 8);
            int bitShift = (int)(n % 8);
            for (int i = 0; i < bytes.length - byteShift; i++) {
                int val = (bytes[i + byteShift] & 0xFF) << bitShift;
                if (bitShift > 0 && i + byteShift + 1 < bytes.length) {
                    val |= (bytes[i + byteShift + 1] & 0xFF) >>> (8 - bitShift);
                }
                result[i] = (byte) val;
            }
        } else {
            // Right shift by |n| bits
            long absN = -n;
            int byteShift = (int)(absN / 8);
            int bitShift = (int)(absN % 8);
            for (int i = bytes.length - 1; i >= byteShift; i--) {
                int val = (bytes[i - byteShift] & 0xFF) >>> bitShift;
                if (bitShift > 0 && i - byteShift - 1 >= 0) {
                    val |= (bytes[i - byteShift - 1] & 0xFF) << (8 - bitShift);
                }
                result[i] = (byte) val;
            }
        }
        return new PlutusData.BytesData(result);
    }

    /** Rotate a bytestring by n bits. Positive = left rotate, negative = right rotate. */
    public static PlutusData.BytesData rotateByteString(PlutusData.BytesData bs, long n) {
        var bytes = toBytes(bs);
        if (bytes.length == 0) return bs;
        int totalBits = bytes.length * 8;
        // Normalize rotation amount to [0, totalBits)
        int rot = (int)(n % totalBits);
        if (rot < 0) rot += totalBits;
        if (rot == 0) return new PlutusData.BytesData(bytes.clone());
        // Rotate left by rot: result = (bs << rot) | (bs >>> (totalBits - rot))
        var left = shiftByteString(bs, rot);
        var right = shiftByteString(bs, -(totalBits - rot));
        var leftBytes = left.value();
        var rightBytes = right.value();
        var result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = (byte)(leftBytes[i] | rightBytes[i]);
        }
        return new PlutusData.BytesData(result);
    }

    /** Count the number of set bits (1s) in a bytestring. */
    public static long countSetBits(PlutusData.BytesData bs) {
        long count = 0;
        for (byte b : toBytes(bs)) count += Integer.bitCount(b & 0xFF);
        return count;
    }

    /** Find the index of the first set bit, or -1 if none. */
    public static long findFirstSetBit(PlutusData.BytesData bs) {
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
    // Bitwise operations — byte[] overloads
    // =========================================================================

    /** @see #andByteString(boolean, PlutusData.BytesData, PlutusData.BytesData) */
    public static byte[] andByteString(boolean padding, byte[] a, byte[] b) {
        return andByteString(padding, new PlutusData.BytesData(a), new PlutusData.BytesData(b)).value();
    }

    /** @see #orByteString(boolean, PlutusData.BytesData, PlutusData.BytesData) */
    public static byte[] orByteString(boolean padding, byte[] a, byte[] b) {
        return orByteString(padding, new PlutusData.BytesData(a), new PlutusData.BytesData(b)).value();
    }

    /** @see #xorByteString(boolean, PlutusData.BytesData, PlutusData.BytesData) */
    public static byte[] xorByteString(boolean padding, byte[] a, byte[] b) {
        return xorByteString(padding, new PlutusData.BytesData(a), new PlutusData.BytesData(b)).value();
    }

    /** @see #complementByteString(PlutusData.BytesData) */
    public static byte[] complementByteString(byte[] bs) {
        return complementByteString(new PlutusData.BytesData(bs)).value();
    }

    /** @see #readBit(PlutusData.BytesData, long) */
    public static boolean readBit(byte[] bs, long index) {
        return readBit(new PlutusData.BytesData(bs), index);
    }

    /** @see #writeBits(PlutusData.BytesData, PlutusData.ListData, boolean) */
    public static byte[] writeBits(byte[] bs, PlutusData.ListData indices, boolean value) {
        return writeBits(new PlutusData.BytesData(bs), indices, value).value();
    }

    /** @see #shiftByteString(PlutusData.BytesData, long) */
    public static byte[] shiftByteString(byte[] bs, long n) {
        return shiftByteString(new PlutusData.BytesData(bs), n).value();
    }

    /** @see #rotateByteString(PlutusData.BytesData, long) */
    public static byte[] rotateByteString(byte[] bs, long n) {
        return rotateByteString(new PlutusData.BytesData(bs), n).value();
    }

    /** @see #countSetBits(PlutusData.BytesData) */
    public static long countSetBits(byte[] bs) {
        return countSetBits(new PlutusData.BytesData(bs));
    }

    /** @see #findFirstSetBit(PlutusData.BytesData) */
    public static long findFirstSetBit(byte[] bs) {
        return findFirstSetBit(new PlutusData.BytesData(bs));
    }

    // =========================================================================
    // Math operations
    // =========================================================================

    /** Modular exponentiation: (base^exp) mod modulus. */
    public static long expModInteger(long base, long exp, long mod) {
        return BigInteger.valueOf(base).modPow(BigInteger.valueOf(exp), BigInteger.valueOf(mod)).longValue();
    }

    /** Modular exponentiation with BigInteger: (base^exp) mod modulus. */
    public static BigInteger expModInteger(BigInteger base, BigInteger exp, BigInteger mod) {
        return base.modPow(exp, mod);
    }

    // =========================================================================
    // Object-accepting overloads for IDE + off-chain compatibility
    // =========================================================================
    // On-chain types (ScriptInfo, TxInfo, etc.) don't extend PlutusData in
    // IDE stubs. The compiler matches by method name via StdlibRegistry and
    // ignores parameter types, so these overloads simply satisfy the IDE.
    // Off-chain, ledger records implement PlutusDataConvertible for conversion.

    private static PlutusData asPlutusData(Object obj) {
        if (obj instanceof PlutusData pd) return pd;
        if (obj instanceof PlutusDataConvertible pdc) return pdc.toPlutusData();
        throw new ClassCastException("Cannot convert " + obj.getClass().getName() + " to PlutusData");
    }

    /** @see #constrFields(PlutusData) */
    public static PlutusData.ListData constrFields(Object data) {
        return constrFields(asPlutusData(data));
    }

    /** @see #constrTag(PlutusData) */
    public static long constrTag(Object data) {
        return constrTag(asPlutusData(data));
    }

    /** @see #headList(PlutusData) */
    public static PlutusData headList(Object list) {
        return headList(asPlutusData(list));
    }

    /** @see #tailList(PlutusData) */
    public static PlutusData.ListData tailList(Object list) {
        return tailList(asPlutusData(list));
    }

    /** @see #nullList(PlutusData) */
    public static boolean nullList(Object list) {
        return nullList(asPlutusData(list));
    }

    /** @see #unBData(PlutusData) */
    public static byte[] unBData(Object data) {
        return unBData(asPlutusData(data));
    }

    /** @see #unIData(PlutusData) */
    public static BigInteger unIData(Object data) {
        return unIData(asPlutusData(data));
    }

    /** @see #unConstrData(PlutusData) */
    public static PlutusData.ConstrData unConstrData(Object data) {
        return unConstrData(asPlutusData(data));
    }

    /** @see #unListData(PlutusData) */
    public static PlutusData.ListData unListData(Object data) {
        return unListData(asPlutusData(data));
    }

    /** @see #unMapData(PlutusData) */
    public static PlutusData.MapData unMapData(Object data) {
        return unMapData(asPlutusData(data));
    }

    /** @see #equalsData(PlutusData, PlutusData) */
    public static boolean equalsData(Object a, Object b) {
        return equalsData(asPlutusData(a), asPlutusData(b));
    }

    /** @see #bData(PlutusData.BytesData) */
    public static PlutusData.BytesData bData(Object data) {
        if (data instanceof byte[] b) return bData(b);
        return bData(asPlutusData(data));
    }

    /** Wrap a PlutusData as BytesData (identity if already BytesData, otherwise convert). */
    public static PlutusData.BytesData bData(PlutusData data) {
        if (data instanceof PlutusData.BytesData bd) return bd;
        throw new IllegalArgumentException("bData: expected BytesData, got " + data);
    }

    /** Extract byte[] from a value. On-chain: identity (value is already ByteString). */
    public static byte[] toByteString(Object data) {
        if (data instanceof byte[] b) return b;
        if (data instanceof PlutusData.BytesData bd) return bd.value();
        if (data instanceof PlutusDataConvertible pdc) {
            var pd = pdc.toPlutusData();
            if (pd instanceof PlutusData.BytesData bd) return bd.value();
        }
        throw new ClassCastException("Cannot extract byte[] from " + data.getClass().getName());
    }

    // =========================================================================
    // Type-friendly aliases (eliminate double-cast verbosity)
    // =========================================================================

    /** Extract byte string from Data. Alias for {@link #unBData(PlutusData)}. */
    public static byte[] asBytes(PlutusData data) { return unBData(data); }

    /** @see #asBytes(PlutusData) */
    public static byte[] asBytes(Object data) { return unBData(asPlutusData(data)); }

    /** Extract integer from Data. Alias for {@link #unIData(PlutusData)}. */
    public static BigInteger asInteger(PlutusData data) { return unIData(data); }

    /** @see #asInteger(PlutusData) */
    public static BigInteger asInteger(Object data) { return unIData(asPlutusData(data)); }

    /** Extract list from Data. Typed alias for {@link #unListData(PlutusData)}. */
    public static JulcList<PlutusData> asList(PlutusData data) {
        if (data instanceof PlutusData.ListData ld) return new JulcArrayList<>(ld.items());
        throw new IllegalArgumentException("asList: expected ListData, got " + data);
    }

    /** @see #asList(PlutusData) */
    public static JulcList<PlutusData> asList(Object data) { return asList(asPlutusData(data)); }

    /** Extract map from Data. Typed alias for {@link #unMapData(PlutusData)}. */
    public static JulcMap<PlutusData, PlutusData> asMap(PlutusData data) {
        if (data instanceof PlutusData.MapData md) {
            JulcMap<PlutusData, PlutusData> result = JulcAssocMap.empty();
            for (var pair : md.entries()) {
                result = result.insert(pair.key(), pair.value());
            }
            return result;
        }
        throw new IllegalArgumentException("asMap: expected MapData, got " + data);
    }

    /** @see #asMap(PlutusData) */
    public static JulcMap<PlutusData, PlutusData> asMap(Object data) { return asMap(asPlutusData(data)); }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static List<PlutusData> toList(PlutusData data) {
        if (data instanceof PlutusData.ListData ld) {
            return ld.items();
        }
        if (data instanceof PlutusData.MapData md) {
            // MapData pairs → ConstrData(0, [key, value]) to match on-chain pair representation
            return md.entries().stream()
                    .map(p -> (PlutusData) new PlutusData.ConstrData(0, List.of(p.key(), p.value())))
                    .toList();
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

    private static PlutusData.BytesData bitwiseBinaryOp(boolean padding, byte[] a, byte[] b, ByteOp op) {
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
