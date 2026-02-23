package com.bloxbean.cardano.julc.decompiler.naming;

import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;
import com.bloxbean.cardano.julc.decompiler.hir.HirType;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;

import java.util.*;

/**
 * Assigns meaningful names to variables in decompiled HIR.
 * <p>
 * Uses heuristics based on:
 * <ul>
 *   <li>Inferred type (ScriptContext → ctx, TxInfo → txInfo)</li>
 *   <li>Usage context (list iteration → item, accumulator → acc)</li>
 *   <li>De Bruijn index position (entrypoint params)</li>
 * </ul>
 */
public final class NameAssigner {

    private NameAssigner() {}

    private static final Map<String, String> TYPE_NAMES = Map.ofEntries(
            Map.entry("ScriptContext", "ctx"),
            Map.entry("TxInfo", "txInfo"),
            Map.entry("TxOut", "txOut"),
            Map.entry("TxInInfo", "txInInfo"),
            Map.entry("Address", "addr"),
            Map.entry("Value", "value"),
            Map.entry("Interval", "validRange"),
            Map.entry("IntervalBound", "bound"),
            Map.entry("Credential", "cred"),
            Map.entry("OutputDatum", "datum"),
            Map.entry("ScriptInfo", "scriptInfo"),
            Map.entry("TxOutRef", "txOutRef")
    );

    private static final Map<String, String> HTYPE_NAMES = Map.of(
            "BigInteger", "n",
            "byte[]", "bytes",
            "String", "str",
            "boolean", "flag",
            "PlutusData", "d"
    );

    /**
     * Assign meaningful names to all variables in the HIR tree.
     * Returns a new HIR tree with improved names.
     */
    public static HirTerm assignNames(HirTerm hir, ScriptAnalyzer.ScriptStats stats) {
        var assigner = new NameAssignerState();
        return assigner.rename(hir);
    }

    private static class NameAssignerState {
        private final Map<String, String> renames = new HashMap<>();
        private final Map<String, Integer> counters = new HashMap<>();

        HirTerm rename(HirTerm term) {
            return switch (term) {
                case HirTerm.Var v -> {
                    String newName = renames.getOrDefault(v.name(), v.name());
                    yield new HirTerm.Var(newName, v.type());
                }

                case HirTerm.Let let -> {
                    HirTerm value = rename(let.value());
                    String newName = chooseName(let.name(), value);
                    renames.put(let.name(), newName);
                    HirTerm body = rename(let.body());
                    yield new HirTerm.Let(newName, value, body);
                }

                case HirTerm.LetRec letRec -> {
                    String newName = makeUnique("loop");
                    renames.put(letRec.name(), newName);
                    HirTerm value = rename(letRec.value());
                    HirTerm body = rename(letRec.body());
                    yield new HirTerm.LetRec(newName, value, body);
                }

                case HirTerm.Lambda lam -> {
                    List<String> newParams = new ArrayList<>();
                    for (int i = 0; i < lam.params().size(); i++) {
                        String oldName = lam.params().get(i);
                        String newName;
                        if (i == 0 && lam.params().size() == 1) {
                            newName = makeUnique("ctx");
                        } else {
                            newName = makeUnique("arg" + i);
                        }
                        renames.put(oldName, newName);
                        newParams.add(newName);
                    }
                    HirTerm body = rename(lam.body());
                    yield new HirTerm.Lambda(newParams, body);
                }

                case HirTerm.If iff -> new HirTerm.If(
                        rename(iff.condition()), rename(iff.thenBranch()), rename(iff.elseBranch()));

                case HirTerm.Switch sw -> {
                    HirTerm scrutinee = rename(sw.scrutinee());
                    List<HirTerm.SwitchBranch> branches = sw.branches().stream()
                            .map(b -> {
                                List<String> newNames = b.fieldNames().stream()
                                        .map(n -> {
                                            String nn = makeUnique(improveParamName(n));
                                            renames.put(n, nn);
                                            return nn;
                                        }).toList();
                                return new HirTerm.SwitchBranch(b.tag(), b.constructorName(),
                                        newNames, rename(b.body()));
                            }).toList();
                    yield new HirTerm.Switch(scrutinee, sw.typeName(), branches);
                }

                case HirTerm.BuiltinCall bc -> {
                    List<HirTerm> args = bc.args().stream().map(this::rename).toList();
                    yield new HirTerm.BuiltinCall(bc.fun(), args);
                }

                case HirTerm.FunCall fc -> {
                    String newName = renames.getOrDefault(fc.name(), fc.name());
                    List<HirTerm> args = fc.args().stream().map(this::rename).toList();
                    yield new HirTerm.FunCall(newName, args);
                }

                case HirTerm.FieldAccess fa -> new HirTerm.FieldAccess(
                        rename(fa.record()), fa.fieldName(), fa.fieldIndex(), fa.typeName());

                case HirTerm.Constructor c -> {
                    List<HirTerm> fields = c.fields().stream().map(this::rename).toList();
                    yield new HirTerm.Constructor(c.typeName(), c.tag(), fields);
                }

                case HirTerm.MethodCall mc -> {
                    HirTerm receiver = rename(mc.receiver());
                    List<HirTerm> args = mc.args().stream().map(this::rename).toList();
                    yield new HirTerm.MethodCall(receiver, mc.methodName(), args);
                }

                case HirTerm.DataEncode de -> new HirTerm.DataEncode(de.encoder(), rename(de.operand()));
                case HirTerm.DataDecode dd -> new HirTerm.DataDecode(dd.decoder(), rename(dd.operand()));
                case HirTerm.Trace tr -> new HirTerm.Trace(rename(tr.message()), rename(tr.body()));

                case HirTerm.ForEach fe -> new HirTerm.ForEach(
                        makeUnique("item"), rename(fe.list()),
                        makeUnique("acc"), rename(fe.init()), rename(fe.body()));

                case HirTerm.While w -> {
                    HirTerm cond = rename(w.condition());
                    List<String> newAccVars = w.accVars().stream()
                            .map(v -> makeUnique("acc")).toList();
                    List<HirTerm> newInits = w.accInits().stream().map(this::rename).toList();
                    HirTerm body = rename(w.body());
                    yield new HirTerm.While(cond, newAccVars, newInits, body);
                }

                case HirTerm.ListLiteral ll -> {
                    List<HirTerm> elems = ll.elements().stream().map(this::rename).toList();
                    yield new HirTerm.ListLiteral(elems);
                }

                // Pass-through
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

        private String chooseName(String original, HirTerm value) {
            // If the value provides type hints, use them
            if (value instanceof HirTerm.FieldAccess fa && !fa.fieldName().isEmpty()) {
                return makeUnique(fa.fieldName());
            }
            if (value instanceof HirTerm.DataDecode dd) {
                return switch (dd.decoder()) {
                    case UnIData -> makeUnique("n");
                    case UnBData -> makeUnique("bytes");
                    case UnConstrData -> makeUnique("constrPair");
                    case UnMapData -> makeUnique("map");
                    case UnListData -> makeUnique("list");
                    default -> makeUnique(improveParamName(original));
                };
            }
            return makeUnique(improveParamName(original));
        }

        private String improveParamName(String name) {
            if (name == null || name.isEmpty()) return "v";
            // De Bruijn names like "i0", "i1" are meaningless
            if (name.matches("i\\d+")) return "v";
            return name;
        }

        private String makeUnique(String base) {
            int count = counters.getOrDefault(base, 0);
            counters.put(base, count + 1);
            return count == 0 ? base : base + count;
        }
    }
}
