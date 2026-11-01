package org.julclang.difftest;

import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whole programs of locals, {@code if}, {@code return} and {@code instanceof}, each with a plain Java model of what it
 * means. The model is neither a frontend nor the JVM: it reads the plan, with Java's scoping and order of evaluation
 * written out, so three independent answers meet in {@code Adr068StatementsPropertyTest}.
 * <p>
 * A plan is a list of choices, so jqwik shrinks it and Jazzer drives it. Every plan decodes to a valid Java program: a
 * local's name is not one an enclosing block holds, a statement after a {@code return} is not written, and a block that
 * can complete is the method's last only if a {@code return} follows. Arithmetic stays small, so no {@code int} wraps.
 */
final class StatementPrograms {

    private StatementPrograms() {
    }

    /** One generated program, with the one call of {@code m} it is run at. */
    record Program(String source, long a, boolean c, Variant s, Result expected) {

        List<PlutusData> arguments() {
            return List.of(new PlutusData.IntData(BigInteger.valueOf(a)), new PlutusData.ConstrData(c ? 1 : 0, List.of()), s.data());
        }
    }

    /** A value of the program's sealed interface: {@code A(v)} is tag 0, {@code B(v, w)} tag 1, {@code D()} tag 2. */
    record Variant(int tag, long v, long w) {
        PlutusData data() {
            return switch (tag) {
                case 0 -> new PlutusData.ConstrData(0, List.of(integer(v)));
                case 1 -> new PlutusData.ConstrData(1, List.of(integer(v), integer(w)));
                default -> new PlutusData.ConstrData(2, List.of());
            };
        }

        private static PlutusData integer(long value) {
            return new PlutusData.IntData(BigInteger.valueOf(value));
        }
    }

    /** What the model says a run gives: a value, or a failure, after these traces. */
    record Result(Long value, List<String> traces) {
        boolean failed() {
            return value == null;
        }
    }

    // --- The plan ---

    private sealed interface Expr permits Lit, Ref, Field, Bin, Said, Fail {
    }

    private record Lit(long value) implements Expr {
    }

    private record Ref(String name) implements Expr {
    }

    /** {@code name.v()} or {@code name.w()} of a pattern variable. */
    private record Field(String name, String component) implements Expr {
    }

    private record Bin(String operator, Expr left, Expr right) implements Expr {
    }

    /** {@code saidL("label", e)}: a trace of {@code label}, then {@code e}. */
    private record Said(String label, Expr value) implements Expr {
    }

    /** A call that fails. */
    private record Fail() implements Expr {
    }

    private sealed interface Condition permits Compare, Flag, TestOf {
    }

    private record Compare(String operator, Expr left, Expr right) implements Condition {
    }

    /** The parameter {@code c}, or its negation. */
    private record Flag(boolean negated) implements Condition {
    }

    /** {@code s instanceof S.V name}. */
    private record TestOf(int tag, String name) implements Condition {
    }

    private sealed interface Statement permits Local, Effect, Branch, Return {
    }

    private record Local(String name, Expr value) implements Statement {
    }

    private record Effect(Expr value) implements Statement {
    }

    private record Branch(Condition condition, List<Statement> then, List<Statement> otherwise) implements Statement {
    }

    private record Return(Expr value) implements Statement {
    }

    private static final String[] VARIANTS = {"A", "B", "D"};
    private static final String[] NAMES = {"p", "q", "r", "K"};
    private static final String[] COMPARISONS = {"<", "<=", ">", ">=", "==", "!="};
    private static final long STATIC_K = 10;

    // --- Decoding ---

    /** Reads a plan; past its end every choice is 0, so any list is a program. */
    private static final class Choices {
        private final List<Integer> plan;
        private int at;
        private int labels;

        Choices(List<Integer> plan) {
            this.plan = plan;
        }

        int next(int bound) {
            return at < plan.size() ? Math.floorMod(plan.get(at++), bound) : 0;
        }

        boolean flip() {
            return next(2) == 1;
        }

        String label() {
            return "t" + labels++;
        }
    }

    /** What a point in a method can read. */
    private record Visible(Set<String> locals, Map<String, Integer> patterns) {
        Visible copy() {
            return new Visible(new HashSet<>(locals), new HashMap<>(patterns));
        }

        List<String> numbers() {
            var names = new ArrayList<>(locals.stream().sorted().toList());
            if (!locals.contains("K")) names.add("K");
            names.add("a");
            return names;
        }

        boolean taken(String name) {
            return locals.contains(name) || patterns.containsKey(name);
        }
    }

    static Program decode(List<Integer> plan, List<Integer> inputs) {
        var choices = new Choices(plan);
        var visible = new Visible(new HashSet<>(), new HashMap<>());
        var body = block(choices, visible, 0, true);
        var input = new Choices(inputs);
        long a = input.next(9) - 4;
        boolean c = input.flip();
        var s = new Variant(input.next(3), input.next(7) - 3, input.next(7) - 3);
        return new Program(source(body), a, c, s, new Model(a, c, s).run(body));
    }

    /** A block's statements. {@code last} is whether the block must end in a {@code return}. */
    private static List<Statement> block(Choices choices, Visible outer, int depth, boolean last) {
        var visible = outer.copy();
        var statements = new ArrayList<Statement>();
        int count = choices.next(4);
        for (int i = 0; i < count; i++) {
            var statement = statement(choices, visible, depth);
            statements.add(statement);
            if (!completes(statement)) return statements;
            if (statement instanceof Local local) visible.locals().add(local.name());
        }
        if (last) statements.add(new Return(expression(choices, visible, 1)));
        return statements;
    }

    private static boolean completes(Statement statement) {
        return switch (statement) {
            case Return ignored -> false;
            case Branch branch -> branch.otherwise() == null || completes(branch.then()) || completes(branch.otherwise());
            default -> true;
        };
    }

    private static boolean completes(List<Statement> statements) {
        return statements.stream().allMatch(StatementPrograms::completes);
    }

    private static Statement statement(Choices choices, Visible visible, int depth) {
        switch (choices.next(depth < 3 ? 6 : 3)) {
            case 0: {
                var free = new ArrayList<String>();
                for (String name : NAMES) if (!visible.taken(name)) free.add(name);
                if (free.isEmpty()) return new Effect(new Said(choices.label(), expression(choices, visible, 1)));
                String name = free.get(choices.next(free.size()));
                // A local is in scope in its own initializer, so a local K cannot read the static K there.
                return new Local(name, name.equals("K") ? new Bin("+", new Ref("a"), new Lit(1)) : expression(choices, visible, 2));
            }
            case 1:
                return new Effect(new Said(choices.label(), expression(choices, visible, 1)));
            case 2:
                return new Return(expression(choices, visible, 2));
            default:
                return branch(choices, visible, depth);
        }
    }

    private static Statement branch(Choices choices, Visible visible, int depth) {
        var condition = condition(choices, visible);
        var inside = visible.copy();
        if (condition instanceof TestOf test) inside.patterns().put(test.name(), test.tag());
        var then = block(choices, inside, depth + 1, choices.next(3) == 0);
        var otherwise = choices.flip() ? block(choices, visible, depth + 1, choices.next(3) == 0) : null;
        return new Branch(condition, then, otherwise);
    }

    private static Condition condition(Choices choices, Visible visible) {
        switch (choices.next(4)) {
            case 0:
                return new Flag(choices.flip());
            case 1: {
                var free = new ArrayList<String>();
                for (String name : List.of("x", "y", "z")) if (!visible.taken(name)) free.add(name);
                if (!free.isEmpty()) return new TestOf(choices.next(3), free.get(choices.next(free.size())));
                return new Flag(false);
            }
            default:
                return new Compare(COMPARISONS[choices.next(COMPARISONS.length)], expression(choices, visible, 1), expression(choices, visible, 1));
        }
    }

    private static Expr expression(Choices choices, Visible visible, int depth) {
        int kinds = depth > 0 ? 7 : 3;
        switch (choices.next(kinds)) {
            case 0:
                return new Lit(choices.next(7) - 3);
            case 1:
            case 2: {
                var names = visible.numbers();
                return new Ref(names.get(choices.next(names.size())));
            }
            case 3: {
                var patterns = visible.patterns().entrySet().stream().filter(e -> e.getValue() != 2).map(Map.Entry::getKey).sorted().toList();
                if (patterns.isEmpty()) return new Lit(1);
                String name = patterns.get(choices.next(patterns.size()));
                return new Field(name, visible.patterns().get(name) == 1 && choices.flip() ? "w" : "v");
            }
            case 4:
                return new Bin(choices.flip() ? "+" : "-", expression(choices, visible, depth - 1), expression(choices, visible, depth - 1));
            case 5:
                return new Said(choices.label(), expression(choices, visible, depth - 1));
            default:
                return choices.next(5) == 0 ? new Fail() : new Lit(choices.next(7) - 3);
        }
    }

    // --- The source ---

    private static String source(List<Statement> body) {
        var text = new StringBuilder("""
                import java.math.BigInteger;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    static final long K = %d;
                    static long saidL(String m, long v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))).longValue(); }
                    static long fail(long z) { return Builtins.unIData(Builtins.error()).longValue(); }
                    public sealed interface S {
                        record A(BigInteger v) implements S { }
                        record B(BigInteger v, BigInteger w) implements S { }
                        record D() implements S { }
                    }
                    public static long m(long a, boolean c, S s) {
                """.formatted(STATIC_K));
        print(body, text, "        ");
        return text.append("    }\n}\n").toString();
    }

    private static void print(List<Statement> statements, StringBuilder text, String indent) {
        for (var statement : statements) {
            switch (statement) {
                case Local local -> text.append(indent).append("long ").append(local.name()).append(" = ").append(print(local.value())).append(";\n");
                case Effect effect -> text.append(indent).append(print(effect.value())).append(";\n");
                case Return ret -> text.append(indent).append("return ").append(print(ret.value())).append(";\n");
                case Branch branch -> {
                    text.append(indent).append("if (").append(print(branch.condition())).append(") {\n");
                    print(branch.then(), text, indent + "    ");
                    text.append(indent).append("}");
                    if (branch.otherwise() != null) {
                        text.append(" else {\n");
                        print(branch.otherwise(), text, indent + "    ");
                        text.append(indent).append("}");
                    }
                    text.append("\n");
                }
            }
        }
    }

    private static String print(Condition condition) {
        return switch (condition) {
            case Compare compare -> print(compare.left()) + " " + compare.operator() + " " + print(compare.right());
            case Flag flag -> flag.negated() ? "!c" : "c";
            case TestOf test -> "s instanceof S." + VARIANTS[test.tag()] + " " + test.name();
        };
    }

    private static String print(Expr expr) {
        return switch (expr) {
            case Lit lit -> lit.value() < 0 ? "(" + lit.value() + ")" : Long.toString(lit.value());
            case Ref ref -> ref.name();
            case Field field -> field.name() + "." + field.component() + "().longValue()";
            case Bin bin -> "(" + print(bin.left()) + " " + bin.operator() + " " + print(bin.right()) + ")";
            case Said said -> "saidL(\"" + said.label() + "\", " + print(said.value()) + ")";
            case Fail ignored -> "fail(0)";
        };
    }

    // --- The model ---

    /** Runs a plan as Java means it: statements in order, operands left to right, each name in the innermost scope. */
    private static final class Model {
        private static final class Failed extends RuntimeException {
            Failed() {
                super(null, null, false, false);
            }
        }

        private static final class Returned extends RuntimeException {
            final long value;

            Returned(long value) {
                super(null, null, false, false);
                this.value = value;
            }
        }

        private final boolean c;
        private final Variant s;
        private final List<String> traces = new ArrayList<>();
        /** Scopes, innermost first. A local shadows the static {@code K}, and ends with its block. */
        private final Deque<Map<String, Long>> scopes = new ArrayDeque<>();

        Model(long a, boolean c, Variant s) {
            this.c = c;
            this.s = s;
            scopes.push(new HashMap<>(Map.of("a", a, "K", STATIC_K)));
        }

        Result run(List<Statement> body) {
            try {
                block(body);
            } catch (Returned returned) {
                return new Result(returned.value, traces);
            } catch (Failed failed) {
                return new Result(null, traces);
            }
            throw new IllegalStateException("a generated method fell off its end");
        }

        private void block(List<Statement> statements) {
            scopes.push(new HashMap<>());
            try {
                for (var statement : statements) run(statement);
            } finally {
                scopes.pop();
            }
        }

        private void run(Statement statement) {
            switch (statement) {
                case Local local -> {
                    long value = eval(local.value());
                    scopes.peek().put(local.name(), value);
                }
                case Effect effect -> eval(effect.value());
                case Return ret -> throw new Returned(eval(ret.value()));
                case Branch branch -> {
                    if (branch.condition() instanceof TestOf test) {
                        if (test.tag() == s.tag()) {
                            scopes.push(new HashMap<>(Map.of(test.name() + ".v", s.v(), test.name() + ".w", s.w())));
                            try {
                                block(branch.then());
                            } finally {
                                scopes.pop();
                            }
                        } else if (branch.otherwise() != null) {
                            block(branch.otherwise());
                        }
                    } else if (test(branch.condition())) {
                        block(branch.then());
                    } else if (branch.otherwise() != null) {
                        block(branch.otherwise());
                    }
                }
            }
        }

        private boolean test(Condition condition) {
            return switch (condition) {
                case Flag flag -> flag.negated() != c;
                case Compare compare -> {
                    long left = eval(compare.left());
                    long right = eval(compare.right());
                    yield switch (compare.operator()) {
                        case "<" -> left < right;
                        case "<=" -> left <= right;
                        case ">" -> left > right;
                        case ">=" -> left >= right;
                        case "==" -> left == right;
                        default -> left != right;
                    };
                }
                case TestOf ignored -> throw new IllegalStateException();
            };
        }

        private long read(String name) {
            for (var scope : scopes) {
                var value = scope.get(name);
                if (value != null) return value;
            }
            throw new IllegalStateException("not visible: " + name);
        }

        private long eval(Expr expr) {
            return switch (expr) {
                case Lit lit -> lit.value();
                case Ref ref -> read(ref.name());
                case Field field -> read(field.name() + "." + field.component());
                case Bin bin -> {
                    long left = eval(bin.left());
                    long right = eval(bin.right());
                    yield bin.operator().equals("+") ? left + right : left - right;
                }
                case Said said -> {
                    long value = eval(said.value());
                    traces.add(said.label());
                    yield value;
                }
                case Fail ignored -> throw new Failed();
            };
        }
    }
}
