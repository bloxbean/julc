package org.julclang.core;

import org.julclang.core.Constant.ByteArrayKey;
import org.julclang.core.Constant.ValueConst;
import org.julclang.core.Constant.ValueConst.TokenEntry;
import org.julclang.core.Constant.ValueConst.ValueEntry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pinned Plutus semantics of the PV11 native Value builtins (CIP-153) over
 * {@link ValueConst}: {@code InsertCoin}, {@code LookupCoin}, {@code UnionValue},
 * {@code ValueContains}, {@code ScaleValue}, {@code ValueData} and {@code UnValueData}.
 *
 * <p>Entries are kept sorted by policy id, then token name (unsigned lexicographic byte
 * order); zero quantities are removed; quantities must fit in a signed 128-bit integer; keys
 * are at most 32 bytes. A failing call throws {@link EvaluationFailure} carrying the exact
 * builtin failure text. Keeping this in {@code julc-core} gives the VM runtime and the
 * compiler's literal fold (ADR-045, O14) one source of truth, as {@link ExpModIntegerSemantics}
 * does for {@code ExpModInteger}.</p>
 */
public final class NativeValueSemantics {

    public static final int MAX_KEY_LENGTH = 32;
    public static final BigInteger MAX_QUANTITY = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
    public static final BigInteger MIN_QUANTITY = BigInteger.ONE.shiftLeft(127).negate();

    public static final ValueConst EMPTY = new ValueConst(List.of());

    private NativeValueSemantics() {}

    /** The pinned failure of a native Value builtin, with the builtin's exact message. */
    public static final class EvaluationFailure extends RuntimeException {
        public EvaluationFailure(String message) {
            super(message);
        }
    }

    /**
     * {@code InsertCoin}: insert or replace the {@code (policyId, tokenName)} entry with
     * {@code quantity}; a zero quantity removes the entry and skips the key-length and range
     * checks.
     */
    public static ValueConst insertCoin(byte[] policyId, byte[] tokenName, BigInteger quantity, ValueConst value) {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(tokenName, "tokenName");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(value, "value");
        if (quantity.signum() != 0) {
            if (policyId.length > MAX_KEY_LENGTH) {
                throw new EvaluationFailure("InsertCoin: policyId too long: "
                        + policyId.length + " bytes (max " + MAX_KEY_LENGTH + ")");
            }
            if (tokenName.length > MAX_KEY_LENGTH) {
                throw new EvaluationFailure("InsertCoin: tokenName too long: "
                        + tokenName.length + " bytes (max " + MAX_KEY_LENGTH + ")");
            }
            checkQuantityRange(quantity, "InsertCoin");
        }
        var policyMap = toMutableMap(value);
        var targetPolicy = new ByteArrayKey(policyId);
        var targetToken = new ByteArrayKey(tokenName);
        if (quantity.signum() == 0) {
            var tokenMap = policyMap.get(targetPolicy);
            if (tokenMap != null) {
                tokenMap.remove(targetToken);
                if (tokenMap.isEmpty()) {
                    policyMap.remove(targetPolicy);
                }
            }
        } else {
            policyMap.computeIfAbsent(targetPolicy, k -> new TreeMap<>(ByteArrayKey.COMPARATOR))
                    .put(targetToken, quantity);
        }
        return fromMutableMap(policyMap);
    }

    /** {@code LookupCoin}: the quantity at {@code (policyId, tokenName)}, or zero when absent. Total. */
    public static BigInteger lookupCoin(byte[] policyId, byte[] tokenName, ValueConst value) {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(tokenName, "tokenName");
        Objects.requireNonNull(value, "value");
        for (var entry : value.entries()) {
            if (Arrays.equals(entry.policyId(), policyId)) {
                for (var token : entry.tokens()) {
                    if (Arrays.equals(token.tokenName(), tokenName)) {
                        return token.quantity();
                    }
                }
            }
        }
        return BigInteger.ZERO;
    }

    /** {@code UnionValue}: add quantities entry-wise; zero sums vanish; a sum outside the range fails. */
    public static ValueConst unionValue(ValueConst a, ValueConst b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        var policyMap = toMutableMap(a);
        for (var entry : b.entries()) {
            var pKey = new ByteArrayKey(entry.policyId());
            var tokenMap = policyMap.computeIfAbsent(pKey, k -> new TreeMap<>(ByteArrayKey.COMPARATOR));
            for (var token : entry.tokens()) {
                tokenMap.merge(new ByteArrayKey(token.tokenName()), token.quantity(), BigInteger::add);
            }
        }
        var result = new TreeMap<ByteArrayKey, TreeMap<ByteArrayKey, BigInteger>>(ByteArrayKey.COMPARATOR);
        for (var pEntry : policyMap.entrySet()) {
            var tokens = new TreeMap<ByteArrayKey, BigInteger>(ByteArrayKey.COMPARATOR);
            for (var tEntry : pEntry.getValue().entrySet()) {
                var qty = tEntry.getValue();
                if (qty.signum() != 0) {
                    checkQuantityRange(qty, "UnionValue");
                    tokens.put(tEntry.getKey(), qty);
                }
            }
            if (!tokens.isEmpty()) {
                result.put(pEntry.getKey(), tokens);
            }
        }
        return fromMutableMap(result);
    }

    /**
     * {@code ValueContains}: whether {@code a} holds at least {@code b} entry-wise. Fails when
     * either value holds a negative quantity ({@code a} is checked first).
     */
    public static boolean valueContains(ValueConst a, ValueConst b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        checkAllNonNegative(a, "ValueContains");
        checkAllNonNegative(b, "ValueContains");
        var aMap = toImmutableMap(a);
        for (var bEntry : b.entries()) {
            var aTokens = aMap.get(new ByteArrayKey(bEntry.policyId()));
            for (var bToken : bEntry.tokens()) {
                var tKey = new ByteArrayKey(bToken.tokenName());
                BigInteger aQty = aTokens != null ? aTokens.getOrDefault(tKey, BigInteger.ZERO) : BigInteger.ZERO;
                if (aQty.compareTo(bToken.quantity()) < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@code ScaleValue}: multiply every quantity by {@code scalar}. A zero scalar yields the
     * empty value before any range check; a product outside the range fails.
     */
    public static ValueConst scaleValue(BigInteger scalar, ValueConst value) {
        Objects.requireNonNull(scalar, "scalar");
        Objects.requireNonNull(value, "value");
        if (scalar.signum() == 0) {
            return EMPTY;
        }
        var result = new ArrayList<ValueEntry>();
        for (var entry : value.entries()) {
            var tokens = new ArrayList<TokenEntry>();
            for (var token : entry.tokens()) {
                BigInteger newQty = token.quantity().multiply(scalar);
                checkQuantityRange(newQty, "ScaleValue");
                if (newQty.signum() != 0) {
                    tokens.add(new TokenEntry(token.tokenName(), newQty));
                }
            }
            if (!tokens.isEmpty()) {
                result.add(new ValueEntry(entry.policyId(), tokens));
            }
        }
        return new ValueConst(result);
    }

    /** {@code ValueData}: the canonical {@code Map[B policyId, Map[B tokenName, I quantity]]} encoding. Total. */
    public static PlutusData valueData(ValueConst value) {
        Objects.requireNonNull(value, "value");
        var outerEntries = new ArrayList<PlutusData.Pair>();
        for (var entry : value.entries()) {
            var innerEntries = new ArrayList<PlutusData.Pair>();
            for (var token : entry.tokens()) {
                innerEntries.add(new PlutusData.Pair(
                        PlutusData.bytes(token.tokenName()),
                        PlutusData.integer(token.quantity())));
            }
            outerEntries.add(new PlutusData.Pair(
                    PlutusData.bytes(entry.policyId()),
                    PlutusData.map(innerEntries.toArray(PlutusData.Pair[]::new))));
        }
        return PlutusData.map(outerEntries.toArray(PlutusData.Pair[]::new));
    }

    /**
     * {@code UnValueData}: strict decoding of {@code Map[B, Map[B, I]]}. Policies and tokens
     * must be strictly ordered (no duplicates), token maps non-empty, keys at most 32 bytes,
     * quantities non-zero and in range. Nothing is normalised: any deviation fails.
     */
    public static ValueConst unValueData(PlutusData data) {
        Objects.requireNonNull(data, "data");
        if (!(data instanceof PlutusData.MapData outerMap)) {
            throw new EvaluationFailure("UnValueData: expected Map data, got " + data.getClass().getSimpleName());
        }
        var entries = new ArrayList<ValueEntry>();
        byte[] prevPolicyId = null;
        for (var outerEntry : outerMap.entries()) {
            if (!(outerEntry.key() instanceof PlutusData.BytesData policyIdData)) {
                throw new EvaluationFailure("UnValueData: policyId must be BytesData");
            }
            byte[] policyId = policyIdData.value();
            if (policyId.length > MAX_KEY_LENGTH) {
                throw new EvaluationFailure("UnValueData: policyId too long: " + policyId.length);
            }
            if (prevPolicyId != null && Arrays.compareUnsigned(prevPolicyId, policyId) >= 0) {
                throw new EvaluationFailure("UnValueData: currencies not strictly ordered or duplicate");
            }
            prevPolicyId = policyId;
            if (!(outerEntry.value() instanceof PlutusData.MapData tokenMap)) {
                throw new EvaluationFailure("UnValueData: token map must be MapData");
            }
            if (tokenMap.entries().isEmpty()) {
                throw new EvaluationFailure("UnValueData: empty token map");
            }
            var tokens = new ArrayList<TokenEntry>();
            byte[] prevTokenName = null;
            for (var tokenEntry : tokenMap.entries()) {
                if (!(tokenEntry.key() instanceof PlutusData.BytesData tokenNameData)) {
                    throw new EvaluationFailure("UnValueData: tokenName must be BytesData");
                }
                byte[] tokenName = tokenNameData.value();
                if (tokenName.length > MAX_KEY_LENGTH) {
                    throw new EvaluationFailure("UnValueData: tokenName too long: " + tokenName.length);
                }
                if (prevTokenName != null && Arrays.compareUnsigned(prevTokenName, tokenName) >= 0) {
                    throw new EvaluationFailure("UnValueData: tokens not strictly ordered or duplicate");
                }
                prevTokenName = tokenName;
                if (!(tokenEntry.value() instanceof PlutusData.IntData quantityData)) {
                    throw new EvaluationFailure("UnValueData: quantity must be IntData");
                }
                BigInteger quantity = quantityData.value();
                if (quantity.signum() == 0) {
                    throw new EvaluationFailure("UnValueData: zero quantity");
                }
                checkQuantityRange(quantity, "UnValueData");
                tokens.add(new TokenEntry(tokenName, quantity));
            }
            entries.add(new ValueEntry(policyId, tokens));
        }
        return new ValueConst(entries);
    }

    private static void checkQuantityRange(BigInteger qty, String context) {
        if (qty.compareTo(MAX_QUANTITY) > 0 || qty.compareTo(MIN_QUANTITY) < 0) {
            throw new EvaluationFailure(context + ": quantity out of Int128 range: " + qty);
        }
    }

    private static void checkAllNonNegative(ValueConst value, String context) {
        for (var entry : value.entries()) {
            for (var token : entry.tokens()) {
                if (token.quantity().signum() < 0) {
                    throw new EvaluationFailure(context + ": negative quantity not allowed");
                }
            }
        }
    }

    private static TreeMap<ByteArrayKey, TreeMap<ByteArrayKey, BigInteger>> toMutableMap(ValueConst value) {
        var map = new TreeMap<ByteArrayKey, TreeMap<ByteArrayKey, BigInteger>>(ByteArrayKey.COMPARATOR);
        for (var entry : value.entries()) {
            var tokenMap = new TreeMap<ByteArrayKey, BigInteger>(ByteArrayKey.COMPARATOR);
            for (var token : entry.tokens()) {
                tokenMap.put(new ByteArrayKey(token.tokenName()), token.quantity());
            }
            map.put(new ByteArrayKey(entry.policyId()), tokenMap);
        }
        return map;
    }

    private static Map<ByteArrayKey, Map<ByteArrayKey, BigInteger>> toImmutableMap(ValueConst value) {
        var map = new HashMap<ByteArrayKey, Map<ByteArrayKey, BigInteger>>();
        for (var entry : value.entries()) {
            var tokenMap = new HashMap<ByteArrayKey, BigInteger>();
            for (var token : entry.tokens()) {
                tokenMap.put(new ByteArrayKey(token.tokenName()), token.quantity());
            }
            map.put(new ByteArrayKey(entry.policyId()), tokenMap);
        }
        return map;
    }

    private static ValueConst fromMutableMap(TreeMap<ByteArrayKey, TreeMap<ByteArrayKey, BigInteger>> map) {
        var entries = new ArrayList<ValueEntry>();
        for (var pEntry : map.entrySet()) {
            var tokens = new ArrayList<TokenEntry>();
            for (var tEntry : pEntry.getValue().entrySet()) {
                tokens.add(new TokenEntry(tEntry.getKey().bytes(), tEntry.getValue()));
            }
            entries.add(new ValueEntry(pEntry.getKey().bytes(), tokens));
        }
        return new ValueConst(entries);
    }
}
