package org.julclang.difftest.gen;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The bounded grammar that ADR-063 D7 enumerates exhaustively: G0 programs whose loops run and whose
 * updates reach the result. Every program has the shape
 * <pre>
 * BigInteger v0 = a;
 * BigInteger v1 = b;
 * &lt;one or two loops, in one of the {@link Shape}s&gt;
 * return v0.add(v1.multiply(BigInteger.valueOf(3)));
 * </pre>
 * where each loop body is a sequence of {@link Statement}s that reads the loop variable. The bound:
 * <ul>
 *   <li>one loop: a body of one or two statements, over all {@value #STATEMENT_COUNT} statements;</li>
 *   <li>two loops, one after the other or nested: a body of one statement in each loop, plus the inner
 *       loop in the outer body;</li>
 *   <li>a {@code while} runs once or three times, with the loop variable counting from 1; a for-each
 *       runs over the {@code xs} argument.</li>
 * </ul>
 * Combinations JuLC documents as rejected are left out: a loop body that contains a loop cannot also
 * {@code break} (saved case {@code reject-nested-loop-in-break-loop}), and every body updates an
 * accumulator, which also keeps a for-each with {@code break} valid
 * ({@code reject-break-in-for-each-without-accumulator}).
 */
public final class G0Enumeration {

    /** The enumeration's version, saved with every failure it finds (D8). */
    public static final String VERSION = "G0 enumeration r1";

    static final int STATEMENT_COUNT = 9;

    /** Loop-body statements; {@code X} is the loop variable as a {@code BigInteger}. */
    public enum Statement {
        /** {@code v0 = v0.add(X);} */
        ADD,
        /** {@code v1 = v1.subtract(v0);}: reads the other accumulator, so order matters. */
        SUBTRACT_OTHER,
        /** {@code if (X > a) { v0 = v0.add(X); }} */
        IF_UPDATE,
        /** {@code if (X < b) { v0 = v0.add(1); } else { v1 = v1.add(X); }} */
        IF_ELSE_UPDATE,
        /** {@code if (X > b) { break; }} */
        BREAK,
        /** {@code BigInteger t = X.multiply(2); v1 = v1.add(t);} */
        LOCAL,
        /** {@code v0 = (X < a ? v0.subtract(X) : v0.add(2));} */
        TERNARY,
        /** {@code if (X > a && X < 4) { v1 = v1.add(1); }}: independent of the break condition. */
        AND_UPDATE,
        /** {@code if (X < a || X > 2) { v1 = v1.add(X); }}: independent of the break condition. */
        OR_UPDATE;

        boolean updates() {
            return this != BREAK;
        }

        boolean isIfUpdate() {
            return this == IF_UPDATE || this == IF_ELSE_UPDATE || this == AND_UPDATE || this == OR_UPDATE;
        }
    }

    /** How the loops are arranged. */
    public enum Shape {
        /** One {@code while} that runs once. */
        WHILE_ONCE(false),
        /** One {@code while} that runs three times. */
        WHILE(false),
        /** One for-each over {@code xs}. */
        FOR_EACH(false),
        /** A {@code while} that runs three times, then a for-each. */
        WHILE_THEN_FOR_EACH(true),
        /** A {@code while} that runs three times, whose body is a statement and then a for-each. */
        WHILE_AROUND_FOR_EACH(true),
        /** A for-each whose body is a statement and then a {@code while} that runs three times. */
        FOR_EACH_AROUND_WHILE(true);

        final boolean twoLoops;

        Shape(boolean twoLoops) {
            this.twoLoops = twoLoops;
        }
    }

    /** What to leave out of a program, for the corpus checks. */
    public sealed interface Erasure {
        /** Nothing: the program itself. */
        record None() implements Erasure {
        }

        /** Every loop, with its counter: the declarations and the return stay. */
        record Loops() implements Erasure {
        }

        /** One statement of the first ({@code inner == false}) or the second loop body. */
        record OneStatement(boolean inner, int index) implements Erasure {
        }
    }

    /**
     * One enumerated program. {@code first} is the body of the single, first or outer loop;
     * {@code second} is the body of the second or inner loop, empty for a single loop.
     */
    public record Program(Shape shape, List<Statement> first, List<Statement> second) {

        public Program {
            first = List.copyOf(first);
            second = List.copyOf(second);
        }

        /** The features G0 would record for this program, for coverage and the divergence registry. */
        public Set<G0.Feature> features() {
            var features = EnumSet.noneOf(G0.Feature.class);
            features.add(G0.Feature.ACCUMULATOR_UPDATED);
            if (shape != Shape.FOR_EACH) features.add(G0.Feature.WHILE);
            if (shape != Shape.WHILE_ONCE && shape != Shape.WHILE) features.add(G0.Feature.FOR_EACH);
            if (shape == Shape.WHILE_AROUND_FOR_EACH || shape == Shape.FOR_EACH_AROUND_WHILE) {
                features.add(G0.Feature.NESTED_LOOP);
            }
            for (List<Statement> body : List.of(first, second)) {
                for (Statement s : body) {
                    switch (s) {
                        case IF_UPDATE -> features.add(G0.Feature.IF_IN_LOOP);
                        case IF_ELSE_UPDATE -> {
                            features.add(G0.Feature.IF_IN_LOOP);
                            features.add(G0.Feature.IF_ELSE_IN_LOOP);
                        }
                        case AND_UPDATE, OR_UPDATE -> {
                            features.add(G0.Feature.IF_IN_LOOP);
                            features.add(G0.Feature.SHORT_CIRCUIT);
                        }
                        case BREAK -> features.add(G0.Feature.BREAK);
                        case LOCAL -> features.add(G0.Feature.BODY_LOCAL);
                        case TERNARY -> features.add(G0.Feature.TERNARY);
                        default -> { }
                    }
                }
                if (updatesBoth(body)) features.add(G0.Feature.SEVERAL_ACCUMULATORS);
            }
            // The shape of ADR-062 T21: a while body with a break and an if that assigns.
            boolean whileBody = shape == Shape.WHILE_ONCE || shape == Shape.WHILE;
            if (whileBody && first.contains(Statement.BREAK) && first.stream().anyMatch(Statement::isIfUpdate)) {
                features.add(G0.Feature.WHILE_BREAK_WITH_IF_UPDATE);
            }
            // The shape of ADR-062 T23: a for-each body with a break and an if/else whose branches assign
            // different variables.
            if (shape == Shape.FOR_EACH && first.contains(Statement.BREAK) && first.contains(Statement.IF_ELSE_UPDATE)) {
                features.add(G0.Feature.FOR_EACH_BREAK_WITH_SPLIT_IF_ELSE);
            }
            return features;
        }

        private static boolean updatesBoth(List<Statement> body) {
            boolean v0 = false, v1 = false;
            for (Statement s : body) {
                switch (s) {
                    case ADD, IF_UPDATE, TERNARY -> v0 = true;
                    case SUBTRACT_OTHER, LOCAL, AND_UPDATE, OR_UPDATE -> v1 = true;
                    case IF_ELSE_UPDATE -> {
                        v0 = true;
                        v1 = true;
                    }
                    case BREAK -> { }
                }
            }
            return v0 && v1;
        }

        public String source(String className) {
            return source(className, new Erasure.None());
        }

        public String source(String className, Erasure erasure) {
            var printer = new Printer(erasure);
            printer.line(0, "import java.math.BigInteger;");
            printer.line(0, "import org.julclang.core.types.JulcList;");
            printer.line(0, "");
            printer.line(0, "public class " + className + " {");
            printer.line(1, "public static BigInteger m(BigInteger a, BigInteger b, JulcList<BigInteger> xs) {");
            printer.line(2, "BigInteger v0 = a;");
            printer.line(2, "BigInteger v1 = b;");
            if (!(erasure instanceof Erasure.Loops)) {
                switch (shape) {
                    case WHILE_ONCE -> printer.whileLoop(2, 1, first, false, List.of(), false);
                    case WHILE -> printer.whileLoop(2, 3, first, false, List.of(), false);
                    case FOR_EACH -> printer.forEach(2, first, false, List.of(), false);
                    case WHILE_THEN_FOR_EACH -> {
                        printer.whileLoop(2, 3, first, false, List.of(), false);
                        printer.forEach(2, second, true, List.of(), false);
                    }
                    case WHILE_AROUND_FOR_EACH -> printer.whileLoop(2, 3, first, false, second, true);
                    case FOR_EACH_AROUND_WHILE -> printer.forEach(2, first, false, second, true);
                }
            }
            printer.line(2, "return v0.add(v1.multiply(BigInteger.valueOf(3)));");
            printer.line(1, "}");
            printer.line(0, "}");
            return printer.toString();
        }

        @Override
        public String toString() {
            return shape + " " + first + (second.isEmpty() ? "" : " " + second);
        }
    }

    /** Every program of the bounded grammar, in a fixed order. */
    public static List<Program> all() {
        var programs = new ArrayList<Program>();
        var one = bodies(1);
        var oneOrTwo = new ArrayList<>(one);
        oneOrTwo.addAll(bodies(2));
        for (var body : one) programs.add(new Program(Shape.WHILE_ONCE, body, List.of()));
        for (var body : oneOrTwo) programs.add(new Program(Shape.WHILE, body, List.of()));
        for (var body : oneOrTwo) programs.add(new Program(Shape.FOR_EACH, body, List.of()));
        for (var outer : one) {
            for (var inner : one) {
                programs.add(new Program(Shape.WHILE_THEN_FOR_EACH, outer, inner));
                // A body that contains a loop cannot break.
                if (outer.contains(Statement.BREAK)) continue;
                programs.add(new Program(Shape.WHILE_AROUND_FOR_EACH, outer, inner));
                programs.add(new Program(Shape.FOR_EACH_AROUND_WHILE, outer, inner));
            }
        }
        return programs;
    }

    /** Every body of exactly {@code length} statements that updates an accumulator. */
    private static List<List<Statement>> bodies(int length) {
        var bodies = new ArrayList<List<Statement>>();
        bodies.add(List.of());
        for (int i = 0; i < length; i++) {
            var longer = new ArrayList<List<Statement>>();
            for (var body : bodies) {
                for (Statement s : Statement.values()) {
                    var next = new ArrayList<>(body);
                    next.add(s);
                    longer.add(next);
                }
            }
            bodies = longer;
        }
        return bodies.stream().filter(body -> body.stream().anyMatch(Statement::updates)).toList();
    }

    private static final class Printer {
        private final StringBuilder out = new StringBuilder();
        private final Erasure erasure;
        private int fresh;

        Printer(Erasure erasure) {
            this.erasure = erasure;
        }

        void whileLoop(int indent, int bound, List<Statement> body, boolean second, List<Statement> innerBody,
                       boolean nested) {
            // The counter starts at 1, so the loop variable is never 0 and every update changes something.
            String counter = "i" + fresh++;
            line(indent, "long " + counter + " = 1;");
            line(indent, "while (" + counter + " <= " + bound + ") {");
            body(indent + 1, body, second, "BigInteger.valueOf(" + counter + ")");
            if (nested) forEach(indent + 1, innerBody, true, List.of(), false);
            line(indent + 1, counter + " = " + counter + " + 1;");
            line(indent, "}");
        }

        void forEach(int indent, List<Statement> body, boolean second, List<Statement> innerBody, boolean nested) {
            String element = "x" + fresh++;
            line(indent, "for (var " + element + " : xs) {");
            body(indent + 1, body, second, element);
            if (nested) whileLoop(indent + 1, 3, innerBody, true, List.of(), false);
            line(indent, "}");
        }

        private void body(int indent, List<Statement> body, boolean second, String x) {
            for (int i = 0; i < body.size(); i++) {
                if (erasure instanceof Erasure.OneStatement(boolean inner, int index) && inner == second && index == i) continue;
                statement(indent, body.get(i), x);
            }
        }

        private void statement(int indent, Statement statement, String x) {
            switch (statement) {
                case ADD -> line(indent, "v0 = v0.add(" + x + ");");
                case SUBTRACT_OTHER -> line(indent, "v1 = v1.subtract(v0);");
                case IF_UPDATE -> line(indent, "if (" + x + ".compareTo(a) > 0) { v0 = v0.add(" + x + "); }");
                case IF_ELSE_UPDATE -> line(indent, "if (" + x + ".compareTo(b) < 0) { v0 = v0.add(BigInteger.valueOf(1)); }"
                        + " else { v1 = v1.add(" + x + "); }");
                case BREAK -> line(indent, "if (" + x + ".compareTo(b) > 0) { break; }");
                case LOCAL -> {
                    String local = "t" + fresh++;
                    line(indent, "BigInteger " + local + " = " + x + ".multiply(BigInteger.valueOf(2));");
                    line(indent, "v1 = v1.add(" + local + ");");
                }
                case TERNARY -> line(indent, "v0 = (" + x + ".compareTo(a) < 0 ? v0.subtract(" + x + ")"
                        + " : v0.add(BigInteger.valueOf(2)));");
                case AND_UPDATE -> line(indent, "if (" + x + ".compareTo(a) > 0 && " + x + ".compareTo(BigInteger.valueOf(4)) < 0)"
                        + " { v1 = v1.add(BigInteger.valueOf(1)); }");
                case OR_UPDATE -> line(indent, "if (" + x + ".compareTo(a) < 0 || " + x + ".compareTo(BigInteger.valueOf(2)) > 0)"
                        + " { v1 = v1.add(" + x + "); }");
            }
        }

        void line(int indent, String text) {
            out.append("    ".repeat(indent)).append(text).append('\n');
        }

        @Override
        public String toString() {
            return out.toString();
        }
    }
}
