package com.bloxbean.cardano.julc.decompiler.lift;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.decompiler.hir.HirType;

import java.util.ArrayList;
import java.util.List;

/**
 * Main UPLC to HIR lifter.
 * <p>
 * Orchestrates pattern recognizers in priority order to convert raw UPLC terms
 * into structured HIR nodes. The lifting proceeds in a single recursive pass,
 * trying recognizers from most specific to least specific at each node.
 */
public final class UplcLifter {

    private int varCounter = 0;

    private UplcLifter() {}

    /**
     * Lift a UPLC term to HIR.
     */
    public static HirTerm lift(Term term) {
        return new UplcLifter().liftTerm(term);
    }

    private HirTerm liftTerm(Term term) {
        // Priority 1: V3 SOP patterns (Constr/Case)
        var sopConstr = SopRecognizer.matchConstr(term);
        if (sopConstr != null) {
            return liftConstr(sopConstr);
        }

        var sopCase = SopRecognizer.matchCase(term);
        if (sopCase != null) {
            return liftCase(sopCase);
        }

        // Priority 2: IfThenElse
        var ifMatch = IfThenElseRecognizer.match(term);
        if (ifMatch != null) {
            return new HirTerm.If(
                    liftTerm(ifMatch.condition()),
                    liftTerm(ifMatch.thenBranch()),
                    liftTerm(ifMatch.elseBranch()));
        }

        // Priority 3: Z-combinator (loops/recursion)
        var zMatch = LoopRecognizer.match(term);
        if (zMatch != null) {
            return liftZCombinator(zMatch);
        }

        // Priority 4: Data pattern matching (UnConstrData + tag dispatch)
        var dataMatch = DataMatchRecognizer.match(term);
        if (dataMatch != null) {
            return liftDataMatch(dataMatch);
        }

        // Priority 5: Let binding
        var letMatch = LetRecognizer.match(term);
        if (letMatch != null) {
            return liftLet(letMatch);
        }

        // Priority 6: Forced builtins with all args
        var fb = ForceCollapser.matchForcedBuiltin(term);
        if (fb != null) {
            return liftForcedBuiltin(fb);
        }

        // Priority 7: Direct term types
        return switch (term) {
            case Term.Var v -> new HirTerm.Var(v.name().name(), HirType.UNKNOWN);

            case Term.Const c -> liftConstant(c.value());

            case Term.Lam lam -> liftLambda(lam);

            case Term.Apply app -> liftApply(app);

            case Term.Force f -> {
                // Remaining Force not captured by recognizers — try to lift inner
                yield liftTerm(f.term());
            }

            case Term.Delay d -> {
                // Remaining Delay not captured by recognizers — lift inner
                yield liftTerm(d.term());
            }

            case Term.Builtin b -> new HirTerm.BuiltinCall(b.fun(), List.of());

            case Term.Error _ -> new HirTerm.Error();

            case Term.Constr c -> liftConstr(new SopRecognizer.ConstrResult((int) c.tag(), c.fields()));

            case Term.Case cs -> {
                var caseResult = SopRecognizer.matchCase(cs);
                yield caseResult != null ? liftCase(caseResult) : new HirTerm.RawUplc(term);
            }
        };
    }

    private HirTerm liftConstant(Constant value) {
        return switch (value) {
            case Constant.IntegerConst i -> new HirTerm.IntLiteral(i.value());
            case Constant.ByteStringConst bs -> new HirTerm.ByteStringLiteral(bs.value());
            case Constant.StringConst s -> new HirTerm.StringLiteral(s.value());
            case Constant.BoolConst b -> new HirTerm.BoolLiteral(b.value());
            case Constant.UnitConst _ -> new HirTerm.UnitLiteral();
            case Constant.DataConst d -> new HirTerm.DataLiteral(d.value());
            default -> new HirTerm.ConstValue(value);
        };
    }

    private HirTerm liftLambda(Term.Lam lam) {
        List<String> params = new ArrayList<>();
        Term body = lam;
        while (body instanceof Term.Lam l) {
            params.add(l.paramName());
            body = l.body();
        }
        return new HirTerm.Lambda(params, liftTerm(body));
    }

    private HirTerm liftApply(Term.Apply app) {
        // Check if this is a data encode/decode builtin
        if (app.function() instanceof Term.Builtin b) {
            return liftSimpleBuiltinApp(b.fun(), app.argument());
        }

        // Check for forced builtin partial application
        var fb = ForceCollapser.matchForcedBuiltinPartial(app);
        if (fb != null) {
            return liftForcedBuiltin(fb);
        }

        // Check for Trace pattern: Apply(Apply(Force(Builtin(Trace)), msg), body)
        if (app.function() instanceof Term.Apply innerApp) {
            var traceFb = ForceCollapser.matchForcedBuiltinPartial(innerApp);
            if (traceFb != null && traceFb.fun() == DefaultFun.Trace && traceFb.args().size() == 1) {
                return new HirTerm.Trace(
                        liftTerm(traceFb.args().getFirst()),
                        liftTerm(app.argument()));
            }
        }

        // Generic function application
        HirTerm fn = liftTerm(app.function());
        HirTerm arg = liftTerm(app.argument());

        // If function is a lambda, this is an inline application
        if (fn instanceof HirTerm.Lambda lambda && lambda.params().size() == 1) {
            return new HirTerm.Let(lambda.params().getFirst(), arg, lambda.body());
        }

        // If function is a builtin call, add the argument
        if (fn instanceof HirTerm.BuiltinCall bc) {
            var newArgs = new ArrayList<>(bc.args());
            newArgs.add(arg);
            return new HirTerm.BuiltinCall(bc.fun(), newArgs);
        }

        // Generic function call
        if (fn instanceof HirTerm.FunCall fc) {
            var newArgs = new ArrayList<>(fc.args());
            newArgs.add(arg);
            return new HirTerm.FunCall(fc.name(), newArgs);
        }

        if (fn instanceof HirTerm.Var v) {
            return new HirTerm.FunCall(v.name(), List.of(arg));
        }

        return new HirTerm.FunCall("_apply", List.of(fn, arg));
    }

    private HirTerm liftSimpleBuiltinApp(DefaultFun fun, Term arg) {
        // Data encode/decode builtins
        return switch (fun) {
            case IData, BData, ConstrData, MapData, ListData ->
                    new HirTerm.DataEncode(fun, liftTerm(arg));
            case UnIData, UnBData, UnConstrData, UnMapData, UnListData ->
                    new HirTerm.DataDecode(fun, liftTerm(arg));
            default -> new HirTerm.BuiltinCall(fun, List.of(liftTerm(arg)));
        };
    }

    private HirTerm liftForcedBuiltin(ForceCollapser.ForcedBuiltin fb) {
        List<HirTerm> args = fb.args().stream().map(this::liftTerm).toList();

        // Trace
        if (fb.fun() == DefaultFun.Trace && args.size() == 2) {
            return new HirTerm.Trace(args.get(0), args.get(1));
        }

        // Data encode/decode
        if (args.size() == 1) {
            return switch (fb.fun()) {
                case IData, BData, ConstrData, MapData, ListData ->
                        new HirTerm.DataEncode(fb.fun(), args.getFirst());
                case UnIData, UnBData, UnConstrData, UnMapData, UnListData ->
                        new HirTerm.DataDecode(fb.fun(), args.getFirst());
                case HeadList -> {
                    // Single HeadList = field 0
                    yield new HirTerm.BuiltinCall(fb.fun(), args);
                }
                default -> new HirTerm.BuiltinCall(fb.fun(), args);
            };
        }

        // Field access chains
        if (fb.fun() == DefaultFun.HeadList && args.size() == 1) {
            // Already handled above
        }

        return new HirTerm.BuiltinCall(fb.fun(), args);
    }

    private HirTerm liftLet(LetRecognizer.LetComponents let) {
        String name = let.name();
        HirTerm value = liftTerm(let.value());
        HirTerm body = liftTerm(let.body());
        return new HirTerm.Let(name, value, body);
    }

    private HirTerm liftZCombinator(LoopRecognizer.ZCombinatorResult z) {
        var kind = LoopRecognizer.classifyBody(z.recBody());
        HirTerm body = liftTerm(z.recBody());
        HirTerm continuation = liftTerm(z.continuation());

        return new HirTerm.LetRec(z.name(), body, continuation);
    }

    private HirTerm liftDataMatch(DataMatchRecognizer.DataMatchResult match) {
        HirTerm scrutinee = liftTerm(match.scrutinee());
        List<HirTerm.SwitchBranch> branches = new ArrayList<>();

        for (var branch : match.branches()) {
            HirTerm body = liftTerm(branch.body());
            // Extract field names from the branch body's Let bindings
            List<String> fieldNames = extractFieldNames(branch.body());
            branches.add(new HirTerm.SwitchBranch(
                    branch.tag(),
                    "Case" + branch.tag(),
                    fieldNames,
                    body));
        }

        return new HirTerm.Switch(scrutinee, "", branches);
    }

    private HirTerm liftConstr(SopRecognizer.ConstrResult constr) {
        List<HirTerm> fields = constr.fields().stream().map(this::liftTerm).toList();
        return new HirTerm.Constructor("Constr" + constr.tag(), constr.tag(), fields);
    }

    private HirTerm liftCase(SopRecognizer.CaseResult caseResult) {
        HirTerm scrutinee = liftTerm(caseResult.scrutinee());
        List<HirTerm.SwitchBranch> branches = new ArrayList<>();

        for (var branch : caseResult.branches()) {
            HirTerm body = liftTerm(branch.body());
            branches.add(new HirTerm.SwitchBranch(
                    branch.tag(),
                    "Case" + branch.tag(),
                    branch.fieldNames(),
                    body));
        }

        return new HirTerm.Switch(scrutinee, "", branches);
    }

    /**
     * Try to extract field variable names from Let bindings in a branch body
     * that use HeadList/TailList patterns.
     */
    private List<String> extractFieldNames(Term branchBody) {
        List<String> names = new ArrayList<>();
        Term current = branchBody;
        while (current instanceof Term.Apply app && app.function() instanceof Term.Lam lam) {
            // This is a Let binding
            var fa = FieldAccessRecognizer.match(app.argument());
            if (fa != null) {
                names.add(lam.paramName());
            }
            current = lam.body();
        }
        return names;
    }

    private String freshVar(String prefix) {
        return prefix + "_" + (varCounter++);
    }
}
