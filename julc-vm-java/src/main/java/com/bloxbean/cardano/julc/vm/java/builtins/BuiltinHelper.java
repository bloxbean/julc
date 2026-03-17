package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultUni;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.math.BigInteger;
import java.util.List;

/**
 * Helper methods for extracting typed values from CekValue arguments.
 */
public final class BuiltinHelper {

    private BuiltinHelper() {}

    public static BigInteger asInteger(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.IntegerConst ic)) {
            return ic.value();
        }
        throw new BuiltinException(context + ": expected integer, got " + describeValue(v));
    }

    public static byte[] asByteString(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.ByteStringConst bs)) {
            return bs.value();
        }
        throw new BuiltinException(context + ": expected bytestring, got " + describeValue(v));
    }

    public static String asString(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.StringConst sc)) {
            return sc.value();
        }
        throw new BuiltinException(context + ": expected string, got " + describeValue(v));
    }

    public static boolean asBool(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.BoolConst bc)) {
            return bc.value();
        }
        throw new BuiltinException(context + ": expected bool, got " + describeValue(v));
    }

    public static PlutusData asData(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.DataConst dc)) {
            return dc.value();
        }
        throw new BuiltinException(context + ": expected data, got " + describeValue(v));
    }

    public static List<Constant> asList(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.ListConst lc)) {
            return lc.values();
        }
        throw new BuiltinException(context + ": expected list, got " + describeValue(v));
    }

    public static Constant.ListConst asListConst(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.ListConst lc)) {
            return lc;
        }
        throw new BuiltinException(context + ": expected list, got " + describeValue(v));
    }

    public static Constant.PairConst asPair(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.PairConst pc)) {
            return pc;
        }
        throw new BuiltinException(context + ": expected pair, got " + describeValue(v));
    }

    public static Constant asConstant(CekValue v, String context) {
        if (v instanceof CekValue.VCon vc) {
            return vc.constant();
        }
        throw new BuiltinException(context + ": expected constant, got " + describeValue(v));
    }

    public static void checkUnit(CekValue v, String context) {
        if (!(v instanceof CekValue.VCon(Constant.UnitConst _))) {
            throw new BuiltinException(context + ": expected unit, got " + describeValue(v));
        }
    }

    public static CekValue mkInteger(BigInteger value) {
        return new CekValue.VCon(new Constant.IntegerConst(value));
    }

    public static CekValue mkInteger(long value) {
        return mkInteger(BigInteger.valueOf(value));
    }

    public static CekValue mkByteString(byte[] value) {
        return new CekValue.VCon(new Constant.ByteStringConst(value));
    }

    public static CekValue mkString(String value) {
        return new CekValue.VCon(new Constant.StringConst(value));
    }

    public static CekValue mkBool(boolean value) {
        return new CekValue.VCon(new Constant.BoolConst(value));
    }

    public static CekValue mkUnit() {
        return new CekValue.VCon(new Constant.UnitConst());
    }

    public static CekValue mkData(PlutusData value) {
        return new CekValue.VCon(new Constant.DataConst(value));
    }

    public static CekValue mkList(DefaultUni elemType, List<Constant> values) {
        return new CekValue.VCon(new Constant.ListConst(elemType, values));
    }

    public static CekValue mkPair(Constant first, Constant second) {
        return new CekValue.VCon(new Constant.PairConst(first, second));
    }

    private static String describeValue(CekValue v) {
        return switch (v) {
            case CekValue.VCon vc -> "VCon(" + vc.constant().type() + ")";
            case CekValue.VDelay _ -> "VDelay";
            case CekValue.VLam _ -> "VLam";
            case CekValue.VConstr vc -> "VConstr(" + vc.tag() + ")";
            case CekValue.VBuiltin vb -> "VBuiltin(" + vb.fun() + ")";
        };
    }
}
