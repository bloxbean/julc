package org.julclang.decompiler.lift;

import org.julclang.core.NamedDeBruijn;
import org.julclang.core.Term;

import java.util.ArrayList;
import java.util.List;

/** Names-only copy for matches: FLAT indices, structure and evaluation stay unchanged. */
final class ScopedNames {
    private final List<String> scope = new ArrayList<>();
    private int next;

    static Term resolve(Term term) { return new ScopedNames().visit(term); }

    private Term visit(Term term) {
        return switch (term) {
            case Term.Var v -> {
                int index = v.name().index();
                String name = index > 0 && index <= scope.size() ? scope.get(scope.size() - index)
                        : "_free" + Math.max(0, index - scope.size());
                yield Term.var(new NamedDeBruijn(name, index));
            }
            case Term.Lam l -> {
                String name = "_uplc" + next++;
                scope.add(name);
                var body = visit(l.body());
                scope.removeLast();
                yield Term.lam(name, body);
            }
            case Term.Apply a -> Term.apply(visit(a.function()), visit(a.argument()));
            case Term.Force f -> Term.force(visit(f.term()));
            case Term.Delay d -> Term.delay(visit(d.term()));
            case Term.Case c -> new Term.Case(visit(c.scrutinee()), c.branches().stream().map(this::visit).toList());
            case Term.Constr c -> new Term.Constr(c.tag(), c.fields().stream().map(this::visit).toList());
            default -> term;
        };
    }
}
