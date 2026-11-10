package org.julclang.vm.java.cost;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.vm.BuiltinSemanticsVariant;
import org.julclang.vm.java.CekValue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The argument sizing the evaluator used before it sized arguments on demand,
 * kept verbatim (every argument sized, byte strings measured through their
 * defensive copies, integers through {@code abs()}) as the oracle the lazy
 * sizing is checked against. Do not "fix" or share code with the production
 * sizing: its value is that it is independent of it.
 */
final class EagerArgSizesOracle {

    private EagerArgSizesOracle() {
    }

    static long[] argSizes(BuiltinSemanticsVariant variant, DefaultFun fun, List<CekValue> args) {
        long[] sizes = new long[args.size()];
        for (int i = 0; i < args.size(); i++) {
            sizes[i] = sizeOf(args.get(i));
        }
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
                if (idx(args, 3) instanceof Constant.ValueConst vc) sizes[3] = valueMaxDepth(vc);
            }
            case LookupCoin -> {
                if (idx(args, 2) instanceof Constant.ValueConst vc) sizes[2] = valueMaxDepth(vc);
            }
            case UnValueData -> {
                if (idx(args, 0) instanceof Constant.DataConst dc) sizes[0] = dataNodeCount(dc.value());
            }
            default -> { }
        }
        if (variant.usesUtf8StringCosting()) {
            switch (fun) {
                case AppendString, EqualsString -> {
                    textByteLength(args, sizes, 0);
                    textByteLength(args, sizes, 1);
                }
                case EncodeUtf8 -> textByteLength(args, sizes, 0);
                default -> { }
            }
        }
        return sizes;
    }

    private static void textByteLength(List<CekValue> args, long[] sizes, int index) {
        if (index < args.size()
                && args.get(index) instanceof CekValue.VCon(Constant.StringConst text)) {
            sizes[index] = (long) text.value().getBytes(StandardCharsets.UTF_8).length / 4;
        }
    }

    static long sizeOf(CekValue value) {
        if (value instanceof CekValue.VCon vcon) {
            return sizeOfConstant(vcon.constant());
        }
        return 1;
    }

    static long sizeOfConstant(Constant c) {
        return switch (c) {
            case Constant.IntegerConst ic -> integerSize(ic.value());
            case Constant.ByteStringConst bs -> byteStringSize(bs.value());
            case Constant.StringConst s -> s.value().codePointCount(0, s.value().length());
            case Constant.BoolConst _ -> 1;
            case Constant.UnitConst _ -> 1;
            case Constant.DataConst dc -> sizeOfData(dc.value());
            case Constant.ListConst lc -> lc.values().size();
            case Constant.PairConst _ -> Long.MAX_VALUE;
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

    static long sizeOfData(PlutusData data) {
        return switch (data) {
            case PlutusData.IntData id -> 4 + integerSize(id.value());
            case PlutusData.BytesData bd -> 4 + byteStringSize(bd.value());
            case PlutusData.ConstrData cd -> {
                long size = 4;
                for (var field : cd.fields()) size += sizeOfData(field);
                yield size;
            }
            case PlutusData.ListData ld -> {
                long size = 4;
                for (var item : ld.items()) size += sizeOfData(item);
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

    static long integerSize(BigInteger value) {
        if (value.signum() == 0) return 1;
        return (value.abs().bitLength() - 1) / 64 + 1;
    }

    private static long byteStringSize(byte[] bytes) {
        return (bytes.length - 1) / 8 + 1;
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

    private static long literalByteSize(BigInteger value) {
        BigInteger abs = value.abs();
        if (abs.bitLength() <= 63) {
            long l = abs.longValue();
            return l == 0 ? 0 : (l - 1) / 8 + 1;
        }
        BigInteger words = abs.subtract(BigInteger.ONE).shiftRight(3).add(BigInteger.ONE);
        return words.bitLength() <= 63 ? words.longValue() : Long.MAX_VALUE;
    }

    private static long literalValue(BigInteger value) {
        BigInteger abs = value.abs();
        return abs.bitLength() <= 63 ? abs.longValue() : Long.MAX_VALUE;
    }

    private static long valueMaxDepth(Constant.ValueConst value) {
        long outer = value.entries().size();
        long inner = 0;
        for (var entry : value.entries()) {
            inner = Math.max(inner, entry.tokens().size());
        }
        long logOuter = outer > 0 ? 64 - Long.numberOfLeadingZeros(outer) : 0;
        long logInner = inner > 0 ? 64 - Long.numberOfLeadingZeros(inner) : 0;
        return logOuter + logInner;
    }

    private static long dataNodeCount(PlutusData data) {
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
