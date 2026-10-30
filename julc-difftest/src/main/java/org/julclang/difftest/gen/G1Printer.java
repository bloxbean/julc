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
            line(1, "static final " + field.type().name + " " + field.name() + " = " + expression(field.init()) + ";");
        }
        out.append('\n').append(HELPERS);
        for (var helper : unit.helpers()) {
            out.append('\n');
            line(1, "static " + helper.result().name + " " + helper.name() + "(" + parameters(helper.parameters()) + ") {");
            line(2, "return " + expression(helper.body()) + ";");
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
            case G1Ast.Declare d -> line(indent, d.type().name + " " + d.name() + " = " + expression(d.init()) + ";");
            case G1Ast.Assign a -> line(indent, a.target() + " = " + expression(a.value()) + ";");
            case G1Ast.If i -> {
                line(indent, "if (" + expression(i.test()) + ") {");
                statements(i.then(), indent + 1);
                if (i.otherwise() != null) {
                    line(indent, "} else {");
                    statements(i.otherwise(), indent + 1);
                }
                line(indent, "}");
            }
            case G1Ast.BreakIf b -> line(indent, "if (" + expression(b.test()) + ") { break; }");
            case G1Ast.IfInstance i -> {
                line(indent, "if (" + i.subject().name() + " instanceof " + i.variant().name + " " + i.binding() + ") {");
                statements(i.body(), indent + 1);
                line(indent, "}");
            }
            case G1Ast.Return r -> line(indent, "return " + expression(r.value()) + ";");
            case G1Ast.While w -> {
                line(indent, "long " + w.counter() + " = 0;");
                line(indent, "while (" + w.counter() + " < " + w.bound() + ") {");
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

    static String expression(Expr expression) {
        return switch (expression) {
            case G1Ast.IntConst c -> "BigInteger.valueOf(" + c.value() + ")";
            case G1Ast.BoolConst c -> String.valueOf(c.value());
            case G1Ast.Read r -> read(r.var());
            case G1Ast.Arith a -> expression(a.left()) + "." + a.method() + "(" + expression(a.right()) + ")";
            case G1Ast.Scale s -> expression(s.left()) + ".multiply(BigInteger.valueOf(" + s.factor() + "))";
            case G1Ast.Cond c -> "(" + expression(c.test()) + " ? " + expression(c.then()) + " : " + expression(c.otherwise()) + ")";
            case G1Ast.Compare c -> expression(c.left()) + ".compareTo(" + expression(c.right()) + ") " + c.operator() + " 0";
            case G1Ast.Logic l -> "(" + expression(l.left()) + " " + l.operator() + " " + expression(l.right()) + ")";
            case G1Ast.Not n -> "!(" + expression(n.operand()) + ")";
            case G1Ast.Equals e -> expression(e.receiver()) + ".equals(" + expression(e.argument()) + ")";
            case G1Ast.New n -> "new " + n.record().name + "(" + arguments(n.arguments()) + ")";
            case G1Ast.Access a -> read(a.var()) + String.join("", a.components().stream().map(c -> "." + c + "()").toList());
            case G1Ast.Call c -> c.helper() + "(" + arguments(c.arguments()) + ")";
            case G1Ast.Trace t -> "t(\"" + t.label() + "\", " + expression(t.value()) + ")";
            case G1Ast.Fail f -> "fail(" + expression(f.value()) + ")";
        };
    }

    private static String arguments(List<Expr> arguments) {
        return String.join(", ", arguments.stream().map(G1Printer::expression).toList());
    }

    private static String read(Var var) {
        return var.counter() ? "BigInteger.valueOf(" + var.name() + ")" : var.name();
    }

    private void line(int indent, String text) {
        out.append("    ".repeat(indent)).append(text).append('\n');
    }
}
