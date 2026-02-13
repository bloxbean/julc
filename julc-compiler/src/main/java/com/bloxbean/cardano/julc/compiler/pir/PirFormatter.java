package com.bloxbean.cardano.julc.compiler.pir;

import com.bloxbean.cardano.julc.compiler.resolve.LibraryMethodRegistry;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.text.UplcPrinter;

import java.util.HexFormat;

/**
 * Pretty-prints PIR (Plutus Intermediate Representation) terms to a human-readable
 * text format, analogous to {@link UplcPrinter} for UPLC terms.
 * <p>
 * Format examples:
 * <pre>
 * (lam x : Integer (builtin addInteger))
 * (let y = (con 42) in y)
 * (if cond then else)
 * </pre>
 */
public final class PirFormatter {

    private static final HexFormat HEX = HexFormat.of();

    private PirFormatter() {}

    /**
     * Format a PIR term to a compact single-line string.
     */
    public static String format(PirTerm term) {
        var sb = new StringBuilder();
        formatTerm(term, sb);
        return sb.toString();
    }

    /**
     * Format a PIR term with indented multi-line output.
     */
    public static String formatPretty(PirTerm term) {
        var sb = new StringBuilder();
        formatTermPretty(term, sb, 0);
        return sb.toString();
    }

    /**
     * Format a PIR type to a compact string.
     */
    public static String formatType(PirType type) {
        return LibraryMethodRegistry.pirTypeName(type);
    }

    // --- Compact formatting ---

    private static void formatTerm(PirTerm term, StringBuilder sb) {
        switch (term) {
            case PirTerm.Var v -> sb.append(v.name());
            case PirTerm.Let let -> {
                sb.append("(let ").append(let.name()).append(" = ");
                formatTerm(let.value(), sb);
                sb.append(" in ");
                formatTerm(let.body(), sb);
                sb.append(')');
            }
            case PirTerm.LetRec lr -> {
                sb.append("(letrec ");
                for (int i = 0; i < lr.bindings().size(); i++) {
                    if (i > 0) sb.append("; ");
                    var b = lr.bindings().get(i);
                    sb.append(b.name()).append(" = ");
                    formatTerm(b.value(), sb);
                }
                sb.append(" in ");
                formatTerm(lr.body(), sb);
                sb.append(')');
            }
            case PirTerm.Lam lam -> {
                sb.append("(lam ").append(lam.param());
                sb.append(" : ").append(formatType(lam.paramType()));
                sb.append(' ');
                formatTerm(lam.body(), sb);
                sb.append(')');
            }
            case PirTerm.App app -> {
                sb.append('[');
                formatTerm(app.function(), sb);
                sb.append(' ');
                formatTerm(app.argument(), sb);
                sb.append(']');
            }
            case PirTerm.Const c -> {
                sb.append("(con ");
                formatConstant(c.value(), sb);
                sb.append(')');
            }
            case PirTerm.Builtin b -> {
                sb.append("(builtin ");
                sb.append(builtinName(b.fun()));
                sb.append(')');
            }
            case PirTerm.IfThenElse ite -> {
                sb.append("(if ");
                formatTerm(ite.cond(), sb);
                sb.append(" then ");
                formatTerm(ite.thenBranch(), sb);
                sb.append(" else ");
                formatTerm(ite.elseBranch(), sb);
                sb.append(')');
            }
            case PirTerm.DataConstr dc -> {
                sb.append("(constr ").append(dc.tag());
                for (var field : dc.fields()) {
                    sb.append(' ');
                    formatTerm(field, sb);
                }
                sb.append(')');
            }
            case PirTerm.DataMatch dm -> {
                sb.append("(match ");
                formatTerm(dm.scrutinee(), sb);
                for (var branch : dm.branches()) {
                    sb.append(" [").append(branch.constructorName());
                    for (var binding : branch.bindings()) {
                        sb.append(' ').append(binding);
                    }
                    sb.append(" => ");
                    formatTerm(branch.body(), sb);
                    sb.append(']');
                }
                sb.append(')');
            }
            case PirTerm.Error e -> sb.append("(error)");
            case PirTerm.Trace t -> {
                sb.append("(trace ");
                formatTerm(t.message(), sb);
                sb.append(' ');
                formatTerm(t.body(), sb);
                sb.append(')');
            }
        }
    }

    // --- Pretty (indented) formatting ---

    private static void formatTermPretty(PirTerm term, StringBuilder sb, int indent) {
        switch (term) {
            case PirTerm.Var v -> sb.append(v.name());
            case PirTerm.Let let -> {
                sb.append("(let ").append(let.name()).append(" =\n");
                indent(sb, indent + 2);
                formatTermPretty(let.value(), sb, indent + 2);
                sb.append('\n');
                indent(sb, indent);
                sb.append("in\n");
                indent(sb, indent + 2);
                formatTermPretty(let.body(), sb, indent + 2);
                sb.append(')');
            }
            case PirTerm.LetRec lr -> {
                sb.append("(letrec\n");
                for (int i = 0; i < lr.bindings().size(); i++) {
                    var b = lr.bindings().get(i);
                    indent(sb, indent + 2);
                    sb.append(b.name()).append(" =\n");
                    indent(sb, indent + 4);
                    formatTermPretty(b.value(), sb, indent + 4);
                    if (i < lr.bindings().size() - 1) sb.append(';');
                    sb.append('\n');
                }
                indent(sb, indent);
                sb.append("in\n");
                indent(sb, indent + 2);
                formatTermPretty(lr.body(), sb, indent + 2);
                sb.append(')');
            }
            case PirTerm.Lam lam -> {
                sb.append("(lam ").append(lam.param());
                sb.append(" : ").append(formatType(lam.paramType()));
                sb.append('\n');
                indent(sb, indent + 2);
                formatTermPretty(lam.body(), sb, indent + 2);
                sb.append(')');
            }
            case PirTerm.App app -> {
                sb.append('[');
                formatTermPretty(app.function(), sb, indent);
                sb.append(' ');
                formatTermPretty(app.argument(), sb, indent);
                sb.append(']');
            }
            case PirTerm.Const c -> {
                sb.append("(con ");
                formatConstant(c.value(), sb);
                sb.append(')');
            }
            case PirTerm.Builtin b -> {
                sb.append("(builtin ");
                sb.append(builtinName(b.fun()));
                sb.append(')');
            }
            case PirTerm.IfThenElse ite -> {
                sb.append("(if ");
                formatTermPretty(ite.cond(), sb, indent + 2);
                sb.append('\n');
                indent(sb, indent + 2);
                sb.append("then ");
                formatTermPretty(ite.thenBranch(), sb, indent + 7);
                sb.append('\n');
                indent(sb, indent + 2);
                sb.append("else ");
                formatTermPretty(ite.elseBranch(), sb, indent + 7);
                sb.append(')');
            }
            case PirTerm.DataConstr dc -> {
                sb.append("(constr ").append(dc.tag());
                for (var field : dc.fields()) {
                    sb.append(' ');
                    formatTermPretty(field, sb, indent);
                }
                sb.append(')');
            }
            case PirTerm.DataMatch dm -> {
                sb.append("(match ");
                formatTermPretty(dm.scrutinee(), sb, indent + 2);
                for (var branch : dm.branches()) {
                    sb.append('\n');
                    indent(sb, indent + 2);
                    sb.append('[').append(branch.constructorName());
                    for (var binding : branch.bindings()) {
                        sb.append(' ').append(binding);
                    }
                    sb.append(" =>\n");
                    indent(sb, indent + 4);
                    formatTermPretty(branch.body(), sb, indent + 4);
                    sb.append(']');
                }
                sb.append(')');
            }
            case PirTerm.Error e -> sb.append("(error)");
            case PirTerm.Trace t -> {
                sb.append("(trace ");
                formatTermPretty(t.message(), sb, indent + 2);
                sb.append('\n');
                indent(sb, indent + 2);
                formatTermPretty(t.body(), sb, indent + 2);
                sb.append(')');
            }
        }
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append(' ');
    }

    // --- Helpers ---

    private static void formatConstant(Constant constant, StringBuilder sb) {
        switch (constant) {
            case Constant.IntegerConst i -> {
                sb.append("integer ");
                sb.append(i.value());
            }
            case Constant.ByteStringConst bs -> {
                sb.append("bytestring #");
                sb.append(HEX.formatHex(bs.value()));
            }
            case Constant.StringConst s -> {
                sb.append("string \"");
                sb.append(s.value().replace("\\", "\\\\").replace("\"", "\\\""));
                sb.append('"');
            }
            case Constant.UnitConst _ -> sb.append("unit ()");
            case Constant.BoolConst b -> {
                sb.append("bool ");
                sb.append(b.value() ? "True" : "False");
            }
            case Constant.DataConst d -> {
                sb.append("data <...>");
            }
            case Constant.ListConst l -> {
                sb.append("list [...]");
            }
            case Constant.PairConst p -> {
                sb.append("pair (...)");
            }
            default -> sb.append("<constant>");
        }
    }

    private static String builtinName(com.bloxbean.cardano.julc.core.DefaultFun fun) {
        String name = fun.name();
        if (name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
