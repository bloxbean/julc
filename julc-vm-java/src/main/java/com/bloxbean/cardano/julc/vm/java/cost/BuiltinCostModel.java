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
 * Argument sizes are computed from the evaluated {@link CekValue} arguments.
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
     * This follows the Plutus specification:
     * <ul>
     *   <li>Integer: number of bytes needed to represent the value</li>
     *   <li>ByteString: length in bytes</li>
     *   <li>String: length in characters</li>
     *   <li>Bool, Unit: 1</li>
     *   <li>Data: recursive size</li>
     *   <li>List: length of the list</li>
     *   <li>Pair: size of both elements (returned separately via argSizes)</li>
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
            case Constant.ByteStringConst bs -> bs.value().length;
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

    /** Compute the size of a PlutusData value (recursive). */
    public static long sizeOfData(PlutusData data) {
        return switch (data) {
            case PlutusData.IntData id -> integerSize(id.value());
            case PlutusData.BytesData bd -> bd.value().length;
            case PlutusData.ConstrData cd -> {
                long size = 4; // tag overhead
                for (var field : cd.fields()) {
                    size += sizeOfData(field);
                }
                yield size;
            }
            case PlutusData.ListData ld -> {
                long size = 4; // list overhead
                for (var item : ld.items()) {
                    size += sizeOfData(item);
                }
                yield size;
            }
            case PlutusData.MapData md -> {
                long size = 4; // map overhead
                for (var entry : md.entries()) {
                    size += sizeOfData(entry.key()) + sizeOfData(entry.value());
                }
                yield size;
            }
        };
    }

    /** Number of bytes needed to represent a BigInteger. */
    private static long integerSize(BigInteger value) {
        if (value.signum() == 0) return 1;
        // bitLength() gives the number of bits excluding the sign bit
        return (value.bitLength() + 7) / 8;
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
