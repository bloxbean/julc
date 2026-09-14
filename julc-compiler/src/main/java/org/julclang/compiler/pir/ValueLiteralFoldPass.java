package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.NativeValueSemantics;
import org.julclang.core.PlutusData;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-045 (O14): fold native Value builtin calls whose arguments are all literals into the
 * literal they evaluate to, at the safe profile on the PV11 target. The domain is
 * {@code InsertCoin}, {@code LookupCoin}, {@code UnionValue}, {@code ValueContains},
 * {@code ScaleValue}, {@code ValueData} and {@code UnValueData}, evaluated by
 * {@link NativeValueSemantics}, the same code the VM runs. {@code NativeValueLib}'s methods
 * are wrappers in the sense of {@link LiteralFoldPass}; the producers
 * {@code Builtins.emptyValue}, {@code singletonValue} and {@code lovelaceValue} are
 * intrinsics that inline the constant or the bare {@code InsertCoin} spine at the call site.
 * No algebraic identity is applied: {@code lookupCoin(p, t, emptyValue())} with a runtime key
 * stays a call. Rule {@value #RULE}.
 */
public final class ValueLiteralFoldPass extends LiteralFoldPass {

    public static final String RULE = "pv11.o14.value-literal-fold";

    private static final Set<DefaultFun> VALUE_BUILTINS = Set.of(
            DefaultFun.InsertCoin, DefaultFun.LookupCoin, DefaultFun.UnionValue,
            DefaultFun.ValueContains, DefaultFun.ScaleValue, DefaultFun.ValueData,
            DefaultFun.UnValueData);

    public ValueLiteralFoldPass(CompilationContext context, Map<PirTerm, SourceLocation> positions) {
        super(context, positions);
    }

    @Override
    protected String rule() {
        return RULE;
    }

    @Override
    protected ProtocolCapability capability() {
        return ProtocolCapability.VALUE_CONSTANTS;
    }

    @Override
    protected Set<DefaultFun> builtins() {
        return VALUE_BUILTINS;
    }

    @Override
    protected Constant evaluate(DefaultFun fun, List<Constant> args) {
        try {
            return switch (fun) {
                case InsertCoin -> new Constant.ValueConst(NativeValueSemantics.insertCoin(
                        bytes(args.get(0)), bytes(args.get(1)), integer(args.get(2)), value(args.get(3))).entries());
                case LookupCoin -> Constant.integer(NativeValueSemantics.lookupCoin(
                        bytes(args.get(0)), bytes(args.get(1)), value(args.get(2))));
                case UnionValue -> NativeValueSemantics.unionValue(value(args.get(0)), value(args.get(1)));
                case ValueContains -> Constant.bool(NativeValueSemantics.valueContains(value(args.get(0)), value(args.get(1))));
                case ScaleValue -> NativeValueSemantics.scaleValue(integer(args.get(0)), value(args.get(1)));
                case ValueData -> Constant.data(NativeValueSemantics.valueData(value(args.get(0))));
                case UnValueData -> NativeValueSemantics.unValueData(data(args.get(0)));
                default -> null;
            };
        } catch (NativeValueSemantics.EvaluationFailure | IllegalArgumentException rejected) {
            return null;
        }
    }

    private static byte[] bytes(Constant c) {
        if (c instanceof Constant.ByteStringConst b) return b.value();
        throw new IllegalArgumentException("not a bytestring literal");
    }

    private static BigInteger integer(Constant c) {
        if (c instanceof Constant.IntegerConst i) return i.value();
        throw new IllegalArgumentException("not an integer literal");
    }

    private static Constant.ValueConst value(Constant c) {
        if (c instanceof Constant.ValueConst v) return v;
        throw new IllegalArgumentException("not a value literal");
    }

    private static PlutusData data(Constant c) {
        if (c instanceof Constant.DataConst d) return d.value();
        throw new IllegalArgumentException("not a data literal");
    }
}
