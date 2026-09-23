package org.julclang.core.text;

import org.julclang.core.Program;
import org.julclang.core.Term;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats a UPLC program as indented text and records where every term appears in that text.
 * <p>
 * A term is printed on one line when it fits in the remaining width; otherwise its children go on separate,
 * indented lines. Application chains are flattened ({@code [f a b]}), which the {@link UplcParser} reads back as
 * nested applications. Variable names are printed as stored, so run {@link UplcNames#uniquify(Program)} first for
 * scripts decoded from FLAT. With {@link Options#maxConstantChars()} left at 0 the text parses back to the same
 * program.
 * <p>
 * Indentation stops growing at half the line width, so deeply nested scripts stay readable and the text size stays
 * proportional to the number of lines.
 * <p>
 * Every term occurrence gets an id in print order and a {@link Span}. Terms that are part of a flattened
 * application chain start at the chain's opening bracket and end after their own argument. The layout pass is
 * iterative, so deep terms do not overflow the call stack.
 */
public final class UplcPrettyPrinter {

    /**
     * @param width            preferred maximum line width
     * @param indent           indentation step for broken terms
     * @param maxConstantChars shorten constants longer than this (0 = never); shortened text does not parse back
     */
    public record Options(int width, int indent, int maxConstantChars) {
        public static final Options DEFAULT = new Options(100, 2, 0);

        public Options {
            if (width < 20) throw new IllegalArgumentException("width must be at least 20: " + width);
            if (indent < 1) throw new IllegalArgumentException("indent must be positive: " + indent);
            if (maxConstantChars < 0) throw new IllegalArgumentException("maxConstantChars must be >= 0");
        }
    }

    /** Position of a term in the printed text: 1-based lines and columns, end column exclusive. */
    public record Span(int startLine, int startColumn, int endLine, int endColumn) {}

    /**
     * Printed program with the span of every term occurrence.
     *
     * @param text  the formatted program
     * @param terms term occurrences in print order; the index is the term id
     * @param spans span of each term, indexed by term id
     */
    public record PrettyUplc(String text, List<Term> terms, List<Span> spans, Map<Term, Integer> ids) {
        public PrettyUplc {
            terms = Collections.unmodifiableList(terms);
            spans = Collections.unmodifiableList(spans);
            ids = Collections.unmodifiableMap(ids);
        }

        /** Id of a term occurrence of the printed program, or -1 if it is not part of it. */
        public int idOf(Term term) {
            Integer id = ids.get(term);
            return id == null ? -1 : id;
        }
    }

    private UplcPrettyPrinter() {}

    /** Formats a program with {@link Options#DEFAULT}. */
    public static PrettyUplc print(Program program) {
        return print(program, Options.DEFAULT);
    }

    /** Formats a program. */
    public static PrettyUplc print(Program program, Options options) {
        return new Layout(options, program.term()).run(program.versionString());
    }

    private static final class Layout {
        private final Options options;
        private final Term root;
        private final Map<Term, Integer> flatWidths = new IdentityHashMap<>();
        private final Map<Term, String> atoms = new IdentityHashMap<>();
        private final StringBuilder out = new StringBuilder();
        private final List<Term> terms = new ArrayList<>();
        private final List<int[]> starts = new ArrayList<>();
        private final List<Span> spans = new ArrayList<>();
        private final Map<Term, Integer> ids = new IdentityHashMap<>();
        private int line = 1;
        private int column = 1;

        Layout(Options options, Term root) {
            this.options = options;
            this.root = root;
        }

        PrettyUplc run(String version) {
            measure();
            String header = "(program " + version;
            write(header);
            if (column + 1 + flatWidths.get(root) + 1 <= options.width()) {
                write(" ");
                emit(root, options.indent(), true);
            } else {
                newline(options.indent());
                emit(root, options.indent(), false);
            }
            write(")");
            return new PrettyUplc(out.toString(), terms, spans, ids);
        }

        // ---- measuring: flat width of every term, capped so the pass stays linear ----

        private void measure() {
            int cap = options.width() + 1;
            Deque<Object> work = new ArrayDeque<>();
            work.push(root);
            while (!work.isEmpty()) {
                Object item = work.pop();
                if (item instanceof Measured m) {
                    flatWidths.put(m.term(), Math.min(cap, flatWidth(m.term())));
                    continue;
                }
                Term term = (Term) item;
                work.push(new Measured(term));
                for (Term child : children(term)) {
                    work.push(child);
                }
            }
        }

        private record Measured(Term term) {}

        private int flatWidth(Term term) {
            return switch (term) {
                case Term.Var v -> v.name().name().length();
                case Term.Lam l -> 6 + l.paramName().length() + width(l.body());
                case Term.Apply a -> 2 + chainInnerWidth(a.function()) + 1 + width(a.argument());
                case Term.Force f -> 8 + width(f.term());
                case Term.Delay d -> 8 + width(d.term());
                case Term.Const c -> atom(c).length();
                case Term.Builtin b -> 10 + UplcPrinter.builtinName(b.fun()).length();
                case Term.Error ignored -> 7;
                case Term.Constr c -> 8 + Long.toUnsignedString(c.tag()).length() + sumWidths(c.fields());
                case Term.Case cs -> 6 + width(cs.scrutinee()) + sumWidths(cs.branches());
            };
        }

        private int width(Term term) {
            return flatWidths.get(term);
        }

        /** Width of an application used as the head of a chain, printed without its own brackets. */
        private int chainInnerWidth(Term function) {
            return function instanceof Term.Apply ? width(function) - 2 : width(function);
        }

        private int sumWidths(List<Term> terms) {
            int sum = 0;
            for (Term t : terms) sum += 1 + width(t);
            return sum;
        }

        private String atom(Term.Const c) {
            return atoms.computeIfAbsent(c, k -> {
                var sb = new StringBuilder("(con ");
                UplcPrinter.printConstant(c.value(), sb);
                sb.append(')');
                int max = options.maxConstantChars();
                if (max > 0 && sb.length() > max) {
                    return sb.substring(0, Math.max(6, max - 2)) + "…)";
                }
                return sb.toString();
            });
        }

        private static List<Term> children(Term term) {
            return switch (term) {
                case Term.Lam l -> List.of(l.body());
                case Term.Apply a -> List.of(a.function(), a.argument());
                case Term.Force f -> List.of(f.term());
                case Term.Delay d -> List.of(d.term());
                case Term.Constr c -> c.fields();
                case Term.Case cs -> {
                    var all = new ArrayList<Term>(cs.branches().size() + 1);
                    all.add(cs.scrutinee());
                    all.addAll(cs.branches());
                    yield all;
                }
                default -> List.of();
            };
        }

        // ---- emitting ----

        private sealed interface Step {}
        private record Node(Term term, int indent, boolean flat) implements Step {}
        private record Text(String text) implements Step {}
        private record Break(int indent, boolean flat) implements Step {}
        private record End(int id) implements Step {}

        private void emit(Term top, int topIndent, boolean topFlat) {
            Deque<Step> work = new ArrayDeque<>();
            work.push(new Node(top, topIndent, topFlat));
            while (!work.isEmpty()) {
                switch (work.pop()) {
                    case Text t -> write(t.text());
                    case Break b -> {
                        if (b.flat()) write(" ");
                        else newline(b.indent());
                    }
                    case End e -> end(e.id());
                    case Node n -> node(n, work);
                }
            }
        }

        private void node(Node n, Deque<Step> work) {
            Term term = n.term();
            boolean flat = n.flat() || column + width(term) <= options.width() + 1;
            int inner = n.indent() + options.indent();
            // Steps are pushed in reverse order of output.
            switch (term) {
                case Term.Var v -> {
                    int id = start(term);
                    write(v.name().name());
                    end(id);
                }
                case Term.Const c -> {
                    int id = start(term);
                    write(atom(c));
                    end(id);
                }
                case Term.Builtin b -> {
                    int id = start(term);
                    write("(builtin " + UplcPrinter.builtinName(b.fun()) + ")");
                    end(id);
                }
                case Term.Error ignored -> {
                    int id = start(term);
                    write("(error)");
                    end(id);
                }
                case Term.Lam l -> {
                    int id = start(term);
                    write("(lam " + l.paramName());
                    work.push(new End(id));
                    work.push(new Text(")"));
                    work.push(new Node(l.body(), inner, flat));
                    work.push(new Break(inner, flat));
                }
                case Term.Force f -> unary(term, "(force", f.term(), inner, flat, work);
                case Term.Delay d -> unary(term, "(delay", d.term(), inner, flat, work);
                case Term.Apply a -> applyChain(a, n.indent(), flat, work);
                case Term.Constr c -> {
                    int id = start(term);
                    write("(constr " + Long.toUnsignedString(c.tag()));
                    work.push(new End(id));
                    work.push(new Text(")"));
                    pushChildren(c.fields(), inner, flat, work);
                }
                case Term.Case cs -> {
                    int id = start(term);
                    write("(case");
                    work.push(new End(id));
                    work.push(new Text(")"));
                    pushChildren(cs.branches(), inner, flat, work);
                    work.push(new Node(cs.scrutinee(), inner, flat));
                    work.push(new Break(inner, true));
                }
            }
        }

        private void unary(Term term, String open, Term child, int inner, boolean flat, Deque<Step> work) {
            int id = start(term);
            write(open);
            work.push(new End(id));
            work.push(new Text(")"));
            work.push(new Node(child, inner, flat));
            work.push(new Break(inner, flat));
        }

        private void pushChildren(List<Term> children, int indent, boolean flat, Deque<Step> work) {
            for (int i = children.size() - 1; i >= 0; i--) {
                work.push(new Node(children.get(i), indent, flat));
                work.push(new Break(indent, flat));
            }
        }

        /** Prints {@code [[[f a] b] c]} as {@code [f a b c]}. */
        private void applyChain(Term.Apply outer, int indent, boolean flat, Deque<Step> work) {
            var chain = new ArrayList<Term.Apply>();
            Term head = outer;
            while (head instanceof Term.Apply a) {
                chain.add(a);
                head = a.function();
            }
            Collections.reverse(chain); // innermost application first
            int[] chainIds = new int[chain.size()];
            for (int i = chain.size() - 1; i >= 0; i--) {
                chainIds[i] = start(chain.get(i)); // outermost first, all at the opening bracket
            }
            write("[");
            int argIndent = indent + 1;
            work.push(new End(chainIds[chain.size() - 1]));
            work.push(new Text("]"));
            for (int i = chain.size() - 1; i >= 0; i--) {
                Term.Apply apply = chain.get(i);
                if (i < chain.size() - 1) {
                    work.push(new End(chainIds[i]));
                }
                work.push(new Node(apply.argument(), argIndent, flat));
                work.push(new Break(argIndent, flat));
            }
            work.push(new Node(head, argIndent, flat));
        }

        private int start(Term term) {
            int id = terms.size();
            ids.putIfAbsent(term, id);
            terms.add(term);
            starts.add(new int[]{line, column});
            spans.add(null);
            return id;
        }

        private void end(int id) {
            int[] s = starts.get(id);
            spans.set(id, new Span(s[0], s[1], line, column));
        }

        private void write(String text) {
            out.append(text);
            column += text.length();
        }

        private void newline(int indent) {
            int visible = Math.min(indent, options.width() / 2);
            out.append('\n');
            out.repeat(' ', visible);
            line++;
            column = visible + 1;
        }
    }
}
