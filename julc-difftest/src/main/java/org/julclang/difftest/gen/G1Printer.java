package org.julclang.difftest.gen;

import org.julclang.difftest.gen.G1Ast.Expr;
import org.julclang.difftest.gen.G1Ast.Kind;
import org.julclang.difftest.gen.G1Ast.Stmt;
import org.julclang.difftest.gen.G1Ast.Type;
import org.julclang.difftest.gen.G1Ast.Unit;
import org.julclang.difftest.gen.G1Ast.Var;

import java.util.List;

/** Prints a {@link G1Ast} program as Java source (ADR-066 D2), four spaces per level. */
final class G1Printer {

    private static final String HELPERS = """
                static BigInteger t(String k, BigInteger v) {
                    return Builtins.unIData(Builtins.trace(k, Builtins.iData(v)));
                }

                static BigInteger fail(BigInteger z) {
                    return Builtins.unIData(Builtins.error());
                }
            """;

    private final StringBuilder out = new StringBuilder();

    private G1Printer() {
    }

    static String print(Unit unit) {
        var printer = new G1Printer();
        printer.unit(unit);
        return printer.out.toString();
    }

    private void unit(Unit unit) {
        out.append("import java.math.BigInteger;\n")
                .append("import org.julclang.core.types.JulcList;\n")
                .append("import org.julclang.stdlib.Builtins;\n\n")
                .append("public class ").append(unit.className()).append(" {\n");
        for (Type type : unit.declarations()) line(1, declaration(type));
        if (!unit.declarations().isEmpty()) out.append('\n');
        for (var field : unit.fields()) {
            line(1, "static final " + field.type().name + " " + field.name() + " = " + expression(field.init(), 1) + ";");
        }
        out.append('\n').append(HELPERS);
        for (var helper : unit.helpers()) {
            out.append('\n');
            line(1, "static " + helper.result().name + " " + helper.name() + "(" + parameters(helper.parameters()) + ") {");
            line(2, "return " + expression(helper.body(), 2) + ";");
            line(1, "}");
        }
        var method = unit.method();
        String extra = method.parameters().isEmpty() ? "" : ", " + parameters(method.parameters());
        out.append('\n');
        line(1, "public static " + method.result().name + " m(BigInteger a, BigInteger b, JulcList<BigInteger> xs" + extra
                + ") {");
        statements(method.body(), 2);
        line(1, "}");
        out.append("}\n");
    }

    private static String declaration(Type type) {
        if (type.kind == Kind.SEALED) {
            return "sealed interface " + type.name + " permits "
                    + String.join(", ", type.variants.stream().map(v -> v.name).toList()) + " {}";
        }
        var components = type.components.stream().map(c -> c.type().name + " " + c.name()).toList();
        return "record " + type.name + "(" + String.join(", ", components) + ")"
                + (type.family == null ? "" : " implements " + type.family.name) + " {}";
    }

    private static String parameters(List<Var> parameters) {
        return String.join(", ", parameters.stream().map(p -> p.type().name + " " + p.name()).toList());
    }

    private void statements(List<Stmt> statements, int indent) {
        for (Stmt statement : statements) statement(statement, indent);
    }

    private void statement(Stmt statement, int indent) {
        switch (statement) {
            case G1Ast.Declare d -> line(indent, d.type().name + " " + d.name() + " = " + expression(d.init(), indent) + ";");
            case G1Ast.Assign a -> line(indent, a.target() + " = " + expression(a.value(), indent) + ";");
            case G1Ast.If i -> {
                line(indent, "if (" + expression(i.test(), indent) + ") {");
                statements(i.then(), indent + 1);
                if (i.otherwise() != null) {
                    line(indent, "} else {");
                    statements(i.otherwise(), indent + 1);
                }
                line(indent, "}");
            }
            case G1Ast.BreakIf b -> line(indent, "if (" + expression(b.test(), indent) + ") { break; }");
            case G1Ast.IfInstance i -> {
                line(indent, "if (" + i.subject().name() + " instanceof " + i.variant().name + " " + i.binding() + ") {");
                statements(i.body(), indent + 1);
                line(indent, "}");
            }
            case G1Ast.PatternIf p -> {
                line(indent, "if (" + patternTest(p, indent) + ") {");
                patternBranches(p, indent);
            }
            case G1Ast.Break b -> line(indent, "break;");
            case G1Ast.Yield y -> line(indent, "yield " + expression(y.value(), indent) + ";");
            case G1Ast.Return r -> line(indent, "return " + expression(r.value(), indent) + ";");
            case G1Ast.While w -> {
                line(indent, "long " + w.counter() + " = 0;");
                String guard = w.guard() == null ? "" : " && " + expression(w.guard(), indent);
                line(indent, "while (" + w.counter() + " < " + w.bound() + guard + ") {");
                statements(w.body(), indent + 1);
                line(indent + 1, w.counter() + " = " + w.counter() + " + 1;");
                line(indent, "}");
            }
            case G1Ast.ForEach f -> {
                line(indent, "for (var " + f.element() + " : xs) {");
                statements(f.body(), indent + 1);
                line(indent, "}");
            }
        }
    }

    private static String patternTest(G1Ast.PatternIf p, int indent) {
        String test = expression(p.subject(), indent) + " instanceof " + p.variant().name + " " + p.binding();
        return p.parenthesized() ? "(" + test + ")" : test;
    }

    /** The branches of a {@link G1Ast.PatternIf} whose first line is printed, and its {@code else if} chain. */
    private void patternBranches(G1Ast.PatternIf p, int indent) {
        statements(p.then(), indent + 1);
        if (p.otherwise() != null && p.otherwise().size() == 1 && p.otherwise().getFirst() instanceof G1Ast.PatternIf next) {
            line(indent, "} else if (" + patternTest(next, indent) + ") {");
            patternBranches(next, indent);
            return;
        }
        if (p.otherwise() != null) {
            line(indent, "} else {");
            statements(p.otherwise(), indent + 1);
        }
        line(indent, "}");
    }

    static String expression(Expr expression) {
        return expression(expression, 0);
    }

    /** An expression printed at statement indent {@code indent}; a {@code switch} spans lines at that indent. */
    static String expression(Expr expression, int indent) {
        return switch (expression) {
            case G1Ast.IntConst c -> "BigInteger.valueOf(" + c.value() + ")";
            case G1Ast.BoolConst c -> String.valueOf(c.value());
            case G1Ast.Read r -> read(r.var());
            case G1Ast.Arith a -> receiver(a.left(), indent) + "." + a.method() + "(" + expression(a.right(), indent) + ")";
            case G1Ast.Scale s -> receiver(s.left(), indent) + ".multiply(BigInteger.valueOf(" + s.factor() + "))";
            case G1Ast.Cond c -> "(" + expression(c.test(), indent) + " ? " + expression(c.then(), indent) + " : " + expression(c.otherwise(), indent) + ")";
            case G1Ast.Compare c -> receiver(c.left(), indent) + ".compareTo(" + expression(c.right(), indent) + ") " + c.operator() + " 0";
            case G1Ast.Logic l -> "(" + expression(l.left(), indent) + " " + l.operator() + " " + expression(l.right(), indent) + ")";
            case G1Ast.Not n -> "!(" + expression(n.operand(), indent) + ")";
            case G1Ast.Equals e -> receiver(e.receiver(), indent) + ".equals(" + expression(e.argument(), indent) + ")";
            case G1Ast.New n -> "new " + n.record().name + "(" + arguments(n.arguments(), indent) + ")";
            case G1Ast.Access a -> read(a.var()) + String.join("", a.components().stream().map(c -> "." + c + "()").toList());
            case G1Ast.Call c -> c.helper() + "(" + arguments(c.arguments(), indent) + ")";
            case G1Ast.Trace t -> "t(\"" + t.label() + "\", " + expression(t.value(), indent) + ")";
            case G1Ast.Fail f -> "fail(" + expression(f.value(), indent) + ")";
            case G1Ast.Switch w -> switchExpression(w, indent);
            case G1Ast.TypeTest t -> expression(t.subject(), indent) + " instanceof " + t.variant().name;
            case G1Ast.PatternTest t -> expression(t.subject(), indent) + " instanceof " + t.variant().name + " " + t.binding();
            case G1Ast.Paren p -> "(" + expression(p.operand(), indent) + ")";
            case G1Ast.Cast c -> "(" + c.target().name + ") " + expression(c.operand(), indent);
            case G1Ast.Lambda l -> "xs." + l.method() + "(" + l.parameter() + " -> " + expression(l.body(), indent) + ")";
            case G1Ast.LambdaBlock l -> "xs." + l.method() + "(" + l.parameter() + " -> { " + inline(l.body()) + "})";
        };
    }

    /** Statements on one line, as a lambda block's: declarations, ifs and returns, with no switch in them. */
    private static String inline(List<Stmt> statements) {
        var text = new StringBuilder();
        for (Stmt statement : statements) {
            switch (statement) {
                case G1Ast.Declare d -> text.append(d.type().name).append(' ').append(d.name()).append(" = ")
                        .append(expression(d.init())).append("; ");
                case G1Ast.Return r -> text.append("return ").append(expression(r.value())).append("; ");
                case G1Ast.If i -> {
                    text.append("if (").append(expression(i.test())).append(") { ").append(inline(i.then())).append('}');
                    if (i.otherwise() != null) text.append(" else { ").append(inline(i.otherwise())).append('}');
                    text.append(' ');
                }
                default -> throw new IllegalArgumentException("not in a lambda block: " + statement);
            }
        }
        return text.toString();
    }

    /** A method call's receiver: a switch expression there needs parentheses. */
    private static String receiver(Expr expression, int indent) {
        String text = expression(expression, indent);
        return expression instanceof G1Ast.Switch ? "(" + text + ")" : text;
    }

    private static String arguments(List<Expr> arguments, int indent) {
        return String.join(", ", arguments.stream().map(a -> expression(a, indent)).toList());
    }

    private static String switchExpression(G1Ast.Switch w, int indent) {
        var printer = new G1Printer();
        printer.out.append("switch (").append(expression(w.subject(), indent)).append(") {\n");
        for (var arm : w.arms()) {
            String label = arm.variant() == null ? "default" : "case " + arm.variant().name + " " + arm.binding();
            if (arm.body().isEmpty()) {
                printer.line(indent + 1, label + " -> " + expression(arm.value(), indent + 1) + ";");
            } else {
                printer.line(indent + 1, label + " -> {");
                printer.statements(arm.body(), indent + 2);
                printer.line(indent + 2, "yield " + expression(arm.value(), indent + 2) + ";");
                printer.line(indent + 1, "}");
            }
        }
        printer.out.append("    ".repeat(indent)).append('}');
        return printer.out.toString();
    }

    private static String read(Var var) {
        return var.counter() ? "BigInteger.valueOf(" + var.name() + ")" : var.name();
    }

    private void line(int indent, String text) {
        out.append("    ".repeat(indent)).append(text).append('\n');
    }
}
