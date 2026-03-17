package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultUni;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * Data type construction/destruction builtins.
 */
public final class DataBuiltins {

    private DataBuiltins() {}

    // === Constructors ===

    public static CekValue constrData(List<CekValue> args) {
        var tag = asInteger(args.get(0), "ConstrData");
        var fields = asList(args.get(1), "ConstrData");
        var dataFields = new ArrayList<PlutusData>();
        for (var field : fields) {
            if (field instanceof Constant.DataConst dc) {
                dataFields.add(dc.value());
            } else {
                throw new BuiltinException("ConstrData: list element is not Data");
            }
        }
        return mkData(new PlutusData.ConstrData(tag.intValueExact(), dataFields));
    }

    public static CekValue mapData(List<CekValue> args) {
        var list = asList(args.get(0), "MapData");
        var entries = new ArrayList<PlutusData.Pair>();
        for (var elem : list) {
            if (elem instanceof Constant.PairConst pc) {
                if (pc.first() instanceof Constant.DataConst dk && pc.second() instanceof Constant.DataConst dv) {
                    entries.add(new PlutusData.Pair(dk.value(), dv.value()));
                } else {
                    throw new BuiltinException("MapData: pair elements must be Data");
                }
            } else {
                throw new BuiltinException("MapData: list element is not a pair");
            }
        }
        return mkData(new PlutusData.MapData(entries));
    }

    public static CekValue listData(List<CekValue> args) {
        var list = asList(args.get(0), "ListData");
        var items = new ArrayList<PlutusData>();
        for (var elem : list) {
            if (elem instanceof Constant.DataConst dc) {
                items.add(dc.value());
            } else {
                throw new BuiltinException("ListData: list element is not Data");
            }
        }
        return mkData(new PlutusData.ListData(items));
    }

    public static CekValue iData(List<CekValue> args) {
        var i = asInteger(args.get(0), "IData");
        return mkData(new PlutusData.IntData(i));
    }

    public static CekValue bData(List<CekValue> args) {
        var bs = asByteString(args.get(0), "BData");
        return mkData(new PlutusData.BytesData(bs));
    }

    // === Destructors ===

    public static CekValue unConstrData(List<CekValue> args) {
        var d = asData(args.get(0), "UnConstrData");
        if (d instanceof PlutusData.ConstrData cd) {
            var tagConst = new Constant.IntegerConst(BigInteger.valueOf(cd.tag()));
            var fieldConsts = cd.fields().stream()
                    .map(f -> (Constant) new Constant.DataConst(f))
                    .toList();
            var listConst = new Constant.ListConst(DefaultUni.DATA, fieldConsts);
            return mkPair(tagConst, listConst);
        }
        throw new BuiltinException("UnConstrData: not ConstrData");
    }

    public static CekValue unMapData(List<CekValue> args) {
        var d = asData(args.get(0), "UnMapData");
        if (d instanceof PlutusData.MapData md) {
            var pairs = md.entries().stream()
                    .map(e -> (Constant) new Constant.PairConst(
                            new Constant.DataConst(e.key()),
                            new Constant.DataConst(e.value())))
                    .toList();
            return mkList(DefaultUni.pairOf(DefaultUni.DATA, DefaultUni.DATA), pairs);
        }
        throw new BuiltinException("UnMapData: not MapData");
    }

    public static CekValue unListData(List<CekValue> args) {
        var d = asData(args.get(0), "UnListData");
        if (d instanceof PlutusData.ListData ld) {
            var items = ld.items().stream()
                    .map(i -> (Constant) new Constant.DataConst(i))
                    .toList();
            return mkList(DefaultUni.DATA, items);
        }
        throw new BuiltinException("UnListData: not ListData");
    }

    public static CekValue unIData(List<CekValue> args) {
        var d = asData(args.get(0), "UnIData");
        if (d instanceof PlutusData.IntData id) {
            return mkInteger(id.value());
        }
        throw new BuiltinException("UnIData: not IntData");
    }

    public static CekValue unBData(List<CekValue> args) {
        var d = asData(args.get(0), "UnBData");
        if (d instanceof PlutusData.BytesData bd) {
            return mkByteString(bd.value());
        }
        throw new BuiltinException("UnBData: not BytesData");
    }

    public static CekValue equalsData(List<CekValue> args) {
        var a = asData(args.get(0), "EqualsData");
        var b = asData(args.get(1), "EqualsData");
        return mkBool(a.equals(b));
    }

    public static CekValue serialiseData(List<CekValue> args) {
        var d = asData(args.get(0), "SerialiseData");
        try {
            byte[] cbor = com.bloxbean.cardano.julc.vm.java.builtins.DataSerializer.serialize(d);
            return mkByteString(cbor);
        } catch (Exception e) {
            throw new BuiltinException("SerialiseData: CBOR encoding failed: " + e.getMessage(), e);
        }
    }

    /**
     * ChooseData: force=1, arity=6 → (data, constrCase, mapCase, listCase, intCase, bytesCase)
     * Dispatches based on the Data constructor.
     */
    public static CekValue chooseData(List<CekValue> args) {
        var d = asData(args.get(0), "ChooseData");
        return switch (d) {
            case PlutusData.ConstrData _ -> args.get(1);
            case PlutusData.MapData _    -> args.get(2);
            case PlutusData.ListData _   -> args.get(3);
            case PlutusData.IntData _    -> args.get(4);
            case PlutusData.BytesData _  -> args.get(5);
        };
    }

    // === Nil constructors ===

    public static CekValue mkNilData(List<CekValue> args) {
        checkUnit(args.get(0), "MkNilData");
        return mkList(DefaultUni.DATA, List.of());
    }

    public static CekValue mkNilPairData(List<CekValue> args) {
        checkUnit(args.get(0), "MkNilPairData");
        return mkList(DefaultUni.pairOf(DefaultUni.DATA, DefaultUni.DATA), List.of());
    }

    public static CekValue mkPairData(List<CekValue> args) {
        var a = asData(args.get(0), "MkPairData");
        var b = asData(args.get(1), "MkPairData");
        return mkPair(new Constant.DataConst(a), new Constant.DataConst(b));
    }
}
