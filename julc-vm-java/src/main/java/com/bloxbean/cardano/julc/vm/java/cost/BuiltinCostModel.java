package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.java.CekValue;

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
     *   <li>Pair: sum of both element sizes</li>
     *   <li>BLS elements: fixed sizes</li>
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
            case Constant.StringConst s -> s.value().length();
            case Constant.BoolConst _ -> 1;
            case Constant.UnitConst _ -> 1;
            case Constant.DataConst dc -> sizeOfData(dc.value());
            case Constant.ListConst lc -> lc.values().size();
            case Constant.PairConst pc -> sizeOfConstant(pc.first()) + sizeOfConstant(pc.second());
            case Constant.Bls12_381_G1Element _ -> 48;
            case Constant.Bls12_381_G2Element _ -> 96;
            case Constant.Bls12_381_MlResult _ -> 576;
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
     * {@code (bitLength - 1) / 64 + 1}. Zero has size 1.
     */
    static long integerSize(BigInteger value) {
        if (value.signum() == 0) return 1;
        return (value.bitLength() - 1) / 64 + 1;
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
}
