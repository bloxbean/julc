package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirTerm;

import java.util.*;

/** Checks lexical closure, including recursive and pattern scopes. Not a full PIR type verifier. */
public final class PirClosure {
    private PirClosure() {}

    public static void check(PirTerm term) {
        visit(term, Set.of());
    }

    public static Set<String> freeVariables(PirTerm term) {
        var found = new LinkedHashSet<String>();
        collect(term, Set.of(), found);
        return found;
    }

    private static void visit(PirTerm term, Set<String> scope) {
        var free = new LinkedHashSet<String>();
        collect(term, scope, free);
        if (!free.isEmpty()) throw new IllegalArgumentException("Unbound PIR symbols: " + free);
    }

    private static Set<String> plus(Set<String> scope, String... names) {
        var s = new HashSet<>(scope);
        Collections.addAll(s, names);
        return s;
    }

    private static void collect(PirTerm t, Set<String> s, Set<String> free) {
        switch (t) {
            case PirTerm.Var v -> {
                if (!s.contains(v.name())) free.add(v.name());
            }
            case PirTerm.Lam l -> collect(l.body(), plus(s, l.param()), free);
            case PirTerm.Let l -> {
                collect(l.value(), s, free);
                collect(l.body(), plus(s, l.name()), free);
            }
            case PirTerm.LetRec l -> {
                var ns = new HashSet<>(s);
                l.bindings().forEach(b -> ns.add(b.name()));
                l.bindings().forEach(b -> collect(b.value(), ns, free));
                collect(l.body(), ns, free);
            }
            case PirTerm.App a -> {
                collect(a.function(), s, free);
                collect(a.argument(), s, free);
            }
            case PirTerm.IfThenElse i -> {
                collect(i.cond(), s, free);
                collect(i.thenBranch(), s, free);
                collect(i.elseBranch(), s, free);
            }
            case PirTerm.DataConstr c -> c.fields().forEach(f -> collect(f, s, free));
            case PirTerm.DataMatch m -> {
                collect(m.scrutinee(), s, free);
                for (var b : m.branches()) {
                    var ns = new HashSet<>(s);
                    ns.addAll(b.bindings());
                    if (b.patternVar() != null) ns.add(b.patternVar());
                    collect(b.body(), ns, free);
                }
            }
            case PirTerm.ListMatch m -> {
                collect(m.scrutinee(), s, free);
                collect(m.nilBranch(), s, free);
                collect(m.consBranch(), plus(s, m.headName(), m.tailName()), free);
            }
            case PirTerm.PairMatch m -> {
                collect(m.scrutinee(), s, free);
                collect(m.body(), plus(s, m.firstName(), m.secondName()), free);
            }
            case PirTerm.IntegerCase c -> {
                collect(c.scrutinee(), s, free);
                c.branches().forEach(b -> collect(b, s, free));
            }
            case PirTerm.Trace t1 -> {
                collect(t1.message(), s, free);
                collect(t1.body(), s, free);
            }
            case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> {}
        }
    }
}
