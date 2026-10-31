package org.julclang.difftest;

import org.julclang.compiler.pir.PirFormatter;
import org.julclang.compiler.pir.PirTerm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A PIR term with its bound names replaced by their binding depth, so two terms have one text exactly when they are
 * alpha-equivalent under PIR's name scoping (ADR-068 I1b): a free name stays as it is, and types, constants, builtins
 * and constructor names are compared as they stand. It is exhaustive over {@link PirTerm} by the compiler, so a new
 * term kind cannot pass as equal unnoticed.
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
            case PirTerm.Const c -> text.append("(const ").append(PirFormatter.format(c)).append(')');
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
                    var names = new java.util.ArrayList<>(branch.bindings());
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

    /** Runs {@code body} with {@code names} bound in order, each at the next depth, and restores what they shadowed. */
    private void within(List<String> names, Runnable body) {
        var shadowed = new HashMap<String, Integer>();
        var absent = new java.util.ArrayList<String>();
        for (String name : names) {
            var previous = depths.put(name, depth++);
            if (previous == null) absent.add(name);
            else shadowed.putIfAbsent(name, previous);
        }
        text.append("[").append(names.size()).append("]");
        body.run();
        depth -= names.size();
        absent.forEach(depths::remove);
        depths.putAll(shadowed);
    }
}
