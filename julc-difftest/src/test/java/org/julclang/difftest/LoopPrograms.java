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
 * Whole programs of for-each, {@code while}, {@code break}, accumulators, body locals, bare blocks and {@code if}s, each with
 * a plain Java model of what it means. The model is neither a frontend nor the JVM: it reads the plan, with Java's scoping
 * and order of evaluation written out, so three independent answers meet in {@code Adr068LoopsPropertyTest}.
 * <p>
 * A plan is a list of choices, so jqwik shrinks it and Jazzer drives it. Every plan decodes to a Java program the subset
 * holds: a loop with {@code break}, a bare block, or an update of a local of its body has an accumulator, and one with a nested loop
 * under a {@code break} has two, an update of a local stays in the block that declared it, nothing follows a {@code break},
 * and a {@code while}'s counter goes up first, so every loop ends. A value is kept small by {@code % 1009}, so no
 * {@code long} wraps.
 */
final class LoopPrograms {

    private LoopPrograms() {
    }

    /** One generated program, with the one call of {@code m} it is run at. */
    record Program(String source, long a, boolean c, List<Long> xs, Result expected) {

        List<PlutusData> arguments() {
            var items = new ArrayList<PlutusData>();
            for (long x : xs) items.add(new PlutusData.IntData(BigInteger.valueOf(x)));
            return List.of(new PlutusData.IntData(BigInteger.valueOf(a)), new PlutusData.ConstrData(c ? 1 : 0, List.of()),
                    new PlutusData.ListData(items));
        }
    }

    /** What the model says a run gives: a value, or a failure, after these traces. */
    record Result(Long value, List<String> traces) {
        boolean failed() {
            return value == null;
        }
    }

    // --- The plan ---

    private sealed interface Expr permits Lit, Ref, Item, Bin, Said, Fail {
    }

    private record Lit(long value) implements Expr {
    }

    private record Ref(String name) implements Expr {
    }

    /** {@code name.longValue()} of a for-each's variable. */
    private record Item(String name) implements Expr {
    }

    private record Bin(String operator, Expr left, Expr right) implements Expr {
    }

    /** {@code saidL("label", e)}: a trace of {@code label}, then {@code e}. */
    private record Said(String label, Expr value) implements Expr {
    }

    /** A call that fails. */
    private record Fail() implements Expr {
    }

    private sealed interface Condition permits Compare, Flag {
    }

    private record Compare(String operator, Expr left, Expr right) implements Condition {
    }

    /** The parameter {@code c}, or its negation. */
    private record Flag(boolean negated) implements Condition {
    }

    private sealed interface Statement permits Local, Assign, Effect, Branch, Block, Break, ForEach, While, Return {
    }

    private record Local(String name, Expr value) implements Statement {
    }

    private record Assign(String name, Expr value) implements Statement {
    }

    private record Effect(Expr value) implements Statement {
    }

    private record Branch(Condition condition, List<Statement> then, List<Statement> otherwise) implements Statement {
    }

    private record Block(List<Statement> statements) implements Statement {
    }

    private record Break() implements Statement {
    }

    private record ForEach(String item, boolean written, List<Statement> body) implements Statement {
    }

    /** {@code while (counter < bound)} over a counter declared just before it, which goes up first. */
    private record While(String counter, Expr bound, boolean traced, List<Statement> body) implements Statement {
    }

    private record Return(Expr value) implements Statement {
    }

    private static final String[] ACCUMULATORS = {"p", "q", "r"};
    private static final String[] NAMES = {"t", "u", "v", "K"};
    private static final String[] ITEMS = {"x", "y"};
    private static final String[] COMPARISONS = {"<", "<=", ">", ">=", "==", "!="};
    private static final long STATIC_K = 10;
    private static final int MODULUS = 1009;

    // --- Decoding ---

    /** Reads a plan; past its end every choice is 0, so any list is a program. */
    private static final class Choices {
        private final List<Integer> plan;
        private int at;
        private int labels;
        private int counters;

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

        String counter() {
            return "i" + counters++;
        }
    }

    /** What a point in a method can read, and the locals its own block declared, which it alone may update. */
    private record Visible(Set<String> locals, Set<String> items, Set<String> ownLocals) {
        /** Inside a nested block. */
        Visible inner() {
            return new Visible(new HashSet<>(locals), new HashSet<>(items), new HashSet<>());
        }

        List<String> numbers() {
            var names = new ArrayList<>(locals.stream().sorted().toList());
            if (!locals.contains("K")) names.add("K");
            names.add("a");
            names.addAll(List.of(ACCUMULATORS));
            return names;
        }
    }

    /** What the innermost loop's body turned out to hold. */
    private static final class LoopBody {
        boolean breaks;
        boolean needsAccumulator;
        boolean nests;
    }

    static Program decode(List<Integer> plan, List<Integer> inputs) {
        var choices = new Choices(plan);
        var visible = new Visible(new HashSet<>(), new HashSet<>(), new HashSet<>());
        var body = new ArrayList<Statement>();
        loop(choices, visible, 0, 0, body);
        body.addAll(block(choices, visible, null, 0, 0, true));
        var input = new Choices(inputs);
        long a = input.next(9) - 4;
        boolean c = input.flip();
        var xs = new ArrayList<Long>();
        int count = input.next(5);
        for (int i = 0; i < count; i++) xs.add((long) input.next(7) - 3);
        return new Program(source(body), a, c, xs, new Model(a, c, xs).run(body));
    }

    /** A block's statements, in {@code loop}'s body or at the method's level. {@code last} says it must end in a {@code return}. */
    private static List<Statement> block(Choices choices, Visible outer, LoopBody loop, int loops, int depth, boolean last) {
        var visible = outer.inner();
        var statements = new ArrayList<Statement>();
        int count = choices.next(5);
        for (int i = 0; i < count; i++) {
            int before = statements.size();
            statement(choices, visible, loop, loops, depth, statements);
            var added = statements.getLast();
            if (statements.size() > before && !completes(added)) return statements;
            if (added instanceof Local local) {
                visible.locals().add(local.name());
                visible.ownLocals().add(local.name());
            }
        }
        if (last) statements.add(new Return(expression(choices, visible, 2)));
        return statements;
    }

    private static boolean completes(Statement statement) {
        return switch (statement) {
            case Return ignored -> false;
            case Break ignored -> false;
            case Branch branch -> branch.otherwise() == null || completes(branch.then()) || completes(branch.otherwise());
            case Block block -> completes(block.statements());
            default -> true;
        };
    }

    private static boolean completes(List<Statement> statements) {
        return statements.stream().allMatch(LoopPrograms::completes);
    }

    private static void statement(Choices choices, Visible visible, LoopBody loop, int loops, int depth, List<Statement> statements) {
        boolean inLoop = loop != null;
        switch (choices.next(depth < 3 ? 9 : 4)) {
            case 0 -> statements.add(local(choices, visible));
            case 1 -> statements.add(new Effect(new Said(choices.label(), expression(choices, visible, 1))));
            case 2 -> {
                if (inLoop) statements.add(new Assign(ACCUMULATORS[choices.next(3)], updated(choices, visible)));
                else statements.add(local(choices, visible));
            }
            case 3 -> {
                if (inLoop && choices.flip() && !visible.ownLocals().isEmpty()) {
                    var own = visible.ownLocals().stream().sorted().toList();
                    loop.needsAccumulator = true;
                    statements.add(new Assign(own.get(choices.next(own.size())), updated(choices, visible)));
                } else if (inLoop) {
                    loop.breaks = true;
                    statements.add(new Break());
                } else {
                    statements.add(new Return(expression(choices, visible, 2)));
                }
            }
            case 4, 5 -> statements.add(branch(choices, visible, loop, loops, depth));
            case 6 -> {
                if (inLoop) {
                    loop.needsAccumulator = true;
                    statements.add(new Block(block(choices, visible, loop, loops, depth + 1, false)));
                } else {
                    statements.add(local(choices, visible));
                }
            }
            default -> {
                if (loops >= 2) {
                    statements.add(local(choices, visible));
                } else {
                    if (inLoop) loop.nests = true;
                    loop(choices, visible, loops, depth, statements);
                }
            }
        }
    }

    private static Statement local(Choices choices, Visible visible) {
        var free = new ArrayList<String>();
        for (String name : NAMES) if (!visible.locals().contains(name)) free.add(name);
        if (free.isEmpty()) return new Effect(new Said(choices.label(), expression(choices, visible, 1)));
        String name = free.get(choices.next(free.size()));
        // A local is in scope in its own initializer, so a local K cannot read the static K there.
        return new Local(name, name.equals("K") ? new Bin("+", new Ref("a"), new Lit(1)) : expression(choices, visible, 2));
    }

    private static Expr updated(Choices choices, Visible visible) {
        return new Bin("%", expression(choices, visible, 2), new Lit(MODULUS));
    }

    private static Statement branch(Choices choices, Visible visible, LoopBody loop, int loops, int depth) {
        var condition = condition(choices, visible);
        var then = block(choices, visible, loop, loops, depth + 1, loop == null && choices.next(3) == 0);
        var otherwise = choices.flip() ? block(choices, visible, loop, loops, depth + 1, loop == null && choices.next(3) == 0) : null;
        return new Branch(condition, then, otherwise);
    }

    private static void loop(Choices choices, Visible visible, int loops, int depth, List<Statement> statements) {
        var body = new LoopBody();
        var inside = visible.inner();
        boolean isWhile = choices.flip();
        String counter = null;
        if (isWhile) {
            counter = choices.counter();
            statements.add(new Local(counter, new Lit(0)));
            visible.locals().add(counter);
            inside.locals().add(counter);
        }
        String item = ITEMS[loops];
        if (!isWhile) inside.items().add(item);
        var statementsOfBody = new ArrayList<>(block(choices, inside, body, loops + 1, depth + 1, false));
        var assigned = new HashSet<String>();
        for (String name : ACCUMULATORS) if (assigns(statementsOfBody, name)) assigned.add(name);
        int carried = assigned.size() + (isWhile ? 1 : 0);
        for (String name : ACCUMULATORS) {
            boolean needed = carried == 0 && (body.breaks || body.needsAccumulator) || carried == 1 && body.breaks && body.nests;
            if (needed && !assigned.contains(name)) {
                statementsOfBody.addFirst(new Assign(name, new Bin("%", new Bin("+", new Ref(name), new Lit(1)), new Lit(MODULUS))));
                carried++;
            }
        }
        if (isWhile) {
            statementsOfBody.addFirst(new Assign(counter, new Bin("+", new Ref(counter), new Lit(1))));
            statements.add(new While(counter, choices.flip() ? new Lit(1 + choices.next(3)) : new Bin("+", new Lit(1), new Lit(choices.next(2))),
                    choices.next(3) == 0, statementsOfBody));
        } else {
            statements.add(new ForEach(item, choices.flip(), statementsOfBody));
        }
    }

    private static boolean assigns(List<Statement> statements, String name) {
        for (var statement : statements) {
            boolean found = switch (statement) {
                case Assign assign -> assign.name().equals(name);
                case Branch branch -> assigns(branch.then(), name) || branch.otherwise() != null && assigns(branch.otherwise(), name);
                case Block block -> assigns(block.statements(), name);
                case ForEach loop -> assigns(loop.body(), name);
                case While loop -> assigns(loop.body(), name);
                default -> false;
            };
            if (found) return true;
        }
        return false;
    }

    private static Condition condition(Choices choices, Visible visible) {
        return choices.next(3) == 0 ? new Flag(choices.flip())
                : new Compare(COMPARISONS[choices.next(COMPARISONS.length)], expression(choices, visible, 1), expression(choices, visible, 1));
    }

    private static Expr expression(Choices choices, Visible visible, int depth) {
        switch (choices.next(depth > 0 ? 7 : 3)) {
            case 0:
                return new Lit(choices.next(7) - 3);
            case 1:
            case 2: {
                var names = visible.numbers();
                return new Ref(names.get(choices.next(names.size())));
            }
            case 3: {
                var items = visible.items().stream().sorted().toList();
                if (items.isEmpty()) return new Lit(1);
                return new Item(items.get(choices.next(items.size())));
            }
            case 4:
                return new Bin(choices.flip() ? "+" : "-", expression(choices, visible, depth - 1), expression(choices, visible, depth - 1));
            case 5:
                return new Said(choices.label(), expression(choices, visible, depth - 1));
            default:
                return choices.next(9) == 0 ? new Fail() : new Lit(choices.next(7) - 3);
        }
    }

    // --- The source ---

    private static String source(List<Statement> body) {
        var text = new StringBuilder("""
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    static final long K = %d;
                    static long saidL(String m, long v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))).longValue(); }
                    static long fail(long z) { return Builtins.unIData(Builtins.error()).longValue(); }
                    public static long m(long a, boolean c, JulcList<BigInteger> xs) {
                        long p = a;
                        long q = 1;
                        long r = 0;
                """.formatted(STATIC_K));
        print(body, text, "        ");
        return text.append("    }\n}\n").toString();
    }

    private static void print(List<Statement> statements, StringBuilder text, String indent) {
        for (var statement : statements) {
            switch (statement) {
                case Local local -> text.append(indent).append("long ").append(local.name()).append(" = ").append(print(local.value())).append(";\n");
                case Assign assign -> text.append(indent).append(assign.name()).append(" = ").append(print(assign.value())).append(";\n");
                case Effect effect -> text.append(indent).append(print(effect.value())).append(";\n");
                case Return ret -> text.append(indent).append("return ").append(print(ret.value())).append(";\n");
                case Break ignored -> text.append(indent).append("break;\n");
                case Block block -> {
                    text.append(indent).append("{\n");
                    print(block.statements(), text, indent + "    ");
                    text.append(indent).append("}\n");
                }
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
                case ForEach loop -> {
                    text.append(indent).append("for (").append(loop.written() ? "BigInteger " : "var ").append(loop.item()).append(" : xs) {\n");
                    print(loop.body(), text, indent + "    ");
                    text.append(indent).append("}\n");
                }
                case While loop -> {
                    String counter = loop.traced() ? "saidL(\"w" + loop.counter() + "\", " + loop.counter() + ")" : loop.counter();
                    text.append(indent).append("while (").append(counter).append(" < ").append(print(loop.bound())).append(") {\n");
                    print(loop.body(), text, indent + "    ");
                    text.append(indent).append("}\n");
                }
            }
        }
    }

    private static String print(Condition condition) {
        return switch (condition) {
            case Compare compare -> print(compare.left()) + " " + compare.operator() + " " + print(compare.right());
            case Flag flag -> flag.negated() ? "!c" : "c";
        };
    }

    private static String print(Expr expr) {
        return switch (expr) {
            case Lit lit -> lit.value() < 0 ? "(" + lit.value() + ")" : Long.toString(lit.value());
            case Ref ref -> ref.name();
            case Item item -> item.name() + ".longValue()";
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

        private static final class Broke extends RuntimeException {
            Broke() {
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
        private final List<Long> xs;
        private final List<String> traces = new ArrayList<>();
        /** Scopes, innermost first. A local shadows the static {@code K}, and ends with its block. */
        private final Deque<Map<String, Long>> scopes = new ArrayDeque<>();

        Model(long a, boolean c, List<Long> xs) {
            this.c = c;
            this.xs = xs;
            scopes.push(new HashMap<>(Map.of("a", a, "K", STATIC_K, "p", a, "q", 1L, "r", 0L)));
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
                case Assign assign -> {
                    long value = eval(assign.value());
                    for (var scope : scopes) {
                        if (scope.containsKey(assign.name())) {
                            scope.put(assign.name(), value);
                            return;
                        }
                    }
                    throw new IllegalStateException("no " + assign.name());
                }
                case Effect effect -> eval(effect.value());
                case Return ret -> throw new Returned(eval(ret.value()));
                case Break ignored -> throw new Broke();
                case Block block -> block(block.statements());
                case Branch branch -> {
                    if (test(branch.condition())) block(branch.then());
                    else if (branch.otherwise() != null) block(branch.otherwise());
                }
                case ForEach loop -> {
                    try {
                        for (long x : xs) {
                            scopes.push(new HashMap<>(Map.of(loop.item(), x)));
                            try {
                                block(loop.body());
                            } finally {
                                scopes.pop();
                            }
                        }
                    } catch (Broke broke) {
                        // The loop ends.
                    }
                }
                case While loop -> {
                    try {
                        while (test(loop)) block(loop.body());
                    } catch (Broke broke) {
                        // The loop ends.
                    }
                }
            }
        }

        private boolean test(While loop) {
            if (loop.traced()) traces.add("w" + loop.counter());
            long counter = read(loop.counter());
            return counter < eval(loop.bound());
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
            };
        }

        private long read(String name) {
            for (var scope : scopes) {
                var value = scope.get(name);
                if (value != null) return value;
            }
            throw new IllegalStateException("no " + name);
        }

        private long eval(Expr expr) {
            return switch (expr) {
                case Lit lit -> lit.value();
                case Ref ref -> read(ref.name());
                case Item item -> read(item.name());
                case Bin bin -> {
                    long left = eval(bin.left());
                    long right = eval(bin.right());
                    yield switch (bin.operator()) {
                        case "+" -> left + right;
                        case "-" -> left - right;
                        default -> left % right;
                    };
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
