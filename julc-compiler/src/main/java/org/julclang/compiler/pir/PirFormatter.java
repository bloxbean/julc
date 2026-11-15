package org.julclang.compiler.pir;

import org.julclang.compiler.resolve.LibraryMethodRegistry;
import org.julclang.core.Constant;
import org.julclang.core.text.UplcPrinter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

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
        return print(new Sub(term, false, 0));
    }

    /**
     * Format a PIR term with indented multi-line output.
     */
    public static String formatPretty(PirTerm term) {
        return print(new Sub(term, true, 0));
    }

    /**
     * Format a PIR type to a compact string.
     */
    public static String formatType(PirType type) {
        return LibraryMethodRegistry.pirTypeName(type);
    }

    /** A sub-term still to print, compact or indented at {@code indent}. */
    private record Sub(PirTerm term, boolean pretty, int indent) {}

    /**
     * Prints with a work list of text and sub-terms, not recursion (issue #327): each term is replaced by its pieces
     * in order, so the text comes out as a recursive printer would write it.
     */
    private static String print(Sub root) {
        var sb = new StringBuilder();
        var pending = new ArrayDeque<Object>();
        pending.push(root);
        while (!pending.isEmpty()) {
            var next = pending.pop();
            if (next instanceof String text) {
                sb.append(text);
                continue;
            }
            var sub = (Sub) next;
            var pieces = new ArrayList<Object>();
            if (sub.pretty()) formatTermPretty(sub.term(), pieces, sub.indent());
            else formatTerm(sub.term(), pieces);
            for (int i = pieces.size() - 1; i >= 0; i--) pending.push(pieces.get(i));
        }
        return sb.toString();
    }

    // --- Compact formatting ---

    private static void formatTerm(PirTerm term, List<Object> out) {
        switch (term) {
            case PirTerm.Var v -> out.add(v.name());
            case PirTerm.Let let -> {
                out.add("(let " + let.name() + " = ");
                out.add(compact(let.value()));
                out.add(" in ");
                out.add(compact(let.body()));
                out.add(")");
            }
            case PirTerm.LetRec lr -> {
                out.add("(letrec ");
                for (int i = 0; i < lr.bindings().size(); i++) {
                    if (i > 0) out.add("; ");
                    var b = lr.bindings().get(i);
                    out.add(b.name() + " = ");
                    out.add(compact(b.value()));
                }
                out.add(" in ");
                out.add(compact(lr.body()));
                out.add(")");
            }
            case PirTerm.Lam lam -> {
                out.add("(lam " + lam.param() + " : " + formatType(lam.paramType()) + " ");
                out.add(compact(lam.body()));
                out.add(")");
            }
            case PirTerm.App app -> {
                out.add("[");
                out.add(compact(app.function()));
                out.add(" ");
                out.add(compact(app.argument()));
                out.add("]");
            }
            case PirTerm.Const c -> out.add(constant(c));
            case PirTerm.Builtin b -> out.add("(builtin " + builtinName(b.fun()) + ")");
            case PirTerm.IfThenElse ite -> {
                out.add("(if ");
                out.add(compact(ite.cond()));
                out.add(" then ");
                out.add(compact(ite.thenBranch()));
                out.add(" else ");
                out.add(compact(ite.elseBranch()));
                out.add(")");
            }
            case PirTerm.DataConstr dc -> {
                out.add("(constr " + dc.tag());
                for (var field : dc.fields()) {
                    out.add(" ");
                    out.add(compact(field));
                }
                out.add(")");
            }
            case PirTerm.IntegerCase c -> integerCase(c, out);
            case PirTerm.PairMatch m -> pairMatch(m, out);
            case PirTerm.ListMatch m -> listMatch(m, out);
            case PirTerm.DataMatch dm -> {
                out.add("(match ");
                out.add(compact(dm.scrutinee()));
                for (var branch : dm.branches()) {
                    out.add(" [" + branch.constructorName() + bindings(branch) + " => ");
                    out.add(compact(branch.body()));
                    out.add("]");
                }
                out.add(")");
            }
            case PirTerm.Error e -> out.add("(error)");
            case PirTerm.Trace t -> {
                out.add("(trace ");
                out.add(compact(t.message()));
                out.add(" ");
                out.add(compact(t.body()));
                out.add(")");
            }
        }
    }

    // --- Pretty (indented) formatting ---

    private static void formatTermPretty(PirTerm term, List<Object> out, int indent) {
        switch (term) {
            case PirTerm.Var v -> out.add(v.name());
            case PirTerm.Let let -> {
                out.add("(let " + let.name() + " =\n" + spaces(indent + 2));
                out.add(pretty(let.value(), indent + 2));
                out.add("\n" + spaces(indent) + "in\n" + spaces(indent + 2));
                out.add(pretty(let.body(), indent + 2));
                out.add(")");
            }
            case PirTerm.LetRec lr -> {
                out.add("(letrec\n");
                for (int i = 0; i < lr.bindings().size(); i++) {
                    var b = lr.bindings().get(i);
                    out.add(spaces(indent + 2) + b.name() + " =\n" + spaces(indent + 4));
                    out.add(pretty(b.value(), indent + 4));
                    out.add((i < lr.bindings().size() - 1 ? ";" : "") + "\n");
                }
                out.add(spaces(indent) + "in\n" + spaces(indent + 2));
                out.add(pretty(lr.body(), indent + 2));
                out.add(")");
            }
            case PirTerm.Lam lam -> {
                out.add("(lam " + lam.param() + " : " + formatType(lam.paramType()) + "\n" + spaces(indent + 2));
                out.add(pretty(lam.body(), indent + 2));
                out.add(")");
            }
            case PirTerm.App app -> {
                out.add("[");
                out.add(pretty(app.function(), indent));
                out.add(" ");
                out.add(pretty(app.argument(), indent));
                out.add("]");
            }
            case PirTerm.Const c -> out.add(constant(c));
            case PirTerm.Builtin b -> out.add("(builtin " + builtinName(b.fun()) + ")");
            case PirTerm.IfThenElse ite -> {
                out.add("(if ");
                out.add(pretty(ite.cond(), indent + 2));
                out.add("\n" + spaces(indent + 2) + "then ");
                out.add(pretty(ite.thenBranch(), indent + 7));
                out.add("\n" + spaces(indent + 2) + "else ");
                out.add(pretty(ite.elseBranch(), indent + 7));
                out.add(")");
            }
            case PirTerm.DataConstr dc -> {
                out.add("(constr " + dc.tag());
                for (var field : dc.fields()) {
                    out.add(" ");
                    out.add(pretty(field, indent));
                }
                out.add(")");
            }
            // These print their parts compactly, as they always have.
            case PirTerm.IntegerCase c -> integerCase(c, out);
            case PirTerm.PairMatch m -> pairMatch(m, out);
            case PirTerm.ListMatch m -> listMatch(m, out);
            case PirTerm.DataMatch dm -> {
                out.add("(match ");
                out.add(pretty(dm.scrutinee(), indent + 2));
                for (var branch : dm.branches()) {
                    out.add("\n" + spaces(indent + 2) + "[" + branch.constructorName() + bindings(branch)
                            + " =>\n" + spaces(indent + 4));
                    out.add(pretty(branch.body(), indent + 4));
                    out.add("]");
                }
                out.add(")");
            }
            case PirTerm.Error e -> out.add("(error)");
            case PirTerm.Trace t -> {
                out.add("(trace ");
                out.add(pretty(t.message(), indent + 2));
                out.add("\n" + spaces(indent + 2));
                out.add(pretty(t.body(), indent + 2));
                out.add(")");
            }
        }
    }

    private static void integerCase(PirTerm.IntegerCase c, List<Object> out) {
        out.add("(integer-case ");
        out.add(compact(c.scrutinee()));
        out.add(" [");
        for (int i = 0; i < c.branches().size(); i++) {
            if (i > 0) out.add(", ");
            out.add(compact(c.branches().get(i)));
        }
        out.add("])");
    }

    private static void pairMatch(PirTerm.PairMatch m, List<Object> out) {
        out.add("(pair-match ");
        out.add(compact(m.scrutinee()));
        out.add(" [" + m.firstName() + ", " + m.secondName() + "] => ");
        out.add(compact(m.body()));
        out.add(")");
    }

    private static void listMatch(PirTerm.ListMatch m, List<Object> out) {
        out.add("(list-match ");
        out.add(compact(m.scrutinee()));
        out.add(" [] => ");
        out.add(compact(m.nilBranch()));
        out.add(" [" + m.headName() + " :: " + m.tailName() + "] => ");
        out.add(compact(m.consBranch()));
        out.add(")");
    }

    private static String bindings(PirTerm.MatchBranch branch) {
        var sb = new StringBuilder();
        for (var binding : branch.bindings()) sb.append(' ').append(binding);
        return sb.toString();
    }

    private static Sub compact(PirTerm term) {
        return new Sub(term, false, 0);
    }

    private static Sub pretty(PirTerm term, int indent) {
        return new Sub(term, true, indent);
    }

    private static String constant(PirTerm.Const c) {
        var sb = new StringBuilder("(con ");
        formatConstant(c.value(), sb);
        return sb.append(')').toString();
    }

    private static String spaces(int level) {
        var sb = new StringBuilder();
        indent(sb, level);
        return sb.toString();
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

    private static String builtinName(org.julclang.core.DefaultFun fun) {
        String name = fun.name();
        if (name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
