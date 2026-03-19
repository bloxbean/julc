package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Constant.ValueConst;
import com.bloxbean.cardano.julc.core.Constant.ValueConst.TokenEntry;
import com.bloxbean.cardano.julc.core.Constant.ValueConst.ValueEntry;
import com.bloxbean.cardano.julc.core.Constant.ByteArrayKey;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.math.BigInteger;
import java.util.*;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * MaryEraValue (CIP-153) builtin implementations.
 * <p>
 * Value entries are always sorted by policyId then tokenName (lexicographic unsigned byte order).
 * Zero-quantity entries are removed. Quantities must fit in Int128 (-2^127 to 2^127-1).
 */
public final class ValueBuiltins {

    private ValueBuiltins() {}

    private static final BigInteger MAX_QUANTITY = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
    private static final BigInteger MIN_QUANTITY = BigInteger.ONE.shiftLeft(127).negate();
    private static final int MAX_KEY_LENGTH = 32;

    /**
     * InsertCoin: arity=4 → (policyId, tokenName, quantity, value)
     * Inserts/replaces the given (policyId, tokenName) entry with the given quantity.
     */
    public static CekValue insertCoin(List<CekValue> args) {
        byte[] policyId = asByteString(args.get(0), "InsertCoin");
        byte[] tokenName = asByteString(args.get(1), "InsertCoin");
        BigInteger quantity = asInteger(args.get(2), "InsertCoin");
        var value = asValueConst(args.get(3), "InsertCoin");

        // Key length validation — only enforced for non-zero quantity
        if (quantity.signum() != 0) {
            if (policyId.length > MAX_KEY_LENGTH) {
                throw new BuiltinException("InsertCoin: policyId too long: " +
                        policyId.length + " bytes (max " + MAX_KEY_LENGTH + ")");
            }
            if (tokenName.length > MAX_KEY_LENGTH) {
                throw new BuiltinException("InsertCoin: tokenName too long: " +
                        tokenName.length + " bytes (max " + MAX_KEY_LENGTH + ")");
            }
            checkQuantityRange(quantity, "InsertCoin");
        }

        // Build result using TreeMap for sorted output
        var policyMap = toMutableMap(value);
        var targetPolicy = new ByteArrayKey(policyId);
        var targetToken = new ByteArrayKey(tokenName);

        if (quantity.signum() == 0) {
            // Remove the entry
            var tokenMap = policyMap.get(targetPolicy);
            if (tokenMap != null) {
                tokenMap.remove(targetToken);
                if (tokenMap.isEmpty()) {
                    policyMap.remove(targetPolicy);
                }
            }
        } else {
            // Insert/replace
            policyMap.computeIfAbsent(targetPolicy, k -> new TreeMap<>(ByteArrayKey.COMPARATOR))
                    .put(targetToken, quantity);
        }

        return mkValue(fromMutableMap(policyMap));
    }

    /**
     * LookupCoin: arity=3 → (policyId, tokenName, value)
     * Returns the quantity for the given key, or 0 if absent.
     */
    public static CekValue lookupCoin(List<CekValue> args) {
        byte[] policyId = asByteString(args.get(0), "LookupCoin");
        byte[] tokenName = asByteString(args.get(1), "LookupCoin");
        var value = asValueConst(args.get(2), "LookupCoin");

        for (var entry : value.entries()) {
            if (Arrays.equals(entry.policyId(), policyId)) {
                for (var token : entry.tokens()) {
                    if (Arrays.equals(token.tokenName(), tokenName)) {
                        return mkInteger(token.quantity());
                    }
                }
            }
        }
        return mkInteger(0);
    }

    /**
     * UnionValue: arity=2 → (value, value)
     * Merges two values by adding quantities. Overflow/underflow is checked.
     */
    public static CekValue unionValue(List<CekValue> args) {
        var a = asValueConst(args.get(0), "UnionValue");
        var b = asValueConst(args.get(1), "UnionValue");

        var policyMap = toMutableMap(a);

        // Merge b into a
        for (var entry : b.entries()) {
            var pKey = new ByteArrayKey(entry.policyId());
            var tokenMap = policyMap.computeIfAbsent(pKey, k -> new TreeMap<>(ByteArrayKey.COMPARATOR));
            for (var token : entry.tokens()) {
                var tKey = new ByteArrayKey(token.tokenName());
                tokenMap.merge(tKey, token.quantity(), BigInteger::add);
            }
        }

        // Check range and remove zeros
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

        return mkValue(fromMutableMap(result));
    }

    /**
     * ValueContains: arity=2 → (value, value)
     * Returns true if the first value contains at least the second.
     * Fails if any quantity in either value is negative.
     */
    public static CekValue valueContains(List<CekValue> args) {
        var a = asValueConst(args.get(0), "ValueContains");
        var b = asValueConst(args.get(1), "ValueContains");

        // Check all quantities are non-negative
        checkAllNonNegative(a, "ValueContains");
        checkAllNonNegative(b, "ValueContains");

        // Build lookup for a
        var aMap = toImmutableMap(a);

        // Check that a contains at least b
        for (var bEntry : b.entries()) {
            var pKey = new ByteArrayKey(bEntry.policyId());
            var aTokens = aMap.get(pKey);
            for (var bToken : bEntry.tokens()) {
                var tKey = new ByteArrayKey(bToken.tokenName());
                BigInteger aQty = (aTokens != null) ? aTokens.getOrDefault(tKey, BigInteger.ZERO) : BigInteger.ZERO;
                if (aQty.compareTo(bToken.quantity()) < 0) {
                    return mkBool(false);
                }
            }
        }

        return mkBool(true);
    }

    /**
     * ValueData: arity=1 → (value)
     * Converts a Value to PlutusData: Map[B policyId, Map[B tokenName, I quantity]]
     */
    public static CekValue valueData(List<CekValue> args) {
        var value = asValueConst(args.get(0), "ValueData");

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

        return mkData(PlutusData.map(outerEntries.toArray(PlutusData.Pair[]::new)));
    }

    /**
     * UnValueData: arity=1 → (data)
     * Converts PlutusData (Map[B,Map[B,I]]) to a Value.
     * Strict validation: no duplicates, must be sorted, no zero quantities,
     * no empty token maps, key length limits, quantity range.
     */
    public static CekValue unValueData(List<CekValue> args) {
        var data = asData(args.get(0), "UnValueData");

        if (!(data instanceof PlutusData.MapData outerMap)) {
            throw new BuiltinException("UnValueData: expected Map data, got " + data.getClass().getSimpleName());
        }

        var entries = new ArrayList<ValueEntry>();
        byte[] prevPolicyId = null;

        for (var outerEntry : outerMap.entries()) {
            // Key must be BytesData
            if (!(outerEntry.key() instanceof PlutusData.BytesData policyIdData)) {
                throw new BuiltinException("UnValueData: policyId must be BytesData");
            }
            byte[] policyId = policyIdData.value();

            // Key length check
            if (policyId.length > MAX_KEY_LENGTH) {
                throw new BuiltinException("UnValueData: policyId too long: " + policyId.length);
            }

            // Strict ordering check
            if (prevPolicyId != null) {
                int cmp = Arrays.compareUnsigned(prevPolicyId, policyId);
                if (cmp >= 0) {
                    throw new BuiltinException("UnValueData: currencies not strictly ordered or duplicate");
                }
            }
            prevPolicyId = policyId;

            // Value must be a Map
            if (!(outerEntry.value() instanceof PlutusData.MapData tokenMap)) {
                throw new BuiltinException("UnValueData: token map must be MapData");
            }

            if (tokenMap.entries().isEmpty()) {
                throw new BuiltinException("UnValueData: empty token map");
            }

            var tokens = new ArrayList<TokenEntry>();
            byte[] prevTokenName = null;

            for (var tokenEntry : tokenMap.entries()) {
                // Token name must be BytesData
                if (!(tokenEntry.key() instanceof PlutusData.BytesData tokenNameData)) {
                    throw new BuiltinException("UnValueData: tokenName must be BytesData");
                }
                byte[] tokenName = tokenNameData.value();

                // Token name length check
                if (tokenName.length > MAX_KEY_LENGTH) {
                    throw new BuiltinException("UnValueData: tokenName too long: " + tokenName.length);
                }

                // Strict ordering check
                if (prevTokenName != null) {
                    int cmp = Arrays.compareUnsigned(prevTokenName, tokenName);
                    if (cmp >= 0) {
                        throw new BuiltinException("UnValueData: tokens not strictly ordered or duplicate");
                    }
                }
                prevTokenName = tokenName;

                // Quantity must be IntData
                if (!(tokenEntry.value() instanceof PlutusData.IntData quantityData)) {
                    throw new BuiltinException("UnValueData: quantity must be IntData");
                }
                BigInteger quantity = quantityData.value();

                // Zero check
                if (quantity.signum() == 0) {
                    throw new BuiltinException("UnValueData: zero quantity");
                }

                // Range check
                checkQuantityRange(quantity, "UnValueData");

                tokens.add(new TokenEntry(tokenName, quantity));
            }

            entries.add(new ValueEntry(policyId, tokens));
        }

        return mkValue(entries);
    }

    /**
     * ScaleValue: arity=2 → (integer, value)
     * Multiplies all quantities by the scalar. Overflow/underflow checked.
     */
    public static CekValue scaleValue(List<CekValue> args) {
        BigInteger scalar = asInteger(args.get(0), "ScaleValue");
        var value = asValueConst(args.get(1), "ScaleValue");

        if (scalar.signum() == 0) {
            return mkValue(List.of());
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

        return mkValue(result);
    }

    // ====== Internal helpers ======

    private static void checkQuantityRange(BigInteger qty, String context) {
        if (qty.compareTo(MAX_QUANTITY) > 0 || qty.compareTo(MIN_QUANTITY) < 0) {
            throw new BuiltinException(context + ": quantity out of Int128 range: " + qty);
        }
    }

    private static void checkAllNonNegative(ValueConst value, String context) {
        for (var entry : value.entries()) {
            for (var token : entry.tokens()) {
                if (token.quantity().signum() < 0) {
                    throw new BuiltinException(context + ": negative quantity not allowed");
                }
            }
        }
    }

    /** Convert a ValueConst to a mutable TreeMap for manipulation. */
    private static TreeMap<ByteArrayKey, TreeMap<ByteArrayKey, BigInteger>> toMutableMap(ValueConst value) {
        var map = new TreeMap<ByteArrayKey, TreeMap<ByteArrayKey, BigInteger>>(ByteArrayKey.COMPARATOR);
        for (var entry : value.entries()) {
            var pKey = new ByteArrayKey(entry.policyId());
            var tokenMap = new TreeMap<ByteArrayKey, BigInteger>(ByteArrayKey.COMPARATOR);
            for (var token : entry.tokens()) {
                tokenMap.put(new ByteArrayKey(token.tokenName()), token.quantity());
            }
            map.put(pKey, tokenMap);
        }
        return map;
    }

    /** Convert a ValueConst to an immutable lookup map. */
    private static Map<ByteArrayKey, Map<ByteArrayKey, BigInteger>> toImmutableMap(ValueConst value) {
        var map = new HashMap<ByteArrayKey, Map<ByteArrayKey, BigInteger>>();
        for (var entry : value.entries()) {
            var pKey = new ByteArrayKey(entry.policyId());
            var tokenMap = new HashMap<ByteArrayKey, BigInteger>();
            for (var token : entry.tokens()) {
                tokenMap.put(new ByteArrayKey(token.tokenName()), token.quantity());
            }
            map.put(pKey, tokenMap);
        }
        return map;
    }

    /** Convert a mutable TreeMap back to ValueEntry list. */
    private static List<ValueEntry> fromMutableMap(
            TreeMap<ByteArrayKey, TreeMap<ByteArrayKey, BigInteger>> map) {
        var entries = new ArrayList<ValueEntry>();
        for (var pEntry : map.entrySet()) {
            var tokens = new ArrayList<TokenEntry>();
            for (var tEntry : pEntry.getValue().entrySet()) {
                tokens.add(new TokenEntry(tEntry.getKey().bytes(), tEntry.getValue()));
            }
            entries.add(new ValueEntry(pEntry.getKey().bytes(), tokens));
        }
        return entries;
    }
}
