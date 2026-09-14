package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.core.ArraySemantics;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-046 (O10): fold array builtin calls over literals at the safe profile on the PV11 target.
 *
 * <p>The domain is {@code ListToArray}, {@code LengthOfArray} and {@code IndexArray},
 * evaluated by {@link ArraySemantics}, the same code the VM runs. Two literal shapes are added
 * to the ones {@link LiteralFoldPass} knows: a <b>list literal</b>, the {@code MkCons} chain
 * over Data-encoded literal elements that {@code JulcList.of(...)} and {@code JulcArray.of(...)}
 * emit (an integer, byte string, boolean or string constant wrapped exactly as
 * {@link PirHelpers#wrapEncode} wraps it, a Data constant, or a nested list literal), and a
 * once-bound local holding one. {@code ListToArray} of a list literal therefore folds to a
 * UPLC array constant with the same Data elements, {@code LengthOfArray} of an array literal to
 * its length, and {@code IndexArray} of an array literal at a literal index to the element.
 *
 * <p>An element folded out of an array literal is a Data constant; the decode that
 * {@code JulcArray.get} wraps around the access ({@link PirHelpers#wrapDecode}: {@code UnIData},
 * {@code UnBData}, {@code UnListData}, {@code UnMapData}, the Bool form
 * {@code EqualsInteger(FstPair(UnConstrData(d)), 1)}, the String form
 * {@code DecodeUtf8(UnBData(d))}) is folded as well when the element has the shape the decode
 * expects, by the decode builtins' own semantics. Such a decode is folded <b>only</b> over a
 * constant this pass produced from an array access, never over a Data constant that was in the
 * program already, so no program compiled before this rule is touched. A decode over an
 * element of the wrong shape stays and fails at runtime exactly as before.
 *
 * <p>An out-of-range literal index stays as written and fails at runtime with the builtin's
 * text; a runtime index over an array literal keeps the access and embeds the constant. Rule
 * {@value #RULE}.
 */
public final class ArrayLiteralFoldPass extends LiteralFoldPass {

    public static final String RULE = "pv11.o10.array-literal-fold";

    private static final Set<DefaultFun> ARRAY_BUILTINS = Set.of(
            DefaultFun.ListToArray, DefaultFun.LengthOfArray, DefaultFun.IndexArray);
    private static final DefaultUni DATA = new DefaultUni.Data();
    private static final Constant ONE = Constant.integer(BigInteger.ONE);

    /** Constants this pass produced by an array access or a decode of one (identity). */
    private final Set<PirTerm> produced = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public ArrayLiteralFoldPass(CompilationContext context, Map<PirTerm, SourceLocation> positions) {
        super(context, positions);
    }

    @Override
    protected String rule() {
        return RULE;
    }

    @Override
    protected ProtocolCapability capability() {
        return ProtocolCapability.ARRAY_CONSTANTS;
    }

    @Override
    protected Set<DefaultFun> builtins() {
        return ARRAY_BUILTINS;
    }

    @Override
    protected Constant evaluate(DefaultFun fun, List<Constant> args) {
        try {
            return switch (fun) {
                case ListToArray -> args.get(0) instanceof Constant.ListConst list
                        ? ArraySemantics.listToArray(list) : null;
                case LengthOfArray -> args.get(0) instanceof Constant.ArrayConst array
                        ? Constant.integer(ArraySemantics.lengthOfArray(array)) : null;
                case IndexArray -> args.get(0) instanceof Constant.ArrayConst array
                        && args.get(1) instanceof Constant.IntegerConst index
                        ? ArraySemantics.indexArray(array, index.value()) : null;
                default -> null;
            };
        } catch (ArraySemantics.EvaluationFailure rejected) {
            return null;
        }
    }

    /** A constant, a literal local, or a list literal (bare or through a once-bound local). */
    @Override
    protected Constant literalOf(PirTerm term) {
        var literal = super.literalOf(term);
        if (literal != null) return literal;
        return listLiteral(term);
    }

    @Override
    protected PirTerm foldNode(PirTerm mapped) {
        var call = foldLiteralCall(mapped);
        if (call != null) {
            if (spineOf(mapped) != null && isBuiltin(spineOf(mapped).head(), DefaultFun.IndexArray)) {
                produced.add(call);
            }
            return call;
        }
        return foldDecode(mapped);
    }

    /**
     * A decode builtin over a constant this pass produced: the element's shape decides, by the
     * builtin's own semantics; a mismatch stays and fails at runtime.
     */
    private PirTerm foldDecode(PirTerm term) {
        if (!(term instanceof PirTerm.App app)) return null;
        if (app.function() instanceof PirTerm.Builtin builtin
                && app.argument() instanceof PirTerm.Const c && produced.contains(c)) {
            Constant result = switch (builtin.fun()) {
                case UnIData -> c.value() instanceof Constant.DataConst d
                        && d.value() instanceof PlutusData.IntData i ? Constant.integer(i.value()) : null;
                case UnBData -> c.value() instanceof Constant.DataConst d
                        && d.value() instanceof PlutusData.BytesData b ? Constant.byteString(b.value()) : null;
                case UnListData -> c.value() instanceof Constant.DataConst d
                        && d.value() instanceof PlutusData.ListData l
                        ? new Constant.ListConst(DATA, l.items().stream().map(Constant::data).toList()) : null;
                case UnMapData -> c.value() instanceof Constant.DataConst d
                        && d.value() instanceof PlutusData.MapData m
                        ? new Constant.ListConst(DefaultUni.pairOf(DATA, DATA), m.entries().stream()
                                .map(e -> (Constant) new Constant.PairConst(Constant.data(e.key()), Constant.data(e.value()))).toList())
                        : null;
                case UnConstrData -> c.value() instanceof Constant.DataConst d
                        && d.value() instanceof PlutusData.ConstrData k
                        ? new Constant.PairConst(Constant.integer(k.constructorTag()),
                                new Constant.ListConst(DATA, k.fields().stream().map(Constant::data).toList()))
                        : null;
                case FstPair -> c.value() instanceof Constant.PairConst p ? p.first() : null;
                case DecodeUtf8 -> c.value() instanceof Constant.ByteStringConst b ? utf8(b.value()) : null;
                default -> null;
            };
            if (result == null) return null;
            if (!fitsObjective(Term.apply(Term.builtin(builtin.fun()), Term.const_(c.value())), result)) return null;
            var out = folded(result);
            produced.add(out);
            return out;
        }
        // The Bool decode's comparison: EqualsInteger(<produced tag>, 1).
        if (app.function() instanceof PirTerm.App inner && isBuiltin(inner.function(), DefaultFun.EqualsInteger)
                && inner.argument() instanceof PirTerm.Const tag && produced.contains(tag)
                && tag.value() instanceof Constant.IntegerConst t
                && app.argument() instanceof PirTerm.Const one && one.value().equals(ONE)) {
            var result = Constant.bool(t.value().equals(BigInteger.ONE));
            var replaced = Term.apply(Term.apply(Term.builtin(DefaultFun.EqualsInteger), Term.const_(tag.value())), Term.const_(ONE));
            return fitsObjective(replaced, result) ? folded(result) : null;
        }
        return null;
    }

    private static Constant utf8(byte[] bytes) {
        try {
            return Constant.string(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException invalid) {
            return null;
        }
    }

    /**
     * {@code MkCons(e1, MkCons(e2, ... MkNilData ()))} over Data-encoded literal elements, or a
     * once-bound local holding one, as a {@code list data} constant; null otherwise.
     */
    private Constant.ListConst listLiteral(PirTerm term) {
        var elements = new ArrayList<Constant>();
        PirTerm current = term;
        while (true) {
            if (current instanceof PirTerm.Var v && super.literalOf(v) instanceof Constant.ListConst tail
                    && tail.elemType().equals(DATA)) {
                elements.addAll(tail.values());
                break;
            }
            if (current instanceof PirTerm.App nil && isBuiltin(nil.function(), DefaultFun.MkNilData)
                    && nil.argument() instanceof PirTerm.Const unit && unit.value() instanceof Constant.UnitConst) {
                break;
            }
            if (current instanceof PirTerm.App cons && cons.function() instanceof PirTerm.App head
                    && isBuiltin(head.function(), DefaultFun.MkCons)) {
                var element = dataLiteral(head.argument());
                if (element == null) return null;
                elements.add(element);
                current = cons.argument();
                continue;
            }
            return null;
        }
        return new Constant.ListConst(DATA, elements);
    }

    /** A Data-encoded literal element exactly as {@link PirHelpers#wrapEncode} spells it over a constant. */
    private Constant dataLiteral(PirTerm term) {
        if (super.literalOf(term) instanceof Constant.DataConst d) return d;
        if (term instanceof PirTerm.App app) {
            if (isBuiltin(app.function(), DefaultFun.IData) && super.literalOf(app.argument()) instanceof Constant.IntegerConst i) {
                return Constant.data(PlutusData.integer(i.value()));
            }
            if (isBuiltin(app.function(), DefaultFun.BData)) {
                if (super.literalOf(app.argument()) instanceof Constant.ByteStringConst b) {
                    return Constant.data(PlutusData.bytes(b.value()));
                }
                if (app.argument() instanceof PirTerm.App encode && isBuiltin(encode.function(), DefaultFun.EncodeUtf8)
                        && super.literalOf(encode.argument()) instanceof Constant.StringConst s) {
                    return Constant.data(PlutusData.bytes(s.value().getBytes(StandardCharsets.UTF_8)));
                }
            }
            if (isBuiltin(app.function(), DefaultFun.ListData)) {
                var nested = listLiteral(app.argument());
                if (nested == null || !nested.values().stream().allMatch(c -> c instanceof Constant.DataConst)) return null;
                return Constant.data(PlutusData.list(nested.values().stream()
                        .map(c -> ((Constant.DataConst) c).value()).toArray(PlutusData[]::new)));
            }
        }
        if (term instanceof PirTerm.IfThenElse ite && super.literalOf(ite.cond()) instanceof Constant.BoolConst b
                && isConstr(ite.thenBranch(), 1) && isConstr(ite.elseBranch(), 0)) {
            return Constant.data(PlutusData.constr(b.value() ? 1 : 0));
        }
        return null;
    }

    /** {@code ConstrData(tag, MkNilData ())}, the Bool encoding of {@link PirHelpers#wrapEncode}. */
    private static boolean isConstr(PirTerm term, long tag) {
        return term instanceof PirTerm.App app && app.function() instanceof PirTerm.App head
                && isBuiltin(head.function(), DefaultFun.ConstrData)
                && head.argument() instanceof PirTerm.Const t && t.value().equals(Constant.integer(tag))
                && app.argument() instanceof PirTerm.App nil && isBuiltin(nil.function(), DefaultFun.MkNilData)
                && nil.argument() instanceof PirTerm.Const unit && unit.value() instanceof Constant.UnitConst;
    }
}
