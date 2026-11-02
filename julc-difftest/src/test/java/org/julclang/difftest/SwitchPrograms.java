package org.julclang.difftest;

import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Whole programs of {@code switch} expressions, each with a plain Java model of what it means: the selector once, then each
 * label in source order, with its guard read after its pattern matched, the fields of what matched, an arm's block, the
 * arrow and the colon form, and traces and failures at every point. The model is neither a frontend nor the JVM, so three
 * independent answers meet in {@code Adr068SwitchPropertyTest}.
 * <p>
 * A plan is a list of choices, so jqwik shrinks it and Jazzer drives it. Every plan decodes to a program javac accepts: an
 * arm's label is not dominated, a switch without {@code default} covers each variant with an unguarded arm, a name is not
 * one in scope, and a guard stays within what the subset holds. Arithmetic stays small, so no {@code long} wraps.
 */
final class SwitchPrograms {

    private SwitchPrograms() {
    }

    /** One generated program, with the one call of {@code m} it is run at. */
    record Program(String source, long a, boolean c, Variant s, Variant t, Result expected) {

        List<PlutusData> arguments() {
            return List.of(new PlutusData.IntData(BigInteger.valueOf(a)), new PlutusData.ConstrData(c ? 1 : 0, List.of()), s.data(), t.data());
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

    private sealed interface Expr permits Lit, Ref, Field, Bin, Said, Fail, Plus, Switch {
    }

    private record Lit(long value) implements Expr {
    }

    private record Ref(String name) implements Expr {
    }

    /** {@code name.v()} or {@code name.w()} of a pattern variable, as a {@code long}. */
    private record Field(String name, String component) implements Expr {
    }

    private record Bin(String operator, Expr left, Expr right) implements Expr {
    }

    /** {@code saidL("label", e)}: {@code e}, then a trace of {@code label}. */
    private record Said(String label, Expr value) implements Expr {
    }

    /** A call that fails. */
    private record Fail() implements Expr {
    }

    /** {@code plus(left, right)}: a helper's arguments, left to right. */
    private record Plus(Expr left, Expr right) implements Expr {
    }

    /** A {@code switch}; {@code colon} is the colon form. {@code arms} ends in {@code default} if one is written. */
    private record Switch(Selector selector, List<Arm> arms, boolean colon) implements Expr {
    }

    /** {@code s} or {@code t}, {@code label} traced as it is read, or {@code fails} a call that fails first. */
    private record Selector(String parameter, String label, boolean fails) {
    }

    /** {@code tag} is -1 for {@code default}, and {@code guard} null for no guard. */
    private record Arm(int tag, String name, Guard guard, Body body) {
    }

    private sealed interface Guard permits Compare, Flag, Always, Test, Traced, Exact, Inner, Failing {
    }

    /** A guard that fails. */
    private record Failing() implements Guard {
    }

    /** {@code name.v().compareTo(BigInteger.valueOf(bound)) OP 0}, or {@code name.w()...}. */
    private record Compare(String name, String component, String operator, int bound) implements Guard {
    }

    /** The parameter {@code c}, or its negation. */
    private record Flag(boolean negated) implements Guard {
    }

    /** {@code when true}, or {@code when ON}: an unguarded label. */
    private record Always(boolean constant) implements Guard {
    }

    /** {@code parameter instanceof S.V}. */
    private record Test(String parameter, int tag) implements Guard {
    }

    /** {@code saidL("label", a) > bound}. */
    private record Traced(String label, int bound) implements Guard {
    }

    /** {@code (2147483647 + 1) < a}: the constant has Java's width, here as in the program's own run. */
    private record Exact() implements Guard {
    }

    /** {@code switch (parameter) { case S.A x -> first; default -> second; }}. */
    private record Inner(String parameter, String name, boolean first, boolean second) implements Guard {
    }

    private sealed interface Body permits Value, Block {
    }

    private record Value(Expr value) implements Body {
    }

    /** A block: its statements, then a {@code yield}. */
    private record Block(List<Step> steps, Expr result) implements Body {
    }

    private sealed interface Step permits Local, Effect, EarlyYield {
    }

    private record Local(String name, Expr value) implements Step {
    }

    private record Effect(Expr value) implements Step {
    }

    /** {@code if (c) { yield value; }}, or its negation. */
    private record EarlyYield(boolean negated, Expr value) implements Step {
    }

    private static final String[] VARIANTS = {"A", "B", "D"};
    private static final String[] OPERATORS = {">", ">=", "<", "<=", "==", "!="};
    private static final long STATIC_K = 10;

    // --- Decoding ---

    /** Reads a plan; past its end every choice is 0, so any list is a program. */
    private static final class Choices {
        private final List<Integer> plan;
        private int at;
        private int labels;
        private int names;

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

        String name() {
            return "n" + names++;
        }
    }

    /** What a point can read: the {@code long} locals, and the pattern variables with their tags. */
    private record Visible(List<String> numbers, List<String[]> patterns) {
        Visible with(String local) {
            var more = new ArrayList<>(numbers);
            more.add(local);
            return new Visible(more, patterns);
        }

        Visible withPattern(String name, int tag) {
            var more = new ArrayList<>(patterns);
            more.add(new String[]{name, Integer.toString(tag)});
            var numbers = new ArrayList<>(this.numbers);
            numbers.remove(name);
            return new Visible(numbers, more);
        }

        boolean takes(String name) {
            return numbers.contains(name) && !name.equals("K") || patterns.stream().anyMatch(p -> p[0].equals(name));
        }
    }

    static Program decode(List<Integer> plan, List<Integer> inputs) {
        var choices = new Choices(plan);
        var visible = new Visible(List.of("a", "K"), List.of());
        var locals = new ArrayList<Local>();
        int count = choices.next(3);
        for (int i = 0; i < count; i++) {
            var name = choices.name();
            locals.add(new Local(name, expression(choices, visible, 2)));
            visible = visible.with(name);
        }
        var result = switchOf(choices, visible, 0);
        var input = new Choices(inputs);
        long a = input.next(9) - 4;
        boolean c = input.flip();
        var s = variant(input);
        var t = variant(input);
        return new Program(source(locals, result), a, c, s, t, new Model(a, c, s, t).run(locals, result));
    }

    private static Variant variant(Choices input) {
        return new Variant(input.next(3), input.next(7) - 3, input.next(7) - 3);
    }

    private static Switch switchOf(Choices choices, Visible visible, int depth) {
        var parameter = choices.flip() ? "s" : "t";
        var selector = switch (choices.next(9)) {
            case 0 -> new Selector(parameter, choices.label(), false);
            case 1 -> new Selector(parameter, null, true);
            default -> new Selector(parameter, null, false);
        };
        boolean colon = choices.next(4) == 0;
        // Each variant's arms keep their order, guarded ones before an unguarded one, in a merge of the three.
        var perVariant = new ArrayList<List<Arm>>();
        boolean covers = true;
        for (int tag = 0; tag < 3; tag++) {
            var arms = new ArrayList<Arm>();
            int guarded = choices.next(3);
            for (int i = 0; i < guarded; i++) arms.add(arm(choices, visible, tag, true, depth, colon));
            boolean unguarded = choices.next(3) != 0;
            if (unguarded) arms.add(arm(choices, visible, tag, false, depth, colon));
            covers &= unguarded;
            perVariant.add(arms);
        }
        var arms = new ArrayList<Arm>();
        while (perVariant.stream().anyMatch(list -> !list.isEmpty())) {
            var next = perVariant.get(choices.next(3));
            if (next.isEmpty()) next = perVariant.stream().filter(list -> !list.isEmpty()).findFirst().orElseThrow();
            arms.add(next.removeFirst());
        }
        // A switch that covers every variant unconditionally takes no default: javac rejects one.
        if (!covers) arms.add(new Arm(-1, null, null, body(choices, visible, depth, colon)));
        return new Switch(selector, arms, colon);
    }

    private static Arm arm(Choices choices, Visible visible, int tag, boolean guarded, int depth, boolean colon) {
        var name = patternName(choices, visible);
        var inside = visible.withPattern(name, tag);
        // A guard that is a constant true dominates, so it stands where an unguarded label does.
        var guard = guarded ? guard(choices, inside, name, tag) : choices.next(4) == 0 ? new Always(choices.flip()) : null;
        return new Arm(tag, name, guard, body(choices, inside, depth, colon));
    }

    /** {@code K} shadows the static field in its arm, and only there. */
    private static String patternName(Choices choices, Visible visible) {
        return choices.next(4) == 0 && !visible.takes("K") ? "K" : choices.name();
    }

    private static Guard guard(Choices choices, Visible inside, String name, int tag) {
        return switch (choices.next(8)) {
            case 7 -> new Failing();
            case 0 -> tag == 2 ? new Flag(choices.flip()) : new Compare(name, tag == 1 && choices.flip() ? "w" : "v",
                    OPERATORS[choices.next(OPERATORS.length)], choices.next(5) - 2);
            case 1 -> new Flag(choices.flip());
            case 2 -> new Test(choices.flip() ? "s" : "t", choices.next(3));
            case 3 -> new Traced(choices.label(), choices.next(5) - 2);
            case 4 -> new Exact();
            case 5 -> new Inner(choices.flip() ? "s" : "t", choices.name(), choices.flip(), choices.flip());
            default -> tag == 2 ? new Flag(false) : new Compare(name, "v", ">", 0);
        };
    }

    private static Body body(Choices choices, Visible visible, int depth, boolean colon) {
        if (!colon && choices.flip()) return new Value(expression(choices, visible, 2 - Math.min(depth, 1)));
        var steps = new ArrayList<Step>();
        var inside = visible;
        int count = choices.next(3);
        for (int i = 0; i < count; i++) {
            switch (choices.next(3)) {
                case 0 -> {
                    var name = choices.name();
                    steps.add(new Local(name, expression(choices, inside, 1)));
                    inside = inside.with(name);
                }
                case 1 -> steps.add(new Effect(new Said(choices.label(), expression(choices, inside, 1))));
                default -> steps.add(new EarlyYield(choices.flip(), expression(choices, inside, 1)));
            }
        }
        return new Block(steps, expression(choices, inside, 1));
    }

    private static Expr expression(Choices choices, Visible visible, int depth) {
        switch (choices.next(depth > 0 ? 9 : 3)) {
            case 0:
                return new Lit(choices.next(7) - 3);
            case 1:
            case 2:
                return new Ref(visible.numbers().get(choices.next(visible.numbers().size())));
            case 3: {
                var fields = visible.patterns().stream().filter(p -> !p[1].equals("2")).toList();
                if (fields.isEmpty()) return new Lit(1);
                var pattern = fields.get(choices.next(fields.size()));
                return new Field(pattern[0], pattern[1].equals("1") && choices.flip() ? "w" : "v");
            }
            case 4:
                return new Bin(choices.flip() ? "+" : "-", expression(choices, visible, depth - 1), expression(choices, visible, depth - 1));
            case 5:
                return new Said(choices.label(), expression(choices, visible, depth - 1));
            case 6:
                return new Plus(expression(choices, visible, depth - 1), expression(choices, visible, depth - 1));
            case 7:
                return switchOf(choices, visible, 1);
            default:
                return choices.flip() ? new Fail() : new Lit(choices.next(7) - 3);
        }
    }

    // --- The source ---

    private static String source(List<Local> locals, Switch result) {
        var text = new StringBuilder("""
                import java.math.BigInteger;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    static final long K = %d;
                    static final boolean ON = true;
                    static long saidL(String m, long v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))).longValue(); }
                    static S said(String m, S v) { saidL(m, 0); return v; }
                    static S bad(S v) { return fail(0) == 0 ? v : v; }
                    static long fail(long z) { return Builtins.unIData(Builtins.error()).longValue(); }
                    static long plus(long x, long y) { return x + y; }
                    public sealed interface S {
                        record A(BigInteger v) implements S { }
                        record B(BigInteger v, BigInteger w) implements S { }
                        record D() implements S { }
                    }
                    public static long m(long a, boolean c, S s, S t) {
                """.formatted(STATIC_K));
        for (var local : locals) text.append("        long ").append(local.name()).append(" = ").append(print(local.value())).append(";\n");
        text.append("        return ").append(print(result)).append(";\n    }\n}\n");
        return text.toString();
    }

    private static String print(Expr expr) {
        return switch (expr) {
            case Lit lit -> lit.value() < 0 ? "(" + lit.value() + ")" : Long.toString(lit.value());
            case Ref ref -> ref.name();
            case Field field -> field.name() + "." + field.component() + "().longValue()";
            case Bin bin -> "(" + print(bin.left()) + " " + bin.operator() + " " + print(bin.right()) + ")";
            case Said said -> "saidL(\"" + said.label() + "\", " + print(said.value()) + ")";
            case Fail ignored -> "fail(0)";
            case Plus plus -> "plus(" + print(plus.left()) + ", " + print(plus.right()) + ")";
            case Switch value -> print(value);
        };
    }

    private static String print(Switch value) {
        var text = new StringBuilder("switch (").append(print(value.selector())).append(") {\n");
        for (var arm : value.arms()) {
            text.append(arm.tag() < 0 ? "default" : "case S." + VARIANTS[arm.tag()] + " " + arm.name());
            if (arm.guard() != null) text.append(" when ").append(print(arm.guard()));
            text.append(value.colon() ? ": " : " -> ");
            text.append(print(arm.body(), value.colon())).append("\n");
        }
        return text.append("}").toString();
    }

    private static String print(Selector selector) {
        if (selector.fails()) return "bad(" + selector.parameter() + ")";
        return selector.label() == null ? selector.parameter() : "said(\"" + selector.label() + "\", " + selector.parameter() + ")";
    }

    private static String print(Guard guard) {
        return switch (guard) {
            case Compare compare -> compare.name() + "." + compare.component() + "().compareTo(BigInteger.valueOf(" + compare.bound() + ")) "
                    + compare.operator() + " 0";
            case Flag flag -> flag.negated() ? "!c" : "c";
            case Always always -> always.constant() ? "ON" : "true";
            case Test test -> test.parameter() + " instanceof S." + VARIANTS[test.tag()];
            case Traced traced -> "saidL(\"" + traced.label() + "\", a) > " + (traced.bound() < 0 ? "(" + traced.bound() + ")" : traced.bound());
            case Exact ignored -> "(2147483647 + 1) < a";
            case Failing ignored -> "fail(0) > 0";
            case Inner inner -> "switch (" + inner.parameter() + ") { case S.A " + inner.name() + " -> " + inner.first()
                    + "; default -> " + inner.second() + "; }";
        };
    }

    private static String print(Body body, boolean colon) {
        return switch (body) {
            case Value value -> print(value.value()) + ";";
            case Block block -> {
                var text = new StringBuilder(colon ? "" : "{\n");
                for (var step : block.steps()) {
                    text.append(switch (step) {
                        case Local local -> "long " + local.name() + " = " + print(local.value()) + ";\n";
                        case Effect effect -> print(effect.value()) + ";\n";
                        case EarlyYield yield -> "if (" + (yield.negated() ? "!c" : "c") + ") { yield " + print(yield.value()) + "; }\n";
                    });
                }
                text.append("yield ").append(print(block.result())).append(";");
                if (!colon) text.append("\n}");
                yield text.toString();
            }
        };
    }

    // --- The model ---

    /** Runs a plan as Java means it: operands left to right, each name in the innermost scope, the first arm that applies. */
    private static final class Model {
        private static final class Failed extends RuntimeException {
            Failed() {
                super(null, null, false, false);
            }
        }

        private static final class Yielded extends RuntimeException {
            final long value;

            Yielded(long value) {
                super(null, null, false, false);
                this.value = value;
            }
        }

        private final long a;
        private final boolean c;
        private final Variant s;
        private final Variant t;
        private final List<String> traces = new ArrayList<>();

        Model(long a, boolean c, Variant s, Variant t) {
            this.a = a;
            this.c = c;
            this.s = s;
            this.t = t;
        }

        /** Names to values: a pattern variable's fields as {@code name.v}, and an inner binding replaces an outer. */
        private record Env(String name, long value, Env outer) {
            static long read(Env env, String name, long missing) {
                for (var e = env; e != null; e = e.outer()) if (e.name().equals(name)) return e.value();
                return missing;
            }
        }

        Result run(List<Local> locals, Switch result) {
            try {
                var env = new Env("a", a, new Env("K", STATIC_K, null));
                for (var local : locals) env = new Env(local.name(), eval(local.value(), env), env);
                return new Result(eval(result, env), traces);
            } catch (Failed failed) {
                return new Result(null, traces);
            }
        }

        private long eval(Expr expr, Env env) {
            return switch (expr) {
                case Lit lit -> lit.value();
                case Ref ref -> Env.read(env, ref.name(), 0);
                case Field field -> Env.read(env, field.name() + "." + field.component(), 0);
                case Bin bin -> {
                    long left = eval(bin.left(), env);
                    long right = eval(bin.right(), env);
                    yield bin.operator().equals("+") ? left + right : left - right;
                }
                case Said said -> {
                    long value = eval(said.value(), env); // An argument runs before the call.
                    traces.add(said.label());
                    yield value;
                }
                case Fail ignored -> throw new Failed();
                case Plus plus -> {
                    long left = eval(plus.left(), env);
                    yield left + eval(plus.right(), env);
                }
                case Switch value -> eval(value, env);
            };
        }

        private Variant parameter(String name) {
            return name.equals("s") ? s : t;
        }

        private long eval(Switch value, Env env) {
            var selector = value.selector();
            if (selector.fails()) throw new Failed();
            if (selector.label() != null) traces.add(selector.label());
            var selected = parameter(selector.parameter());
            for (var arm : value.arms()) {
                if (arm.tag() >= 0 && arm.tag() != selected.tag()) continue;
                var inside = env;
                if (arm.tag() >= 0) {
                    inside = new Env(arm.name() + ".v", selected.v(), new Env(arm.name() + ".w", selected.w(), env));
                }
                if (arm.guard() != null && !holds(arm.guard(), inside)) continue;
                return run(arm.body(), inside);
            }
            throw new IllegalStateException("a generated switch had no arm for " + selected);
        }

        private boolean holds(Guard guard, Env env) {
            return switch (guard) {
                case Compare compare -> {
                    int order = Long.compare(Env.read(env, compare.name() + "." + compare.component(), 0), compare.bound());
                    yield switch (compare.operator()) {
                        case ">" -> order > 0;
                        case ">=" -> order >= 0;
                        case "<" -> order < 0;
                        case "<=" -> order <= 0;
                        case "==" -> order == 0;
                        default -> order != 0;
                    };
                }
                case Flag flag -> flag.negated() != c;
                case Always ignored -> true;
                case Test test -> parameter(test.parameter()).tag() == test.tag();
                case Traced traced -> {
                    traces.add(traced.label());
                    yield a > traced.bound();
                }
                case Exact ignored -> Integer.MIN_VALUE < a;
                case Failing ignored -> throw new Failed();
                case Inner inner -> parameter(inner.parameter()).tag() == 0 ? inner.first() : inner.second();
            };
        }

        private long run(Body body, Env env) {
            return switch (body) {
                case Value value -> eval(value.value(), env);
                case Block block -> {
                    var inside = env;
                    long result;
                    try {
                        for (var step : block.steps()) {
                            switch (step) {
                                case Local local -> inside = new Env(local.name(), eval(local.value(), inside), inside);
                                case Effect effect -> eval(effect.value(), inside);
                                case EarlyYield early -> {
                                    if (early.negated() != c) throw new Yielded(eval(early.value(), inside));
                                }
                            }
                        }
                        result = eval(block.result(), inside);
                    } catch (Yielded yielded) {
                        result = yielded.value;
                    }
                    yield result;
                }
            };
        }
    }
}
