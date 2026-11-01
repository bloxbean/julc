package org.julclang.difftest;

import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Whole programs of records, a sum, a {@code @NewType} and tuples, each with a plain Java model of what it means. The model
 * is neither a frontend nor the JVM: it reads the plan, with Java's order of evaluation and the documented Data of each
 * type written out, so three independent answers meet in {@code Adr068RecordsPropertyTest}.
 * <p>
 * A plan is a list of choices, so jqwik shrinks it and Jazzer drives it, and past its end every choice is 0, so any list is a
 * valid Java program. The variants come first, then records that may hold them and earlier records, so no type is recursive.
 */
final class RecordPrograms {

    /** A program, with what the model says it gives: {@code value} is null when it fails, after {@code traces}. */
    record Program(String source, List<PlutusData> arguments, TagTable tags, PlutusData value, List<String> traces) {
        boolean failed() {
            return value == null;
        }
    }

    // --- Types ---

    private sealed interface Kind permits Scalar, Named, Sum {
        String written();
    }

    private enum Scalar implements Kind {
        INTEGER("BigInteger"), LONG("long"), BOOL("boolean"), TEXT("String"), BYTES("byte[]");

        private final String written;

        Scalar(String written) {
            this.written = written;
        }

        @Override
        public String written() {
            return written;
        }
    }

    /** A record, with its tag if it is a variant, and the name a program writes it by. */
    private record Named(String name, int tag, List<Field> fields) implements Kind {
        @Override
        public String written() {
            return name;
        }
    }

    private record Field(String name, Kind kind) {
    }

    /** The sealed interface: tags follow its nested records' order, or a {@code permits} clause in another order. */
    private record Sum(boolean nested, List<Named> variants) implements Kind {
        @Override
        public String written() {
            return "S";
        }
    }

    // --- Values, as the model holds them ---

    private sealed interface Value permits Number, Flag, Text, Bytes, Built {
        PlutusData data();
    }

    private record Number(BigInteger value) implements Value {
        @Override
        public PlutusData data() {
            return new PlutusData.IntData(value);
        }
    }

    private record Flag(boolean value) implements Value {
        @Override
        public PlutusData data() {
            return new PlutusData.ConstrData(value ? 1 : 0, List.of());
        }
    }

    private record Text(String value) implements Value {
        @Override
        public PlutusData data() {
            return new PlutusData.BytesData(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record Bytes(byte[] value) implements Value {
        @Override
        public PlutusData data() {
            return new PlutusData.BytesData(value);
        }
    }

    /** A record's fields, in declaration order: its Data is {@code Constr tag [fields]}, and 0 is a standalone record's tag. */
    private record Built(Named type, List<Value> fields) implements Value {
        @Override
        public PlutusData data() {
            return new PlutusData.ConstrData(type.tag(), fields.stream().map(Value::data).toList());
        }
    }

    // --- Expressions ---

    private sealed interface Expr permits Constant, Parameter, Read, Component, Add, Said, Fail, New, Test {
        String text();

        Value eval(Run run);
    }

    /** What one evaluation holds. */
    private static final class Run {
        private final Value[] locals;
        private final BigInteger a;
        private final BigInteger b;
        private final List<String> traces = new ArrayList<>();

        Run(int locals, BigInteger a, BigInteger b) {
            this.locals = new Value[locals];
            this.a = a;
            this.b = b;
        }
    }

    private static final class Failure extends RuntimeException {
        Failure() {
            super(null, null, false, false);
        }
    }

    private record Constant(String text, Value value) implements Expr {
        @Override
        public Value eval(Run run) {
            return value;
        }
    }

    private record Parameter(boolean first) implements Expr {
        @Override
        public String text() {
            return first ? "a" : "b";
        }

        @Override
        public Value eval(Run run) {
            return new Number(first ? run.a : run.b);
        }
    }

    private record Read(int local) implements Expr {
        @Override
        public String text() {
            return "x" + local;
        }

        @Override
        public Value eval(Run run) {
            return run.locals[local];
        }
    }

    /** {@code receiver.name()}, of a record, a tuple or a {@code @NewType}. */
    private record Component(Expr receiver, String name, int index) implements Expr {
        @Override
        public String text() {
            return receiver.text() + "." + name + "()";
        }

        @Override
        public Value eval(Run run) {
            return ((Built) receiver.eval(run)).fields().get(index);
        }
    }

    private record Add(Expr left, Expr right) implements Expr {
        @Override
        public String text() {
            return left.text() + ".add(" + right.text() + ")";
        }

        @Override
        public Value eval(Run run) {
            var x = ((Number) left.eval(run)).value();
            return new Number(x.add(((Number) right.eval(run)).value()));
        }
    }

    /** {@code said("label", e)}: {@code e}, then a trace. */
    private record Said(String label, Expr value) implements Expr {
        @Override
        public String text() {
            return "said(\"" + label + "\", " + value.text() + ")";
        }

        @Override
        public Value eval(Run run) {
            var result = value.eval(run);
            run.traces.add(label);
            return result;
        }
    }

    private record Fail() implements Expr {
        @Override
        public String text() {
            return "fail(0)";
        }

        @Override
        public Value eval(Run run) {
            throw new Failure();
        }
    }

    /** {@code new T(...)}: each argument from left to right, and then the value. */
    private record New(String written, Named type, List<Expr> arguments) implements Expr {
        @Override
        public String text() {
            return "new " + written + "(" + arguments.stream().map(Expr::text).collect(Collectors.joining(", ")) + ")";
        }

        @Override
        public Value eval(Run run) {
            var fields = new ArrayList<Value>();
            for (var argument : arguments) fields.add(argument.eval(run));
            return new Built(type, fields);
        }
    }

    /** {@code receiver instanceof V name}, of something whose static type is the sum. */
    private record Test(Expr receiver, Named variant, String binding) implements Expr {
        @Override
        public String text() {
            return "(" + receiver.text() + " instanceof " + variant.name() + " " + binding + ")";
        }

        @Override
        public Value eval(Run run) {
            return new Flag(((Built) receiver.eval(run)).type().equals(variant));
        }
    }

    /** A local: its declared type or {@code var}, what javac types it, and its value. */
    private record Local(String declared, Kind kind, Expr value) {
    }

    // --- Decoding ---

    private static final String[] COMPONENTS = {"a", "b", "c", "x0", "x1"};
    private static final Named NEW_TYPE = new Named("N", 0, List.of(new Field("value", Scalar.INTEGER)));
    private static final String WRITTEN_TUPLE = "Tuple2<BigInteger, String>";
    private static final Named TUPLE = new Named(WRITTEN_TUPLE, 0, List.of(new Field("first", Scalar.INTEGER), new Field("second", Scalar.TEXT)));

    /** Reads a plan; past its end every choice is 0. */
    private static final class Choices {
        private final List<Integer> plan;
        private int at;
        private int labels;
        private int bindings;

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

        String binding() {
            return "p" + bindings++;
        }
    }

    /** What is declared so far, and the locals in scope. */
    private record Scope(List<Kind> kinds, Sum sum, List<Local> locals) {
    }

    static Program decode(List<Integer> plan, List<Integer> inputs) {
        var choices = new Choices(plan);
        var kinds = new ArrayList<Kind>(List.of(Scalar.values()));
        var sum = sum(choices, List.copyOf(kinds));
        kinds.add(sum);
        var records = new ArrayList<Named>();
        int count = 1 + choices.next(3);
        for (int i = 0; i < count; i++) {
            var record = new Named("R" + i, 0, fields(choices, kinds));
            records.add(record);
            kinds.add(record);
        }
        var scope = new Scope(kinds, sum, new ArrayList<>());
        int statements = 1 + choices.next(5);
        for (int i = 0; i < statements; i++) scope.locals().add(local(choices, scope));
        var kind = kinds.get(choices.next(kinds.size()));
        var result = expression(choices, kind, scope, 2);
        var input = new Choices(inputs);
        return program(records, scope, kind, result, BigInteger.valueOf(input.next(9) - 4), BigInteger.valueOf(input.next(9) - 4));
    }

    private static List<Field> fields(Choices choices, List<Kind> kinds) {
        int count = choices.next(4);
        var fields = new ArrayList<Field>();
        var names = new ArrayList<>(Arrays.asList(COMPONENTS));
        for (int i = 0; i < count; i++) {
            fields.add(new Field(names.remove(choices.next(names.size())), kinds.get(choices.next(kinds.size()))));
        }
        return fields;
    }

    private static Sum sum(Choices choices, List<Kind> kinds) {
        boolean nested = choices.flip();
        int count = 2 + choices.next(2);
        var tags = new ArrayList<>(IntStream.range(0, count).boxed().toList());
        var variants = new ArrayList<Named>();
        for (int i = 0; i < count; i++) {
            int tag = nested ? i : tags.remove(choices.next(tags.size()));
            variants.add(new Named((nested ? "S." : "") + "V" + i, tag, fields(choices, kinds)));
        }
        return new Sum(nested, variants);
    }

    private static Local local(Choices choices, Scope scope) {
        var sum = scope.sum();
        return switch (choices.next(5)) {
            case 0 -> {
                var variant = sum.variants().get(choices.next(sum.variants().size()));
                var value = create(choices, variant, scope, 2);
                yield choices.flip() ? new Local("S", sum, value) : new Local("var", variant, value);
            }
            case 1 -> new Local("var", NEW_TYPE, new New("N", NEW_TYPE, List.of(expression(choices, Scalar.INTEGER, scope, 2))));
            case 2 -> {
                var arguments = List.of(expression(choices, Scalar.INTEGER, scope, 2), expression(choices, Scalar.TEXT, scope, 2));
                boolean diamond = choices.flip();
                yield new Local(diamond ? "var" : WRITTEN_TUPLE, TUPLE, new New(diamond ? "Tuple2<>" : WRITTEN_TUPLE, TUPLE, arguments));
            }
            default -> {
                var kind = scope.kinds().get(choices.next(scope.kinds().size()));
                var value = expression(choices, kind, scope, 2);
                // A var of the sum could be a variant to javac, and instanceof needs the sum.
                yield new Local(kind instanceof Sum || choices.flip() ? kind.written() : "var", kind, value);
            }
        };
    }

    private static Expr create(Choices choices, Named type, Scope scope, int depth) {
        var arguments = new ArrayList<Expr>();
        for (var field : type.fields()) arguments.add(expression(choices, field.kind(), scope, depth - 1));
        return new New(type.written(), type, arguments);
    }

    private static Expr expression(Choices choices, Kind kind, Scope scope, int depth) {
        var reads = reads(kind, scope.locals(), kind instanceof Sum);
        if (!reads.isEmpty() && choices.next(3) == 0) return reads.get(choices.next(reads.size()));
        return switch (kind) {
            case Scalar scalar -> scalar(choices, scalar, scope, depth);
            case Named named -> create(choices, named, scope, depth);
            case Sum sum -> create(choices, sum.variants().get(choices.next(sum.variants().size())), scope, depth);
        };
    }

    private static Expr scalar(Choices choices, Scalar scalar, Scope scope, int depth) {
        return switch (scalar) {
            case LONG -> {
                int value = choices.next(7) - 3;
                yield new Constant(String.valueOf(value), new Number(BigInteger.valueOf(value)));
            }
            case BOOL -> {
                var tested = reads(scope.sum(), scope.locals(), false);
                if (!tested.isEmpty() && choices.flip()) {
                    var variant = scope.sum().variants().get(choices.next(scope.sum().variants().size()));
                    yield new Test(tested.get(choices.next(tested.size())), variant, choices.binding());
                }
                boolean value = choices.flip();
                yield new Constant(String.valueOf(value), new Flag(value));
            }
            case TEXT -> {
                String value = "s" + choices.next(3);
                yield new Constant("\"" + value + "\"", new Text(value));
            }
            case BYTES -> {
                int value = choices.next(5);
                yield new Constant("new byte[]{" + value + "}", new Bytes(new byte[]{(byte) value}));
            }
            case INTEGER -> integer(choices, scope, depth);
        };
    }

    private static Expr integer(Choices choices, Scope scope, int depth) {
        if (depth <= 0) return new Parameter(choices.flip());
        return switch (choices.next(7)) {
            case 0 -> new Constant("BigInteger.ONE", new Number(BigInteger.ONE));
            case 1 -> {
                int value = choices.next(9) - 4;
                yield new Constant("new BigInteger(\"" + value + "\")", new Number(BigInteger.valueOf(value)));
            }
            case 2 -> new Add(expression(choices, Scalar.INTEGER, scope, depth - 1), expression(choices, Scalar.INTEGER, scope, depth - 1));
            case 3, 4 -> new Said(choices.label(), expression(choices, Scalar.INTEGER, scope, depth - 1));
            case 5 -> choices.next(3) == 0 ? new Fail() : new Parameter(true);
            default -> new Parameter(choices.flip());
        };
    }

    /**
     * What reads a value of {@code kind}: a local of it, or a component reached from one through accessors. With {@code exact},
     * a value that may stand where the sum does, a variant too; without it, only what javac types the sum, as {@code instanceof}
     * of another variant needs.
     */
    private static List<Expr> reads(Kind kind, List<Local> locals, boolean exact) {
        var reads = new ArrayList<Expr>();
        for (int i = 0; i < locals.size(); i++) walk(new Read(i), locals.get(i).kind(), kind, exact, reads);
        return reads;
    }

    private static void walk(Expr at, Kind here, Kind wanted, boolean variants, List<Expr> reads) {
        boolean variantOfIt = variants && wanted instanceof Sum sum && here instanceof Named named && sum.variants().contains(named);
        if (here.equals(wanted) || variantOfIt) reads.add(at);
        if (here instanceof Named named) {
            for (int i = 0; i < named.fields().size(); i++) {
                var field = named.fields().get(i);
                walk(new Component(at, field.name(), i), field.kind(), wanted, variants, reads);
            }
        }
    }

    // --- The program and its answer ---

    private static Program program(List<Named> records, Scope scope, Kind kind, Expr result, BigInteger a, BigInteger b) {
        var run = new Run(scope.locals().size(), a, b);
        PlutusData value = null;
        try {
            for (int i = 0; i < scope.locals().size(); i++) run.locals[i] = scope.locals().get(i).value().eval(run);
            value = result.eval(run).data();
        } catch (Failure e) {
            // The script stops here, and the traces so far are what the JVM shows too.
        }
        return new Program(source(records, scope, kind, result), List.of(new PlutusData.IntData(a), new PlutusData.IntData(b)),
                tags(scope.sum()), value, run.traces);
    }

    private static TagTable tags(Sum sum) {
        return new TagTable(sum.variants().stream()
                .map(variant -> new TagTable.Entry("Case$" + variant.name().replace('.', '$'), "Case$S", variant.tag())).toList());
    }

    private static String declaration(String name, Named record, String clause) {
        var components = record.fields().stream().map(field -> field.kind().written() + " " + field.name()).collect(Collectors.joining(", "));
        return "    public record " + name + "(" + components + ")" + clause + " { }\n";
    }

    private static String source(List<Named> records, Scope scope, Kind kind, Expr result) {
        var text = new StringBuilder("""
                import java.math.BigInteger;
                import org.julclang.core.types.Tuple2;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.NewType;

                public class Case {
                    static BigInteger said(String m, BigInteger v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))); }
                    static BigInteger fail(long z) { return Builtins.unIData(Builtins.error()); }
                    @NewType public record N(BigInteger value) { }
                """);
        records.forEach(record -> text.append(declaration(record.name(), record, "")));
        var sum = scope.sum();
        if (sum.nested()) {
            text.append("    public sealed interface S {\n");
            sum.variants().forEach(variant -> text.append(declaration(variant.name().substring(2), variant, " implements S")));
            text.append("    }\n");
        } else {
            var order = sum.variants().stream().sorted(Comparator.comparingInt(Named::tag)).map(Named::name).toList();
            text.append("    public sealed interface S permits ").append(String.join(", ", order)).append(" { }\n");
            sum.variants().forEach(variant -> text.append(declaration(variant.name(), variant, " implements S")));
        }
        text.append("    public static ").append(kind.written()).append(" m(BigInteger a, BigInteger b) {\n");
        var locals = scope.locals();
        for (int i = 0; i < locals.size(); i++) {
            text.append("        ").append(locals.get(i).declared()).append(" x").append(i).append(" = ").append(locals.get(i).value().text()).append(";\n");
        }
        return text.append("        return ").append(result.text()).append(";\n    }\n}\n").toString();
    }
}
