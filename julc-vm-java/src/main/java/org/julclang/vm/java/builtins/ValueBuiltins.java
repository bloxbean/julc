package org.julclang.vm.java.builtins;

import org.julclang.core.Constant.ValueConst;
import org.julclang.core.NativeValueSemantics;
import org.julclang.vm.java.CekValue;

import java.util.List;
import java.util.function.Supplier;

import static org.julclang.vm.java.builtins.BuiltinHelper.asByteString;
import static org.julclang.vm.java.builtins.BuiltinHelper.asData;
import static org.julclang.vm.java.builtins.BuiltinHelper.asInteger;
import static org.julclang.vm.java.builtins.BuiltinHelper.asValueConst;
import static org.julclang.vm.java.builtins.BuiltinHelper.mkBool;
import static org.julclang.vm.java.builtins.BuiltinHelper.mkData;
import static org.julclang.vm.java.builtins.BuiltinHelper.mkInteger;

/**
 * MaryEraValue (CIP-153) builtin implementations.
 * <p>
 * Argument unwrapping and result wrapping live here; the semantics (sorted entries, zero
 * removal, Int128 quantities, 32-byte keys, strict Data decoding and every failure text) are
 * pinned once in {@link NativeValueSemantics}, which the compiler's literal fold (ADR-045)
 * shares with this runtime.
 */
public final class ValueBuiltins {

    private ValueBuiltins() {}

    /** InsertCoin: arity=4 → (policyId, tokenName, quantity, value). */
    public static CekValue insertCoin(List<CekValue> args) {
        byte[] policyId = asByteString(args.get(0), "InsertCoin");
        byte[] tokenName = asByteString(args.get(1), "InsertCoin");
        var quantity = asInteger(args.get(2), "InsertCoin");
        var value = asValueConst(args.get(3), "InsertCoin");
        return value(() -> NativeValueSemantics.insertCoin(policyId, tokenName, quantity, value));
    }

    /** LookupCoin: arity=3 → (policyId, tokenName, value); zero when absent. */
    public static CekValue lookupCoin(List<CekValue> args) {
        byte[] policyId = asByteString(args.get(0), "LookupCoin");
        byte[] tokenName = asByteString(args.get(1), "LookupCoin");
        var value = asValueConst(args.get(2), "LookupCoin");
        return mkInteger(NativeValueSemantics.lookupCoin(policyId, tokenName, value));
    }

    /** UnionValue: arity=2 → (value, value); quantities are added, overflow is checked. */
    public static CekValue unionValue(List<CekValue> args) {
        var a = asValueConst(args.get(0), "UnionValue");
        var b = asValueConst(args.get(1), "UnionValue");
        return value(() -> NativeValueSemantics.unionValue(a, b));
    }

    /** ValueContains: arity=2 → (value, value); fails on a negative quantity in either value. */
    public static CekValue valueContains(List<CekValue> args) {
        var a = asValueConst(args.get(0), "ValueContains");
        var b = asValueConst(args.get(1), "ValueContains");
        try {
            return mkBool(NativeValueSemantics.valueContains(a, b));
        } catch (NativeValueSemantics.EvaluationFailure failure) {
            throw new BuiltinException(failure.getMessage());
        }
    }

    /** ValueData: arity=1 → (value); the canonical Map[B, Map[B, I]] encoding. */
    public static CekValue valueData(List<CekValue> args) {
        var value = asValueConst(args.get(0), "ValueData");
        return mkData(NativeValueSemantics.valueData(value));
    }

    /** UnValueData: arity=1 → (data); strict validation, nothing is normalised. */
    public static CekValue unValueData(List<CekValue> args) {
        var data = asData(args.get(0), "UnValueData");
        return value(() -> NativeValueSemantics.unValueData(data));
    }

    /** ScaleValue: arity=2 → (integer, value); overflow is checked, a zero scalar yields the empty value. */
    public static CekValue scaleValue(List<CekValue> args) {
        var scalar = asInteger(args.get(0), "ScaleValue");
        var value = asValueConst(args.get(1), "ScaleValue");
        return value(() -> NativeValueSemantics.scaleValue(scalar, value));
    }

    private static CekValue value(Supplier<ValueConst> semantics) {
        try {
            return new CekValue.VCon(semantics.get());
        } catch (NativeValueSemantics.EvaluationFailure failure) {
            throw new BuiltinException(failure.getMessage());
        }
    }
}
