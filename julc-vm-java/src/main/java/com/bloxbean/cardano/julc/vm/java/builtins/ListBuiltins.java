package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultUni;
import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.util.ArrayList;
import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * List operation builtins.
 */
public final class ListBuiltins {

    private ListBuiltins() {}

    /**
     * MkCons: force=1, arity=2 → (element, list)
     * Prepends an element to a list.
     */
    public static CekValue mkCons(List<CekValue> args) {
        var elem = asConstant(args.get(0), "MkCons");
        var listConst = asListConst(args.get(1), "MkCons");
        // Type check: element type must match list element type
        if (!typeMatches(elem.type(), listConst.elemType())) {
            throw new BuiltinException("MkCons: element type " + elem.type() +
                    " does not match list element type " + listConst.elemType());
        }
        var newValues = new ArrayList<Constant>();
        newValues.add(elem);
        newValues.addAll(listConst.values());
        return mkList(listConst.elemType(), newValues);
    }

    /**
     * Check if two DefaultUni types structurally match.
     */
    private static boolean typeMatches(DefaultUni a, DefaultUni b) {
        return a.equals(b) || a.getClass() == b.getClass();
    }

    /**
     * HeadList: force=1, arity=1 → (list)
     * Returns the first element.
     */
    public static CekValue headList(List<CekValue> args) {
        var listConst = asListConst(args.get(0), "HeadList");
        if (listConst.values().isEmpty()) {
            throw new BuiltinException("HeadList: empty list");
        }
        return new CekValue.VCon(listConst.values().getFirst());
    }

    /**
     * TailList: force=1, arity=1 → (list)
     * Returns the list without the first element.
     */
    public static CekValue tailList(List<CekValue> args) {
        var listConst = asListConst(args.get(0), "TailList");
        if (listConst.values().isEmpty()) {
            throw new BuiltinException("TailList: empty list");
        }
        return mkList(listConst.elemType(), listConst.values().subList(1, listConst.values().size()));
    }

    /**
     * NullList: force=1, arity=1 → (list)
     * Returns true if the list is empty.
     */
    public static CekValue nullList(List<CekValue> args) {
        var listConst = asListConst(args.get(0), "NullList");
        return mkBool(listConst.values().isEmpty());
    }

    /**
     * ChooseList: force=2, arity=3 → (list, nilCase, consCase)
     * Returns nilCase if list is empty, consCase otherwise.
     */
    public static CekValue chooseList(List<CekValue> args) {
        var listConst = asListConst(args.get(0), "ChooseList");
        return listConst.values().isEmpty() ? args.get(1) : args.get(2);
    }
}
