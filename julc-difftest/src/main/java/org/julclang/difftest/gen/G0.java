package org.julclang.difftest.gen;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The first program grammar of ADR-063 (D5, G0): one static method
 * {@code m(BigInteger a, BigInteger b, JulcList<BigInteger> xs)} returning {@code BigInteger}, with
 * method-level declarations, {@code while} loops with a bounded counter, for-each loops over
 * {@code xs}, nesting up to two loops deep, accumulator updates (also inside {@code if}/{@code else}),
 * {@code if (...) break;}, loop-body locals, ternaries, {@code &&} and {@code ||}.
 * <p>
 * The grammar stays inside the subset JuLC documents as supported and inside the semantic
 * intersection of ADR-062:
 * <ul>
 *   <li>Data values are {@code BigInteger}, so nothing overflows (T6); {@code ==} is used only on
 *       {@code int} results of {@code compareTo} (T3–T5); there is no division (T11).</li>
 *   <li>Variables are assigned only inside loops, and only if they were declared at method level
 *       before the loop (ADR-048). There is no {@code continue}, and no {@code return} inside a
 *       loop.</li>
 *   <li>Every loop terminates: a {@code while} counts up to a bound of at most 3, and a for-each
 *       visits {@code xs} once.</li>
 *   <li>Names are unique, so nothing shadows; shadowing belongs to a later grammar.</li>
 * </ul>
 */
public final class G0 {

    /** Interactions a program exercises, for coverage statistics (ADR-063 D9). */
    public enum Feature {
        WHILE, FOR_EACH, NESTED_LOOP, IF_IN_LOOP, IF_ELSE_IN_LOOP, BREAK, BODY_LOCAL, TERNARY,
        SHORT_CIRCUIT, ACCUMULATOR_UPDATED, SEVERAL_ACCUMULATORS,
        /** A while body with a break and an if that assigns: the shape of ADR-062 T21. */
        WHILE_BREAK_WITH_IF_UPDATE,
        /** A for-each body with a break and an if/else whose branches assign different variables: ADR-062 T23. */
        FOR_EACH_BREAK_WITH_SPLIT_IF_ELSE,
        /**
         * A ternary inside {@link G0#DEEP_COMPARISON_NESTING} nested {@code compareTo} operands. The lowering
         * repeats each operand (#146), so the script doubles per level and can exhaust the budget (ADR-062 T13).
         */
        DEEP_COMPARISON;

        /**
         * Shapes of known divergences; they have no coverage quota. The registry's rules match on
         * {@code WHILE_BREAK_WITH_IF_UPDATE} (T21) and {@code DEEP_COMPARISON} (T13); T23 is explained only
         * by exact pins, so {@code FOR_EACH_BREAK_WITH_SPLIT_IF_ELSE} is statistics only.
         */
        public static final Set<Feature> REGISTRY_MARKERS =
                EnumSet.of(WHILE_BREAK_WITH_IF_UPDATE, FOR_EACH_BREAK_WITH_SPLIT_IF_ELSE, DEEP_COMPARISON);
    }

    /**
     * The decoding's version, saved with every failure it finds (ADR-063 D8). Change it whenever a
     * choice sequence would decode to a different program.
     */
    public static final String VERSION = "G0 r1";

    /** A decoded program: the source of class {@code Probe} and the features it uses. */
    public record Program(String source, Set<Feature> features) {
    }

    private static final int MAX_LOOP_DEPTH = 2;
    /** Nesting at which #146's duplication alone multiplies the operand 256 times. */
    public static final int DEEP_COMPARISON_NESTING = 8;

    private final Choices choices;
    private final StringBuilder out = new StringBuilder();
    private final Set<Feature> features = EnumSet.noneOf(Feature.class);
    private int fresh;
    /** How many {@code compareTo} operands enclose the expression being generated. */
    private int comparisonOperands;

    private G0(Choices choices) {
        this.choices = choices;
    }

    public static Program decode(List<Integer> plan) {
        var generator = new G0(new Choices(plan));
        return new Program(generator.method(), generator.features);
    }

    /** One variable in scope: {@code BigInteger} unless it is a loop counter, which is {@code long}. */
    private record Var(String name, boolean counter) {
        String asBigInteger() {
            return counter ? "BigInteger.valueOf(" + name + ")" : name;
        }
    }

    /** What a statement may read and which variables it may assign. */
    private record Scope(List<Var> readable, List<String> assignable, int loopDepth) {
        Scope with(Var var) {
            var vars = new ArrayList<>(readable);
            vars.add(var);
            return new Scope(vars, assignable, loopDepth);
        }

        Scope inLoop() {
            return new Scope(readable, assignable, loopDepth + 1);
        }
    }

    private String method() {
        out.append("import java.math.BigInteger;\n")
                .append("import org.julclang.core.types.JulcList;\n\n")
                .append("public class Probe {\n")
                .append("    public static BigInteger m(BigInteger a, BigInteger b, JulcList<BigInteger> xs) {\n");
        var scope = new Scope(new ArrayList<>(List.of(new Var("a", false), new Var("b", false))), new ArrayList<>(), 0);
        var updated = new ArrayList<String>();
        int statements = choices.below(5);
        for (int i = 0; i < statements; i++) {
            int kind = choices.below(3);
            // A loop needs a method-level variable declared before it to accumulate into.
            if (kind == 0 || scope.assignable().isEmpty()) scope = declaration(scope, 2, true);
            // Usually give a loop a second variable, so bodies can update several accumulators.
            if (kind != 0 && scope.assignable().size() < 2 && !choices.oneIn(3)) scope = declaration(scope, 2, true);
            if (kind != 0) loop(scope, 2, updated, kind == 2);
        }
        if (!updated.isEmpty()) features.add(Feature.ACCUMULATOR_UPDATED);
        line(2, "return " + expression(scope, 2) + ";");
        out.append("    }\n}\n");
        return out.toString();
    }

    /** {@code BigInteger v = e;}; a method-level variable can later be assigned inside loops. */
    private Scope declaration(Scope scope, int indent, boolean methodLevel) {
        String name = (methodLevel ? "v" : "t") + fresh++;
        line(indent, "BigInteger " + name + " = " + expression(scope, 2) + ";");
        var next = scope.with(new Var(name, false));
        if (methodLevel) next.assignable().add(name);
        return next;
    }

    /** A while loop with its counter, or a for-each over {@code xs}. */
    private void loop(Scope scope, int indent, List<String> updated, boolean forEach) {
        if (scope.loopDepth() > 0) features.add(Feature.NESTED_LOOP);
        Scope body = scope.inLoop();
        if (forEach) {
            features.add(Feature.FOR_EACH);
            String element = "x" + fresh++;
            line(indent, "for (var " + element + " : xs) {");
            body(body.with(new Var(element, false)), indent + 1, updated, null);
        } else {
            features.add(Feature.WHILE);
            String counter = "i" + fresh++;
            int bound = choices.below(4);
            line(indent, "long " + counter + " = 0;");
            line(indent, "while (" + counter + " < " + bound + ") {");
            body(body.with(new Var(counter, true)), indent + 1, updated, counter);
        }
        line(indent, "}");
    }

    private void body(Scope scope, int indent, List<String> updated, String counter) {
        int statements = 1 + choices.below(3);
        var assignedHere = new ArrayList<String>();
        boolean hasBreak = false;
        boolean hasIfUpdate = false;
        boolean hasSplitIfElse = false;
        // JuLC's break-aware loop bodies cannot contain loops (saved case
        // reject-nested-loop-in-break-loop), so a body either may break or may nest a loop.
        boolean breakAware = choices.oneIn(3);
        for (int i = 0; i < statements; i++) {
            int kind = choices.below(6);
            if (kind == 3 && !breakAware) kind = 0;
            if (kind == 5 && breakAware) kind = 1;
            switch (kind) {
                case 0, 1 -> assignment(scope, indent, assignedHere);
                case 2 -> {
                    features.add(Feature.IF_IN_LOOP);
                    hasIfUpdate = true;
                    line(indent, "if (" + condition(scope, 2) + ") {");
                    int before = assignedHere.size();
                    assignment(scope, indent + 1, assignedHere);
                    var thenTargets = List.copyOf(assignedHere.subList(before, assignedHere.size()));
                    if (choices.oneIn(2)) {
                        features.add(Feature.IF_ELSE_IN_LOOP);
                        line(indent, "} else {");
                        int elseStart = assignedHere.size();
                        assignment(scope, indent + 1, assignedHere);
                        var elseTargets = assignedHere.subList(elseStart, assignedHere.size());
                        if (!thenTargets.equals(elseTargets)) hasSplitIfElse = true;
                    }
                    line(indent, "}");
                }
                case 3 -> {
                    features.add(Feature.BREAK);
                    hasBreak = true;
                    line(indent, "if (" + condition(scope, 2) + ") { break; }");
                }
                case 4 -> {
                    features.add(Feature.BODY_LOCAL);
                    scope = declaration(scope, indent, false);
                }
                default -> {
                    if (scope.loopDepth() < MAX_LOOP_DEPTH) loop(scope, indent, updated, choices.oneIn(2));
                    else assignment(scope, indent, assignedHere);
                }
            }
        }
        // JuLC compiles the body of a loop that updates no earlier variable as plain statements, where
        // break is rejected (saved case reject-break-in-for-each-without-accumulator). A while loop
        // always updates its counter; give a for-each with a break an update too.
        if (counter == null && assignedHere.isEmpty() && hasBreak) assignment(scope, indent, assignedHere);
        if (assignedHere.stream().distinct().count() > 1) features.add(Feature.SEVERAL_ACCUMULATORS);
        if (counter != null && hasBreak && hasIfUpdate) features.add(Feature.WHILE_BREAK_WITH_IF_UPDATE);
        if (counter == null && hasBreak && hasSplitIfElse) features.add(Feature.FOR_EACH_BREAK_WITH_SPLIT_IF_ELSE);
        updated.addAll(assignedHere);
        if (counter != null) line(indent, counter + " = " + counter + " + 1;");
    }

    private void assignment(Scope scope, int indent, List<String> assigned) {
        if (scope.assignable().isEmpty()) {
            line(indent, "BigInteger t" + fresh++ + " = " + expression(scope, 1) + ";");
            return;
        }
        String target = scope.assignable().get(choices.below(scope.assignable().size()));
        assigned.add(target);
        line(indent, target + " = " + expression(scope, 2) + ";");
    }

    private String expression(Scope scope, int depth) {
        if (depth == 0 || choices.below(3) == 0) return atom(scope);
        return switch (choices.below(5)) {
            case 0 -> expression(scope, depth - 1) + ".add(" + expression(scope, depth - 1) + ")";
            case 1 -> expression(scope, depth - 1) + ".subtract(" + expression(scope, depth - 1) + ")";
            case 2 -> expression(scope, depth - 1) + ".multiply(BigInteger.valueOf(" + (1 + choices.below(3)) + "))";
            case 3 -> {
                features.add(Feature.TERNARY);
                if (comparisonOperands >= DEEP_COMPARISON_NESTING) features.add(Feature.DEEP_COMPARISON);
                yield "(" + condition(scope, 1) + " ? " + expression(scope, depth - 1) + " : " + expression(scope, depth - 1) + ")";
            }
            default -> atom(scope);
        };
    }

    private String atom(Scope scope) {
        if (scope.readable().isEmpty() || choices.below(3) == 0) {
            return "BigInteger.valueOf(" + (choices.below(11) - 5) + ")";
        }
        return scope.readable().get(choices.below(scope.readable().size())).asBigInteger();
    }

    private static final String[] COMPARISONS = {"<", "<=", ">", ">=", "==", "!="};

    private String condition(Scope scope, int depth) {
        if (depth > 0 && choices.oneIn(4)) {
            features.add(Feature.SHORT_CIRCUIT);
            String operator = choices.oneIn(2) ? " || " : " && ";
            return "(" + condition(scope, depth - 1) + operator + condition(scope, depth - 1) + ")";
        }
        comparisonOperands++;
        String left = expression(scope, 1);
        String right = expression(scope, 1);
        comparisonOperands--;
        return left + ".compareTo(" + right + ") " + COMPARISONS[choices.below(COMPARISONS.length)] + " 0";
    }

    private void line(int indent, String text) {
        out.append("    ".repeat(indent)).append(text).append('\n');
    }
}
