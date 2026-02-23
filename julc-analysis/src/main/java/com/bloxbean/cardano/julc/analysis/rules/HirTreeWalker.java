package com.bloxbean.cardano.julc.analysis.rules;

import com.bloxbean.cardano.julc.decompiler.hir.HirTerm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Utility for traversing {@link HirTerm} trees.
 */
public final class HirTreeWalker {

    private HirTreeWalker() {}

    /**
     * Walk all nodes in the HIR tree, invoking the visitor on each.
     */
    public static void walk(HirTerm term, Consumer<HirTerm> visitor) {
        if (term == null) return;
        visitor.accept(term);
        switch (term) {
            case HirTerm.Let let -> {
                walk(let.value(), visitor);
                walk(let.body(), visitor);
            }
            case HirTerm.LetRec lr -> {
                walk(lr.value(), visitor);
                walk(lr.body(), visitor);
            }
            case HirTerm.Lambda lam -> walk(lam.body(), visitor);
            case HirTerm.FunCall fc -> fc.args().forEach(a -> walk(a, visitor));
            case HirTerm.BuiltinCall bc -> bc.args().forEach(a -> walk(a, visitor));
            case HirTerm.MethodCall mc -> {
                walk(mc.receiver(), visitor);
                mc.args().forEach(a -> walk(a, visitor));
            }
            case HirTerm.If iff -> {
                walk(iff.condition(), visitor);
                walk(iff.thenBranch(), visitor);
                walk(iff.elseBranch(), visitor);
            }
            case HirTerm.Switch sw -> {
                walk(sw.scrutinee(), visitor);
                for (var branch : sw.branches()) {
                    walk(branch.body(), visitor);
                }
            }
            case HirTerm.ForEach fe -> {
                walk(fe.list(), visitor);
                walk(fe.init(), visitor);
                walk(fe.body(), visitor);
            }
            case HirTerm.While wh -> {
                walk(wh.condition(), visitor);
                wh.accInits().forEach(ai -> walk(ai, visitor));
                walk(wh.body(), visitor);
            }
            case HirTerm.FieldAccess fa -> walk(fa.record(), visitor);
            case HirTerm.Constructor c -> c.fields().forEach(f -> walk(f, visitor));
            case HirTerm.DataEncode de -> walk(de.operand(), visitor);
            case HirTerm.DataDecode dd -> walk(dd.operand(), visitor);
            case HirTerm.Trace tr -> {
                walk(tr.message(), visitor);
                walk(tr.body(), visitor);
            }
            case HirTerm.ListLiteral ll -> ll.elements().forEach(e -> walk(e, visitor));
            case HirTerm.Var _, HirTerm.IntLiteral _, HirTerm.ByteStringLiteral _,
                 HirTerm.StringLiteral _, HirTerm.BoolLiteral _, HirTerm.UnitLiteral _,
                 HirTerm.DataLiteral _, HirTerm.Error _, HirTerm.RawUplc _,
                 HirTerm.ConstValue _ -> { /* leaf nodes */ }
        };
    }

    /**
     * Collect all nodes matching a predicate.
     */
    public static List<HirTerm> collect(HirTerm term, Predicate<HirTerm> predicate) {
        var result = new ArrayList<HirTerm>();
        walk(term, node -> {
            if (predicate.test(node)) result.add(node);
        });
        return result;
    }

    /**
     * Check if any node matches a predicate.
     */
    public static boolean anyMatch(HirTerm term, Predicate<HirTerm> predicate) {
        boolean[] found = {false};
        walk(term, node -> {
            if (!found[0] && predicate.test(node)) found[0] = true;
        });
        return found[0];
    }

    /**
     * Count nodes matching a predicate.
     */
    public static int count(HirTerm term, Predicate<HirTerm> predicate) {
        int[] count = {0};
        walk(term, node -> {
            if (predicate.test(node)) count[0]++;
        });
        return count[0];
    }
}
