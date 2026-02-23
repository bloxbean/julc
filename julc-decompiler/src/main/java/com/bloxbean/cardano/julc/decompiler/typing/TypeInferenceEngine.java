package com.bloxbean.cardano.julc.decompiler.typing;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.decompiler.hir.HirType;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;

import java.util.*;

/**
 * Bottom-up type inference on HIR terms.
 * <p>
 * Propagates type information using:
 * <ul>
 *   <li>Builtin type signatures (from BuiltinTypeRules)</li>
 *   <li>Ledger type field positions (from LedgerTypeRecovery)</li>
 *   <li>Data encode/decode coercions</li>
 *   <li>Let binding propagation</li>
 * </ul>
 */
public final class TypeInferenceEngine {

    private final Map<String, HirType> typeEnv = new HashMap<>();
    private final ScriptAnalyzer.ScriptStats stats;

    private TypeInferenceEngine(ScriptAnalyzer.ScriptStats stats) {
        this.stats = stats;
    }

    /**
     * Run type inference on the HIR tree.
     * Returns a new HIR tree with type annotations where possible.
     */
    public static HirTerm infer(HirTerm hir, ScriptAnalyzer.ScriptStats stats) {
        var engine = new TypeInferenceEngine(stats);

        // If arity >= 1, the outermost lambda parameters are likely ScriptContext args
        engine.seedEntrypointTypes(hir);

        return engine.inferTerm(hir);
    }

    /**
     * Seed type information for the entrypoint parameters.
     * For Plutus V3 validators, the single parameter is ScriptContext.
     */
    private void seedEntrypointTypes(HirTerm hir) {
        if (stats.estimatedArity() == 1) {
            // V3 single-arg validator: param is ScriptContext
            if (hir instanceof HirTerm.Lambda lam && !lam.params().isEmpty()) {
                typeEnv.put(lam.params().getFirst(), LedgerTypeRecovery.lookupRecord("ScriptContext")
                        .map(r -> (HirType) r).orElse(HirType.DATA));
            }
        }
    }

    private HirTerm inferTerm(HirTerm term) {
        return switch (term) {
            case HirTerm.Var v -> {
                HirType type = typeEnv.getOrDefault(v.name(), v.type());
                yield new HirTerm.Var(v.name(), type);
            }

            case HirTerm.Let let -> {
                HirTerm value = inferTerm(let.value());
                HirType valueType = inferType(value);
                typeEnv.put(let.name(), valueType);
                HirTerm body = inferTerm(let.body());
                yield new HirTerm.Let(let.name(), value, body);
            }

            case HirTerm.LetRec letRec -> {
                HirTerm value = inferTerm(letRec.value());
                typeEnv.put(letRec.name(), inferType(value));
                HirTerm body = inferTerm(letRec.body());
                yield new HirTerm.LetRec(letRec.name(), value, body);
            }

            case HirTerm.Lambda lam -> {
                HirTerm body = inferTerm(lam.body());
                yield new HirTerm.Lambda(lam.params(), body);
            }

            case HirTerm.If iff -> new HirTerm.If(
                    inferTerm(iff.condition()),
                    inferTerm(iff.thenBranch()),
                    inferTerm(iff.elseBranch()));

            case HirTerm.Switch sw -> {
                HirTerm scrutinee = inferTerm(sw.scrutinee());
                List<HirTerm.SwitchBranch> branches = sw.branches().stream()
                        .map(b -> new HirTerm.SwitchBranch(b.tag(), b.constructorName(),
                                b.fieldNames(), inferTerm(b.body())))
                        .toList();
                yield new HirTerm.Switch(scrutinee, sw.typeName(), branches);
            }

            case HirTerm.BuiltinCall bc -> {
                List<HirTerm> args = bc.args().stream().map(this::inferTerm).toList();
                yield new HirTerm.BuiltinCall(bc.fun(), args);
            }

            case HirTerm.FunCall fc -> {
                List<HirTerm> args = fc.args().stream().map(this::inferTerm).toList();
                yield new HirTerm.FunCall(fc.name(), args);
            }

            case HirTerm.FieldAccess fa -> {
                HirTerm rec = inferTerm(fa.record());
                yield new HirTerm.FieldAccess(rec, fa.fieldName(), fa.fieldIndex(), fa.typeName());
            }

            case HirTerm.Constructor c -> {
                List<HirTerm> fields = c.fields().stream().map(this::inferTerm).toList();
                yield new HirTerm.Constructor(c.typeName(), c.tag(), fields);
            }

            case HirTerm.MethodCall mc -> {
                HirTerm receiver = inferTerm(mc.receiver());
                List<HirTerm> args = mc.args().stream().map(this::inferTerm).toList();
                yield new HirTerm.MethodCall(receiver, mc.methodName(), args);
            }

            case HirTerm.DataEncode de -> new HirTerm.DataEncode(de.encoder(), inferTerm(de.operand()));
            case HirTerm.DataDecode dd -> new HirTerm.DataDecode(dd.decoder(), inferTerm(dd.operand()));

            case HirTerm.Trace tr -> new HirTerm.Trace(inferTerm(tr.message()), inferTerm(tr.body()));

            case HirTerm.ForEach fe -> new HirTerm.ForEach(
                    fe.itemVar(), inferTerm(fe.list()), fe.accVar(),
                    inferTerm(fe.init()), inferTerm(fe.body()));

            case HirTerm.While w -> {
                HirTerm cond = inferTerm(w.condition());
                List<HirTerm> inits = w.accInits().stream().map(this::inferTerm).toList();
                HirTerm body = inferTerm(w.body());
                yield new HirTerm.While(cond, w.accVars(), inits, body);
            }

            case HirTerm.ListLiteral ll -> {
                List<HirTerm> elems = ll.elements().stream().map(this::inferTerm).toList();
                yield new HirTerm.ListLiteral(elems);
            }

            // Literals and terminals pass through
            case HirTerm.IntLiteral _,
                 HirTerm.ByteStringLiteral _,
                 HirTerm.StringLiteral _,
                 HirTerm.BoolLiteral _,
                 HirTerm.UnitLiteral _,
                 HirTerm.DataLiteral _,
                 HirTerm.ConstValue _,
                 HirTerm.Error _,
                 HirTerm.RawUplc _ -> term;
        };
    }

    /**
     * Infer the type of an HIR term.
     */
    private HirType inferType(HirTerm term) {
        return switch (term) {
            case HirTerm.IntLiteral _ -> HirType.INTEGER;
            case HirTerm.ByteStringLiteral _ -> HirType.BYTE_STRING;
            case HirTerm.StringLiteral _ -> HirType.STRING;
            case HirTerm.BoolLiteral _ -> HirType.BOOL;
            case HirTerm.UnitLiteral _ -> HirType.UNIT;
            case HirTerm.DataLiteral _ -> HirType.DATA;
            case HirTerm.Var v -> typeEnv.getOrDefault(v.name(), v.type());
            case HirTerm.BuiltinCall bc -> BuiltinTypeRules.returnType(bc.fun());
            case HirTerm.DataEncode _ -> HirType.DATA;
            case HirTerm.DataDecode dd -> switch (dd.decoder()) {
                case UnIData -> HirType.INTEGER;
                case UnBData -> HirType.BYTE_STRING;
                default -> HirType.UNKNOWN;
            };
            case HirTerm.Let let -> inferType(let.body());
            case HirTerm.LetRec letRec -> inferType(letRec.body());
            case HirTerm.If iff -> inferType(iff.thenBranch());
            case HirTerm.FieldAccess fa -> {
                var recordType = LedgerTypeRecovery.lookupRecord(fa.typeName());
                yield recordType.map(rt -> {
                    if (fa.fieldIndex() >= 0 && fa.fieldIndex() < rt.fields().size()) {
                        return rt.fields().get(fa.fieldIndex()).type();
                    }
                    return (HirType) HirType.UNKNOWN;
                }).orElse(HirType.UNKNOWN);
            }
            default -> HirType.UNKNOWN;
        };
    }
}
