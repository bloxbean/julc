package com.bloxbean.cardano.julc.vm.truffle.runtime;

import com.oracle.truffle.api.interop.TruffleObject;

import java.util.List;

/**
 * Constructor value (SOP — Sums of Products, Plutus V3).
 *
 * @see com.bloxbean.cardano.julc.vm.java.CekValue.VConstr
 */
public final class UplcConstrValue implements TruffleObject {

    private final long tag;
    private final Object[] fields;

    public UplcConstrValue(long tag, Object[] fields) {
        this.tag = tag;
        this.fields = fields;
    }

    public long getTag() {
        return tag;
    }

    public Object[] getFields() {
        return fields;
    }

    public int getFieldCount() {
        return fields.length;
    }

    public Object getField(int index) {
        return fields[index];
    }

    @Override
    public String toString() {
        return "UplcConstrValue(tag=" + tag + ", fields=" + fields.length + ")";
    }
}
