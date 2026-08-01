package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import com.bloxbean.cardano.julc.vm.BuiltinSemanticsVariant;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Maps each builtin function to its CPU and memory cost functions.
 * <p>
 * Cost functions take argument sizes as input and produce a cost value.
 * Argument sizes are computed from the evaluated {@link CekValue} arguments
 * following the Plutus specification's {@code ExMemoryUsage} instances.
 * <p>
 * All sizes are in 64-bit machine words (8 bytes), matching the Plutus/Scalus
 * memory usage model. Data values additionally include a 4-word node overhead
 * for each constructor.
 */
public final class BuiltinCostModel {

    /** CPU and memory cost function pair for a builtin. */
    public record CostPair(CostFunction cpu, CostFunction mem) {}

    private final Map<DefaultFun, CostPair> costs;

    public BuiltinCostModel(Map<DefaultFun, CostPair> costs) {
        this.costs = new EnumMap<>(costs);
    }

    /** Get the cost pair for a builtin, or null if not registered. */
    public CostPair get(DefaultFun fun) {
        return costs.get(fun);
    }

    /**
     * Compute the "size" of a {@link CekValue} for cost model purposes.
     * <p>
     * Follows the Plutus {@code ExMemoryUsage} specification:
     * <ul>
     *   <li>Integer: number of 64-bit words needed</li>
     *   <li>ByteString: number of 8-byte words needed</li>
     *   <li>String: length in characters</li>
     *   <li>Bool, Unit: 1</li>
     *   <li>Data: recursive size with 4-word node overhead per constructor</li>
     *   <li>List: length of the list</li>
     *   <li>Pair: maxBound — Plutus poisons pair sizing since all pair
     *       builtins are constant-cost</li>
     *   <li>BLS elements: fixed sizes (in-memory bytes / 8)</li>
     * </ul>
     */
    public static long sizeOf(CekValue value) {
        if (value instanceof CekValue.VCon vcon) {
            return sizeOfConstant(vcon.constant());
        }
        // Non-constant values (closures, etc.) have size 1
        return 1;
    }

    /** Compute the size of a Constant value. */
    public static long sizeOfConstant(Constant c) {
        return switch (c) {
            case Constant.IntegerConst ic -> integerSize(ic.value());
            case Constant.ByteStringConst bs -> byteStringSize(bs.value());
            // Plutus counts Unicode code points (Text.length), not UTF-16 units
            case Constant.StringConst s -> s.value().codePointCount(0, s.value().length());
            case Constant.BoolConst _ -> 1;
            case Constant.UnitConst _ -> 1;
            case Constant.DataConst dc -> sizeOfData(dc.value());
            case Constant.ListConst lc -> lc.values().size();
            // Plutus sets pair memory usage to maxBound: all pair builtins are
            // expected to be constant-cost, and the poison value makes any
            // violation of that assumption blow up instead of undercharging
            case Constant.PairConst _ -> Long.MAX_VALUE;
            // In-memory sizes in 8-byte words: G1 = 144 bytes, G2 = 288 bytes, MlResult = 576 bytes
            case Constant.Bls12_381_G1Element _ -> 18;
            case Constant.Bls12_381_G2Element _ -> 36;
            case Constant.Bls12_381_MlResult _ -> 72;
            case Constant.ArrayConst ac -> ac.values().size();
            case Constant.ValueConst vc -> {
                long size = 0;
                for (var entry : vc.entries()) size += entry.tokens().size();
                yield size;
            }
        };
    }

    /**
     * Compute the size of a PlutusData value (recursive).
     * <p>
     * Every Data node has a 4-word overhead (including IntData and BytesData).
     * This matches the Plutus {@code memoryUsage @Data} implementation.
     */
    public static long sizeOfData(PlutusData data) {
        return switch (data) {
            case PlutusData.IntData id -> 4 + integerSize(id.value());
            case PlutusData.BytesData bd -> 4 + byteStringSize(bd.value());
            case PlutusData.ConstrData cd -> {
                long size = 4;
                for (var field : cd.fields()) {
                    size += sizeOfData(field);
                }
                yield size;
            }
            case PlutusData.ListData ld -> {
                long size = 4;
                for (var item : ld.items()) {
                    size += sizeOfData(item);
                }
                yield size;
            }
            case PlutusData.MapData md -> {
                long size = 4;
                for (var entry : md.entries()) {
                    size += sizeOfData(entry.key()) + sizeOfData(entry.value());
                }
                yield size;
            }
        };
    }

    /**
     * Memory usage of an integer in 64-bit machine words.
     * <p>
     * Matches Plutus: {@code (integerLog2(abs(n))) `div` 64 + 1}, or equivalently
     * {@code (abs(n).bitLength() - 1) / 64 + 1}. Zero has size 1.
     * <p>
     * The {@code abs} matters: Java's {@code bitLength()} of a negative power of
     * two is one less than that of its absolute value (e.g. -2^64 gives 64, not 65).
     */
    static long integerSize(BigInteger value) {
        if (value.signum() == 0) return 1;
        return (value.abs().bitLength() - 1) / 64 + 1;
    }

    /**
     * Memory usage of a byte string in 8-byte (64-bit) words.
     * <p>
     * Matches Plutus: {@code ((BS.length bs - 1) `quot` 8) + 1}.
     * This is ceiling division of byte length by 8, with empty strings giving 1.
     * <p>
     * Note: Java's integer division truncates toward zero (same as Haskell's {@code quot}),
     * so {@code (0 - 1) / 8 + 1 = 1} correctly handles empty byte strings.
     */
    static long byteStringSize(byte[] bytes) {
        return (bytes.length - 1) / 8 + 1;
    }

    /**
     * Compute argument sizes for a builtin call.
     * Returns an array of sizes, one per argument.
     */
    public static long[] argSizes(List<CekValue> args) {
        long[] sizes = new long[args.size()];
        for (int i = 0; i < args.size(); i++) {
            sizes[i] = sizeOf(args.get(i));
        }
        return sizes;
    }

    /**
     * Compute argument sizes for a builtin call, applying builtin-specific
     * sizing where Plutus deviates from the generic {@code ExMemoryUsage}
     * instances:
     * <ul>
     *   <li>{@code ReplicateByte} arg0 (requested length) and
     *       {@code IntegerToByteString} arg1 (requested width) are costed by
     *       the requested byte count converted to 8-byte words
     *       (Plutus {@code NumBytesCostedAsNumWords})</li>
     *   <li>{@code ShiftByteString}/{@code RotateByteString} arg1 (shift) and
     *       {@code DropList} arg0 (count) are costed by the literal absolute
     *       value (Plutus {@code IntegerCostedLiterally})</li>
     *   <li>{@code InsertCoin} arg3 and {@code LookupCoin} arg2 (the value)
     *       are costed by binary-search depth (Plutus {@code ValueMaxDepth})</li>
     *   <li>{@code UnValueData} arg0 is costed by the number of Data nodes
     *       (Plutus {@code DataNodeCount})</li>
     * </ul>
     * {@code WriteBits} arg1 is costed by list length (Plutus
     * {@code ListCostedByLength}), which coincides with the generic list
     * sizing above, so it needs no override here.
     */
    public static long[] argSizes(DefaultFun fun, List<CekValue> args) {
        long[] sizes = argSizes(args);
        // A wrong-typed arg is a type error — the builtin fails right after
        // charging — so each case keeps the generic size on a type mismatch.
        switch (fun) {
            case ReplicateByte -> {
                if (integerArg(args, 0) instanceof BigInteger n) sizes[0] = literalByteSize(n);
            }
            case IntegerToByteString -> {
                if (integerArg(args, 1) instanceof BigInteger n) sizes[1] = literalByteSize(n);
            }
            case ShiftByteString, RotateByteString -> {
                if (integerArg(args, 1) instanceof BigInteger n) sizes[1] = literalValue(n);
            }
            case DropList -> {
                if (integerArg(args, 0) instanceof BigInteger n) sizes[0] = literalValue(n);
            }
            case InsertCoin -> {
                if (valueArg(args, 3) instanceof Constant.ValueConst vc) sizes[3] = valueMaxDepth(vc);
            }
            case LookupCoin -> {
                if (valueArg(args, 2) instanceof Constant.ValueConst vc) sizes[2] = valueMaxDepth(vc);
            }
            case UnValueData -> {
                if (idx(args, 0) instanceof Constant.DataConst dc) sizes[0] = dataNodeCount(dc.value());
            }
            default -> { }
        }
        return sizes;
    }

    /**
     * Variant-aware costing boundary. Variant-specific wrappers are applied
     * here as their runtime behavior is implemented.
     */
    public static long[] argSizes(BuiltinSemanticsVariant variant, DefaultFun fun,
                                  List<CekValue> args) {
        return argSizes(fun, args);
    }

    private static Constant idx(List<CekValue> args, int i) {
        if (i < args.size() && args.get(i) instanceof CekValue.VCon vcon) {
            return vcon.constant();
        }
        return null;
    }

    private static BigInteger integerArg(List<CekValue> args, int i) {
        return idx(args, i) instanceof Constant.IntegerConst ic ? ic.value() : null;
    }

    private static Constant.ValueConst valueArg(List<CekValue> args, int i) {
        return idx(args, i) instanceof Constant.ValueConst vc ? vc : null;
    }

    /**
     * Memory usage of an integer interpreted as a requested byte count, in
     * 8-byte words (Plutus {@code NumBytesCostedAsNumWords}):
     * {@code ((abs n - 1) `div` 8) + 1}, so zero gives 0 and everything else
     * is ceiling division of the absolute value by 8. Saturates at
     * {@code Long.MAX_VALUE} like Haskell's {@code SatInt} conversion.
     */
    static long literalByteSize(BigInteger value) {
        BigInteger abs = value.abs();
        if (abs.bitLength() <= 63) {
            long l = abs.longValue();
            return l == 0 ? 0 : (l - 1) / 8 + 1;
        }
        BigInteger words = abs.subtract(BigInteger.ONE).shiftRight(3).add(BigInteger.ONE);
        return words.bitLength() <= 63 ? words.longValue() : Long.MAX_VALUE;
    }

    /**
     * Memory usage of an integer as its literal absolute value, saturating
     * at {@code Long.MAX_VALUE} (Plutus {@code IntegerCostedLiterally}).
     */
    static long literalValue(BigInteger value) {
        BigInteger abs = value.abs();
        return abs.bitLength() <= 63 ? abs.longValue() : Long.MAX_VALUE;
    }

    /**
     * Memory usage of a builtin Value as its binary-search depth (Plutus
     * {@code ValueMaxDepth}): {@code integerLog2(outerSize) + 1} plus
     * {@code integerLog2(maxInnerSize) + 1}, each term zero when the
     * corresponding map is empty.
     */
    static long valueMaxDepth(Constant.ValueConst value) {
        long outer = value.entries().size();
        long inner = 0;
        for (var entry : value.entries()) {
            inner = Math.max(inner, entry.tokens().size());
        }
        long logOuter = outer > 0 ? 64 - Long.numberOfLeadingZeros(outer) : 0;
        long logInner = inner > 0 ? 64 - Long.numberOfLeadingZeros(inner) : 0;
        return logOuter + logInner;
    }

    /**
     * Number of nodes in a Data value (Plutus {@code DataNodeCount}):
     * every constructor, list, map, integer, and bytestring node counts 1.
     */
    static long dataNodeCount(PlutusData data) {
        return switch (data) {
            case PlutusData.IntData _ -> 1;
            case PlutusData.BytesData _ -> 1;
            case PlutusData.ConstrData cd -> {
                long n = 1;
                for (var field : cd.fields()) n += dataNodeCount(field);
                yield n;
            }
            case PlutusData.ListData ld -> {
                long n = 1;
                for (var item : ld.items()) n += dataNodeCount(item);
                yield n;
            }
            case PlutusData.MapData md -> {
                long n = 1;
                for (var entry : md.entries()) {
                    n += dataNodeCount(entry.key()) + dataNodeCount(entry.value());
                }
                yield n;
            }
        };
    }
}
