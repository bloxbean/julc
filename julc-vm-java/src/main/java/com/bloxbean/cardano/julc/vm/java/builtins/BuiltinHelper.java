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

    // --- Array and Value type helpers ---

    public static Constant.ArrayConst asArrayConst(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.ArrayConst ac)) {
            return ac;
        }
        throw new BuiltinException(context + ": expected array, got " + describeValue(v));
    }

    public static Constant.ValueConst asValueConst(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.ValueConst vc)) {
            return vc;
        }
        throw new BuiltinException(context + ": expected value, got " + describeValue(v));
    }

    public static CekValue mkArray(DefaultUni elemType, List<Constant> values) {
        return new CekValue.VCon(new Constant.ArrayConst(elemType, values));
    }

    public static CekValue mkValue(List<Constant.ValueConst.ValueEntry> entries) {
        return new CekValue.VCon(new Constant.ValueConst(entries));
    }

    // --- BLS12-381 type helpers ---

    public static byte[] asG1Element(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.Bls12_381_G1Element g1)) {
            return g1.value();
        }
        throw new BuiltinException(context + ": expected bls12_381_G1_element, got " + describeValue(v));
    }

    public static byte[] asG2Element(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.Bls12_381_G2Element g2)) {
            return g2.value();
        }
        throw new BuiltinException(context + ": expected bls12_381_G2_element, got " + describeValue(v));
    }

    public static byte[] asMlResult(CekValue v, String context) {
        if (v instanceof CekValue.VCon(Constant.Bls12_381_MlResult ml)) {
            return ml.value();
        }
        throw new BuiltinException(context + ": expected bls12_381_mlresult, got " + describeValue(v));
    }

    public static CekValue mkG1Element(byte[] value) {
        return new CekValue.VCon(new Constant.Bls12_381_G1Element(value));
    }

    public static CekValue mkG2Element(byte[] value) {
        return new CekValue.VCon(new Constant.Bls12_381_G2Element(value));
    }

    public static CekValue mkMlResult(byte[] value) {
        return new CekValue.VCon(new Constant.Bls12_381_MlResult(value));
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
