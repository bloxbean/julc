package org.julclang.difftest;

import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Pipelines of the list methods over generated lists, each with a plain Java model of what it means. The model is neither a
 * frontend nor the JVM: it reads the plan and runs ordinary Java collections, element by element and from the first, with
 * failures and traces written out, so three independent answers meet in {@code Adr068LambdasPropertyTest}. The alphabets are
 * small, so values collide: a list holds what {@code ys} holds and what {@code k} is.
 * <p>
 * A plan is a list of choices, so jqwik shrinks it and Jazzer drives it, and past its end every choice is 0, so any list is a
 * valid Java program. The test that {@code any}, {@code all} and {@code find} end with may trace or fail: each stops at the
 * element that decides its answer, on the JVM and on-chain (ADR-070 D5, T7), and so does the model.
 */
final class LambdaPrograms {

    private LambdaPrograms() {
    }

    /**
     * A program, with what the model says it gives: {@code value} is null when it fails, after {@code traces}.
     *
     * @param referenceAgrees whether the reference gives this answer too: a list element, a read of {@code find} and a written
     *                        parameter type are F1042's, F1023's and F1024's, where only the JVM and {@code JAVAC} do
     */
    record Program(String source, List<PlutusData> arguments, PlutusData value, List<String> traces, boolean referenceAgrees,
                   Set<String> shapes) {
        boolean failed() {
            return value == null;
        }
    }

    /** A failure of the program, as a Java exception would be. */
    private static final class Failed extends RuntimeException {
        Failed() {
            super(null, null, false, false);
        }
    }

    /** The element types of a step, as Java writes them. */
    private enum Kind {
        INT("BigInteger"), BOOL("Boolean"), TEXT("String"), REC("R"), SUM("S"), LIST("JulcList<BigInteger>");

        final String written;

        Kind(String written) {
            this.written = written;
        }
    }

    /** {@code A(v)} is tag 0, {@code D()} tag 1. */
    private record Sum(BigInteger v) {
    }

    private record Rec(BigInteger a, BigInteger b) {
    }

    /** A lambda: its text, its model, the kind it returns, whether running it can show, and a shape's name. */
    private record Fn(String text, Function<Object, Object> model, Kind result, boolean pure, String shape) {
    }

    /** A plan's choices; past the end, every choice is 0. */
    private static final class Plan {
        private final List<Integer> choices;
        private int at;

        Plan(List<Integer> choices) {
            this.choices = choices;
        }

        int next(int bound) {
            return at < choices.size() ? Math.floorMod(choices.get(at++), bound) : 0;
        }
    }

    /** What a lambda captures, and what running it has shown. */
    private static final class Run {
        final BigInteger k;
        final BigInteger j;
        final boolean c;
        final List<BigInteger> ys;
        final List<String> traces = new ArrayList<>();

        Run(BigInteger k, boolean c, List<BigInteger> ys) {
            this.k = k;
            this.j = k.add(BigInteger.ONE);
            this.c = c;
            this.ys = ys;
        }

        BigInteger said(String message, BigInteger value) {
            traces.add(message);
            return value;
        }

        boolean inYs(BigInteger value) {
            return ys.contains(value);
        }

        long below(BigInteger value) {
            return ys.stream().filter(y -> y.compareTo(value) < 0).count();
        }
    }

    private static final int[] ALPHABET = {-1, 0, 1, 2};

    private static List<BigInteger> list(Plan plan, int most) {
        var values = new ArrayList<BigInteger>();
        int size = plan.next(most + 1);
        for (int i = 0; i < size; i++) values.add(BigInteger.valueOf(ALPHABET[plan.next(ALPHABET.length)]));
        return values;
    }

    private static Fn fn(String text, Function<Object, Object> model, Kind result, boolean pure, String shape) {
        return new Fn(text, model, result, pure, shape);
    }

    private static Function<Object, Object> ints(Function<BigInteger, Object> body) {
        return value -> body.apply((BigInteger) value);
    }

    private static List<Fn> fromInt(Run run) {
        var k = run.k;
        return List.of(
                fn("x -> x.add(k)", ints(x -> x.add(k)), Kind.INT, true, "capture a parameter"),
                fn("x -> x.add(j)", ints(x -> x.add(run.j)), Kind.INT, true, "capture a local"),
                fn("x -> x.add(K)", ints(x -> x.add(BigInteger.TEN)), Kind.INT, true, "capture a static"),
                fn("x -> x", ints(x -> x), Kind.INT, true, "identity"),
                fn("(BigInteger x) -> x.multiply(x)", ints(x -> x.multiply(x)), Kind.INT, true, "written type"),
                fn("x -> { var y = x.add(k); return y.add(y); }", ints(x -> x.add(k).add(x.add(k))), Kind.INT, true, "block"),
                fn("x -> { if (x.signum() > 0) { return x; } return k; }", ints(x -> x.signum() > 0 ? x : k), Kind.INT, true, "branches"),
                fn("x -> said(\"m\", x)", ints(x -> run.said("m", x)), Kind.INT, false, "trace"),
                fn("x -> BigInteger.TEN.divide(x)", ints(x -> {
                    if (x.signum() == 0) throw new Failed();
                    return BigInteger.TEN.divide(x);
                }), Kind.INT, false, "failing"),
                fn("x -> x.signum() > 0", ints(x -> x.signum() > 0), Kind.BOOL, true, "a boolean"),
                fn("x -> x.equals(k)", ints(x -> x.equals(k)), Kind.BOOL, true, "equals"),
                fn("x -> x.compareTo(k) < 0", ints(x -> x.compareTo(k) < 0), Kind.BOOL, true, "compareTo"),
                fn("x -> c", ints(x -> run.c), Kind.BOOL, true, "capture a boolean"),
                fn("x -> ys.contains(x)", ints(run::inYs), Kind.BOOL, true, "contains"),
                fn("x -> ys.any(y -> y.equals(x))", ints(run::inYs), Kind.BOOL, true, "nested"),
                fn("x -> ys.filter(y -> y.compareTo(x) < 0).size() > 0", ints(x -> run.below(x) > 0), Kind.BOOL, true, "nested"),
                fn("x -> \"t\"", ints(x -> "t"), Kind.TEXT, true, "a text"),
                fn("x -> new R(x, k)", ints(x -> new Rec(x, k)), Kind.REC, true, "a record"),
                fn("x -> { if (x.signum() > 0) { return new S.A(x); } return new S.D(); }", ints(x -> new Sum(x.signum() > 0 ? x : null)), Kind.SUM, true, "a variant"),
                fn("x -> { if (ys.contains(x)) { return new S.A(x); } return new S.D(); }", ints(x -> new Sum(run.inYs(x) ? x : null)), Kind.SUM, true, "a variant"),
                fn("x -> { if (c) { return new S.A(k); } return new S.A(x); }", ints(x -> new Sum(run.c ? k : x)), Kind.SUM, true, "a variant"),
                fn("x -> ys.filter(y -> y.compareTo(x) < 0)", ints(x -> run.ys.stream().filter(y -> y.compareTo(x) < 0).toList()), Kind.LIST, true, "a list"));
    }

    /**
     * Tests that trace or fail, offered only as the test a pipeline ends with, so that the steps before it are drawn as they
     * were: whether the test runs on an element past the answer shows in the traces and the failure (D5).
     */
    private static List<Fn> effectfulTests(Run run) {
        return List.of(
                fn("x -> said(\"t\", x).signum() > 0", ints(x -> run.said("t", x).signum() > 0), Kind.BOOL, false, "a traced test"),
                fn("x -> BigInteger.TEN.divide(x).signum() > 0", ints(x -> {
                    if (x.signum() == 0) throw new Failed();
                    return BigInteger.TEN.divide(x).signum() > 0;
                }), Kind.BOOL, false, "a failing test"));
    }

    private static List<Fn> from(Kind kind, Run run) {
        var k = run.k;
        return switch (kind) {
            case INT -> fromInt(run);
            case BOOL -> List.of(
                    fn("b -> b", value -> value, Kind.BOOL, true, "a boolean element"),
                    fn("b -> !b", value -> !(Boolean) value, Kind.BOOL, true, "a boolean element"),
                    fn("(Boolean b) -> b", value -> value, Kind.BOOL, true, "written type"),
                    fn("b -> b ? k : j", value -> (Boolean) value ? k : run.j, Kind.INT, true, "a boolean element"));
            case TEXT -> List.of(
                    fn("t -> t + \"!\"", value -> value + "!", Kind.TEXT, true, "a text element"),
                    fn("t -> t.equals(\"t\")", value -> value.equals("t"), Kind.BOOL, true, "a text element"));
            case REC -> List.of(
                    fn("r -> r.a()", value -> ((Rec) value).a(), Kind.INT, true, "a record element"),
                    fn("r -> r.b().add(k)", value -> ((Rec) value).b().add(k), Kind.INT, true, "a record element"),
                    fn("r -> new R(r.b(), r.a())", value -> new Rec(((Rec) value).b(), ((Rec) value).a()), Kind.REC, true, "a record element"),
                    fn("r -> r.a().compareTo(r.b()) < 0", value -> ((Rec) value).a().compareTo(((Rec) value).b()) < 0, Kind.BOOL, true, "a record element"));
            case SUM -> List.of(
                    fn("s -> { if (s instanceof S.A a) { return a.v(); } return k; }", value -> ((Sum) value).v() != null ? ((Sum) value).v() : k, Kind.INT, true, "a variant element"),
                    fn("s -> { if (s instanceof S.A a) { return ys.any(y -> y.equals(a.v())); } return false; }",
                            value -> ((Sum) value).v() != null && run.inYs(((Sum) value).v()), Kind.BOOL, true, "capture a pattern variable"));
            case LIST -> List.of(
                    fn("l -> BigInteger.valueOf(l.size())", value -> BigInteger.valueOf(((List<?>) value).size()), Kind.INT, true, "a list element"),
                    fn("l -> l.map(y -> y.add(k))", value -> ((List<?>) value).stream().map(y -> ((BigInteger) y).add(k)).toList(), Kind.LIST, true, "a list element"),
                    fn("l -> l.isEmpty()", value -> ((List<?>) value).isEmpty(), Kind.BOOL, true, "a list element"));
        };
    }

    /**
     * {@code JulcArrayList.any} ({@code stopOn} true) or {@code all} ({@code stopOn} false), written out: the elements from
     * the first, up to the first whose test gives {@code stopOn}. Null when a test fails.
     */
    private static Object stopAt(List<Object> values, Fn test, boolean stopOn) {
        try {
            for (var value : values) {
                if ((Boolean) test.model().apply(value) == stopOn) return stopOn;
            }
            return !stopOn;
        } catch (Failed e) {
            return null;
        }
    }

    private static PlutusData data(Object value) {
        return switch (value) {
            case BigInteger integer -> new PlutusData.IntData(integer);
            case Boolean bool -> new PlutusData.ConstrData(bool ? 1 : 0, List.of());
            case String text -> new PlutusData.BytesData(text.getBytes(StandardCharsets.UTF_8));
            case Rec rec -> new PlutusData.ConstrData(0, List.of(data(rec.a()), data(rec.b())));
            case Sum sum -> sum.v() == null ? new PlutusData.ConstrData(1, List.of()) : new PlutusData.ConstrData(0, List.of(data(sum.v())));
            case List<?> items -> new PlutusData.ListData(items.stream().map(LambdaPrograms::data).toList());
            default -> throw new IllegalStateException("no Data for " + value);
        };
    }

    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;

            public class Case {
                static final BigInteger K = BigInteger.TEN;
                static BigInteger said(String m, BigInteger v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))); }
                public record R(BigInteger a, BigInteger b) { }
                public sealed interface S {
                    record A(BigInteger v) implements S { }
                    record D() implements S { }
                }
            """;

    /** The steps a plan takes, then what the program returns. */
    private static final int MOST_STEPS = 4;

    static Program decode(List<Integer> choices, List<Integer> inputs) {
        var plan = new Plan(choices);
        var input = new Plan(inputs);
        var xs = list(input, 4);
        var ys = list(input, 3);
        var k = BigInteger.valueOf(ALPHABET[input.next(ALPHABET.length)]);
        boolean c = input.next(2) == 1;
        var run = new Run(k, c, ys);
        var body = new StringBuilder("        BigInteger j = k.add(BigInteger.ONE);\n");
        var shapes = new LinkedHashSet<String>();
        boolean referenceAgrees = true;
        var kind = Kind.INT;
        String current = "xs";
        List<Object> values = new ArrayList<>(xs);
        boolean failed = false;
        int steps = plan.next(MOST_STEPS + 1);
        for (int i = 0; i < steps; i++) {
            var options = from(kind, run);
            var chosen = options.get(plan.next(options.size()));
            boolean filter = chosen.result() == Kind.BOOL && plan.next(2) == 0;
            var next = filter ? kind : chosen.result();
            String name = "l" + i;
            body.append("        JulcList<").append(next.written).append("> ").append(name).append(" = ").append(current)
                    .append(filter ? ".filter(" : ".map(").append(chosen.text()).append(");\n");
            shapes.add(filter ? "filter" : "map");
            shapes.add(chosen.shape());
            if (kind == Kind.LIST || chosen.shape().equals("written type")) referenceAgrees = false;
            if (!failed) {
                try {
                    var result = new ArrayList<Object>();
                    for (var value : values) {
                        var answer = chosen.model().apply(value);
                        if (!filter) result.add(answer);
                        else if ((Boolean) answer) result.add(value);
                    }
                    values = result;
                } catch (Failed e) {
                    failed = true;
                }
            }
            kind = next;
            current = name;
        }
        var options = from(kind, run);
        // Over integers, an effectful test half the time, so that stopping at the answer is seen often (D5).
        var tests = kind == Kind.INT && plan.next(2) == 0 ? effectfulTests(run)
                : options.stream().filter(option -> option.result() == Kind.BOOL).toList();
        String returned;
        Object value = null;
        var predicate = tests.get(plan.next(tests.size()));
        int terminal = kind == Kind.SUM ? plan.next(2) : plan.next(5);
        if (predicate.shape().equals("written type")) referenceAgrees = false;
        if (!predicate.pure() && terminal != 2 && terminal != 4) {
            shapes.add("an effect in the last test");
            shapes.add(predicate.shape());
        }
        if (kind == Kind.INT && terminal == 3) {
            // A find's element, read: null fails at the read, as the script fails at the field.
            body.append("        BigInteger found = ").append(current).append(".find(").append(predicate.text()).append(");\n");
            body.append("        return found.add(k);\n");
            returned = "BigInteger";
            shapes.add("find");
            referenceAgrees = false;
            if (!failed) {
                try {
                    BigInteger match = null;
                    for (var element : values) {
                        if ((Boolean) predicate.model().apply(element)) {
                            match = (BigInteger) element;
                            break;
                        }
                    }
                    if (match == null) failed = true;
                    else value = match.add(k);
                } catch (Failed e) {
                    failed = true;
                }
            }
        } else {
            switch (terminal) {
                case 0, 3 -> {
                    returned = "boolean";
                    body.append("        return ").append(current).append(".any(").append(predicate.text()).append(");\n");
                    shapes.add("any");
                    if (kind == Kind.LIST) referenceAgrees = false;
                    if (!failed) value = stopAt(values, predicate, true);
                }
                case 1 -> {
                    returned = "boolean";
                    body.append("        return ").append(current).append(".all(").append(predicate.text()).append(");\n");
                    shapes.add("all");
                    if (kind == Kind.LIST) referenceAgrees = false;
                    if (!failed) value = stopAt(values, predicate, false);
                }
                case 2 -> {
                    returned = "long";
                    body.append("        return ").append(current).append(".size();\n");
                    if (!failed) value = BigInteger.valueOf(values.size());
                }
                default -> {
                    returned = "JulcList<" + kind.written + ">";
                    body.append("        return ").append(current).append(";\n");
                    if (!failed) value = values;
                }
            }
        }
        if (failed) value = null;
        String source = HEADER + "    public static " + returned + " m(JulcList<BigInteger> xs, JulcList<BigInteger> ys, BigInteger k, boolean c) {\n"
                + body + "    }\n}\n";
        return new Program(source, List.of(data(xs), data(ys), data(k), data(c)), value == null ? null : data(value), List.copyOf(run.traces),
                referenceAgrees, shapes);
    }
}
