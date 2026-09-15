package org.julclang.core.text;

import org.julclang.core.NamedDeBruijn;
import org.julclang.core.Program;
import org.julclang.core.Term;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gives every lambda binder of a program a unique, printable name and names each variable after its binder.
 * <p>
 * Programs decoded from FLAT carry no names (every binder is {@code i0}, every variable {@code i<index>}), so their
 * text is ambiguous and cannot be parsed back. {@link #uniquify(Program)} keeps every De Bruijn index unchanged, so
 * the result evaluates and encodes exactly like the input; only names change. Meaningful names (for example from the
 * JuLC compiler) are kept and de-duplicated with a numeric suffix; generated names become {@code i_1, i_2, ...}.
 * Variables that refer outside the program become {@code free_<index>}.
 * <p>
 * The traversal is iterative, so arbitrarily deep terms do not overflow the call stack. Every node of the result is
 * a fresh object, so the result can be used as an identity key per occurrence.
 */
public final class UplcNames {

    private static final Pattern GENERATED = Pattern.compile("i\\d*");
    private static final Set<String> KEYWORDS = Set.of(
            "lam", "con", "builtin", "force", "delay", "error", "constr", "case", "program");

    private UplcNames() {}

    /** Returns a program with unique binder names and the same De Bruijn structure. */
    public static Program uniquify(Program program) {
        return new Program(program.major(), program.minor(), program.patch(), uniquify(program.term()));
    }

    /** Returns a term with unique binder names and the same De Bruijn structure. */
    public static Term uniquify(Term root) {
        var names = new NameAllocator();
        var scope = new ArrayList<String>();
        Deque<Object> work = new ArrayDeque<>();
        Deque<Term> results = new ArrayDeque<>();
        work.push(root);

        while (!work.isEmpty()) {
            Object item = work.pop();
            if (item instanceof Exit exit) {
                results.push(rebuild(exit, results, scope));
                continue;
            }
            Term term = (Term) item;
            switch (term) {
                case Term.Var v -> {
                    int index = v.name().index();
                    String name = index >= 1 && index <= scope.size()
                            ? scope.get(scope.size() - index)
                            : "free_" + index;
                    results.push(new Term.Var(new NamedDeBruijn(name, index)));
                }
                case Term.Lam l -> {
                    String name = names.fresh(l.paramName());
                    work.push(new Exit(term, name, 1));
                    scope.add(name);
                    work.push(l.body());
                }
                case Term.Apply a -> {
                    work.push(new Exit(term, null, 2));
                    work.push(a.argument());
                    work.push(a.function());
                }
                case Term.Force f -> {
                    work.push(new Exit(term, null, 1));
                    work.push(f.term());
                }
                case Term.Delay d -> {
                    work.push(new Exit(term, null, 1));
                    work.push(d.term());
                }
                case Term.Const c -> results.push(new Term.Const(c.value()));
                case Term.Builtin b -> results.push(new Term.Builtin(b.fun()));
                case Term.Error ignored -> results.push(new Term.Error());
                case Term.Constr c -> {
                    work.push(new Exit(term, null, c.fields().size()));
                    pushReversed(work, c.fields());
                }
                case Term.Case cs -> {
                    work.push(new Exit(term, null, cs.branches().size() + 1));
                    pushReversed(work, cs.branches());
                    work.push(cs.scrutinee());
                }
            }
        }
        return results.pop();
    }

    private record Exit(Term original, String binderName, int arity) {}

    private static Term rebuild(Exit exit, Deque<Term> results, List<String> scope) {
        Term[] children = new Term[exit.arity()];
        for (int i = exit.arity() - 1; i >= 0; i--) {
            children[i] = results.pop();
        }
        return switch (exit.original()) {
            case Term.Lam ignored -> {
                scope.removeLast();
                yield new Term.Lam(exit.binderName(), children[0]);
            }
            case Term.Apply ignored -> new Term.Apply(children[0], children[1]);
            case Term.Force ignored -> new Term.Force(children[0]);
            case Term.Delay ignored -> new Term.Delay(children[0]);
            case Term.Constr c -> new Term.Constr(c.tag(), List.of(children));
            case Term.Case ignored -> new Term.Case(children[0], List.of(children).subList(1, children.length));
            default -> throw new IllegalStateException("Unexpected term with children: " + exit.original());
        };
    }

    private static void pushReversed(Deque<Object> work, List<Term> terms) {
        for (int i = terms.size() - 1; i >= 0; i--) {
            work.push(terms.get(i));
        }
    }

    /** Allocates program-wide unique names that the UPLC text parser accepts. */
    private static final class NameAllocator {
        private final Map<String, Integer> counters = new HashMap<>();
        private final Set<String> used = new HashSet<>();

        String fresh(String requested) {
            String base = sanitize(requested);
            boolean generated = base.isEmpty() || GENERATED.matcher(base).matches();
            if (generated) {
                base = "i";
            }
            String candidate;
            do {
                int n = counters.merge(base, 1, Integer::sum);
                candidate = (n == 1 && !generated) ? base : base + "_" + n;
            } while (!used.add(candidate));
            return candidate;
        }

        private static String sanitize(String name) {
            var sb = new StringBuilder(name.length());
            for (int i = 0; i < name.length(); i++) {
                char ch = name.charAt(i);
                sb.append(Character.isLetterOrDigit(ch) || ch == '_' || ch == '\'' ? ch : '_');
            }
            String result = sb.toString();
            if (!result.isEmpty() && !Character.isLetter(result.charAt(0)) && result.charAt(0) != '_') {
                result = "_" + result;
            }
            return KEYWORDS.contains(result) ? result + "_" : result;
        }
    }
}
