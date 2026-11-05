package org.julclang.difftest;

import org.julclang.compiler.pir.PirTerm;
import org.julclang.core.Constant;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A PIR term with its bound names replaced by their binding depth, so two terms have one text exactly when they are
 * alpha-equivalent under PIR's name scoping (ADR-068 I1b): a free name stays as it is, and types, constants, builtins
 * and constructor names are compared as they stand. A constant is spelled out here, with its universe and every value in
 * it; a printer for people elides them. It is exhaustive over {@link PirTerm}, {@link Constant} and {@link PlutusData} by
 * the compiler, so a new kind cannot pass as equal unnoticed.
 */
final class PirCanonical {

    private final StringBuilder text = new StringBuilder();
    private final Map<String, Integer> depths = new HashMap<>();
    private int depth;

    private PirCanonical() {
    }

    static String of(PirTerm term) {
        var canonical = new PirCanonical();
        canonical.walk(term);
        return canonical.text.toString();
    }

    private void walk(PirTerm term) {
        switch (term) {
            case PirTerm.Var v -> {
                var bound = depths.get(v.name());
                text.append("(var ").append(bound == null ? "free:" + v.name() : "#" + bound).append(' ').append(v.type()).append(')');
            }
            case PirTerm.Let let -> {
                text.append("(let ");
                walk(let.value());
                text.append(" in ");
                within(List.of(let.name()), () -> walk(let.body()));
                text.append(')');
            }
            case PirTerm.LetRec rec -> {
                text.append("(letrec ");
                within(rec.bindings().stream().map(PirTerm.Binding::name).toList(), () -> {
                    for (var binding : rec.bindings()) {
                        walk(binding.value());
                        text.append(" and ");
                    }
                    walk(rec.body());
                });
                text.append(')');
            }
            case PirTerm.Lam lam -> {
                text.append("(lam ").append(lam.paramType()).append(' ');
                within(List.of(lam.param()), () -> walk(lam.body()));
                text.append(')');
            }
            case PirTerm.App app -> all("app", List.of(app.function(), app.argument()));
            case PirTerm.Const c -> text.append("(const ").append(constant(c.value())).append(')');
            case PirTerm.Builtin b -> text.append("(builtin ").append(b.fun()).append(')');
            case PirTerm.IfThenElse branch -> all("if", List.of(branch.cond(), branch.thenBranch(), branch.elseBranch()));
            case PirTerm.IntegerCase select -> {
                text.append("(case ");
                walk(select.scrutinee());
                all("branches", select.branches());
                text.append(')');
            }
            case PirTerm.PairMatch pair -> {
                text.append("(pair ").append(pair.pairType()).append(' ');
                walk(pair.scrutinee());
                within(List.of(pair.firstName(), pair.secondName()), () -> walk(pair.body()));
                text.append(')');
            }
            case PirTerm.ListMatch list -> {
                text.append("(list ");
                walk(list.scrutinee());
                walk(list.nilBranch());
                within(List.of(list.headName(), list.tailName()), () -> walk(list.consBranch()));
                text.append(')');
            }
            case PirTerm.DataConstr constr -> {
                text.append("(constr ").append(constr.tag()).append(' ').append(constr.dataType()).append(' ');
                all("fields", constr.fields());
                text.append(')');
            }
            case PirTerm.DataMatch match -> {
                text.append("(match ");
                walk(match.scrutinee());
                for (var branch : match.branches()) {
                    text.append(" (branch ").append(branch.constructorName()).append(' ').append(branch.bindingTypes())
                            .append(' ').append(branch.patternVar() == null ? "-" : "pattern").append(' ');
                    var names = new ArrayList<>(branch.bindings());
                    if (branch.patternVar() != null) names.addFirst(branch.patternVar());
                    within(names, () -> walk(branch.body()));
                    text.append(')');
                }
                text.append(')');
            }
            case PirTerm.Error error -> text.append("(error ").append(error.type()).append(')');
            case PirTerm.Trace trace -> all("trace", List.of(trace.message(), trace.body()));
        }
    }

    private void all(String head, List<PirTerm> terms) {
        text.append('(').append(head);
        for (var term : terms) {
            text.append(' ');
            walk(term);
        }
        text.append(')');
    }

    /** Runs {@code body} with {@code names} bound in order, each at the next depth, and restores what each one shadowed. */
    private void within(List<String> names, Runnable body) {
        var shadowed = new ArrayList<Integer>();
        for (String name : names) shadowed.add(depths.put(name, depth++));
        text.append("[").append(names.size()).append("]");
        body.run();
        depth -= names.size();
        // Last first: a name bound twice here goes back through its own first binding to what was there before.
        for (int i = names.size() - 1; i >= 0; i--) {
            if (shadowed.get(i) == null) depths.remove(names.get(i));
            else depths.put(names.get(i), shadowed.get(i));
        }
    }

    private static String constant(Constant constant) {
        return switch (constant) {
            case Constant.IntegerConst c -> "integer " + c.value();
            case Constant.ByteStringConst c -> "bytes " + hex(c.value());
            case Constant.StringConst c -> "string " + quoted(c.value());
            case Constant.UnitConst c -> "unit";
            case Constant.BoolConst c -> "bool " + c.value();
            case Constant.DataConst c -> "data " + data(c.value());
            case Constant.ListConst c -> "list " + universe(c.elemType()) + " " + constants(c.values());
            case Constant.ArrayConst c -> "array " + universe(c.elemType()) + " " + constants(c.values());
            case Constant.PairConst c -> "pair (" + constant(c.first()) + ", " + constant(c.second()) + ")";
            case Constant.Bls12_381_G1Element c -> "g1 " + hex(c.value());
            case Constant.Bls12_381_G2Element c -> "g2 " + hex(c.value());
            case Constant.Bls12_381_MlResult c -> "ml " + hex(c.value());
            case Constant.ValueConst c -> c.entries().stream().map(PirCanonical::valueEntry)
                    .collect(Collectors.joining(", ", "value [", "]"));
        };
    }

    private static String constants(List<Constant> values) {
        return values.stream().map(PirCanonical::constant).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String valueEntry(Constant.ValueConst.ValueEntry entry) {
        return hex(entry.policyId()) + " " + entry.tokens().stream().map(token -> hex(token.tokenName()) + "=" + token.quantity())
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private static String universe(DefaultUni universe) {
        return switch (universe) {
            case DefaultUni.Integer u -> "integer";
            case DefaultUni.ByteString u -> "bytes";
            case DefaultUni.String u -> "string";
            case DefaultUni.Unit u -> "unit";
            case DefaultUni.Bool u -> "bool";
            case DefaultUni.Data u -> "data";
            case DefaultUni.ProtoList u -> "list";
            case DefaultUni.ProtoPair u -> "pair";
            case DefaultUni.ProtoArray u -> "array";
            case DefaultUni.ProtoValue u -> "value";
            case DefaultUni.Bls12_381_G1_Element u -> "g1";
            case DefaultUni.Bls12_381_G2_Element u -> "g2";
            case DefaultUni.Bls12_381_MlResult u -> "ml";
            case DefaultUni.Apply u -> "(" + universe(u.f()) + " " + universe(u.arg()) + ")";
        };
    }

    private static String data(PlutusData data) {
        return switch (data) {
            case PlutusData.ConstrData d -> "(constr " + d.constructorTag() + " " + datas(d.fields()) + ")";
            case PlutusData.ListData d -> "(list " + datas(d.items()) + ")";
            case PlutusData.MapData d -> d.entries().stream().map(e -> data(e.key()) + " -> " + data(e.value()))
                    .collect(Collectors.joining(", ", "(map [", "])"));
            case PlutusData.IntData d -> "(int " + d.value() + ")";
            case PlutusData.BytesData d -> "(bytes " + hex(d.value()) + ")";
        };
    }

    private static String datas(List<PlutusData> items) {
        return items.stream().map(PirCanonical::data).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** A string in one line, whatever it holds, so that two strings have two texts. */
    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
