package com.bloxbean.cardano.julc.core.text;

import com.bloxbean.cardano.julc.core.*;

import java.math.BigInteger;
import java.util.HexFormat;

/**
 * Pretty-prints UPLC {@link Program}s and {@link Term}s to the standard
 * UPLC text format.
 * <p>
 * Format examples:
 * <pre>
 * (program 1.1.0 (lam x x))
 * (con integer 42)
 * [(builtin addInteger) (con integer 1) (con integer 2)]
 * </pre>
 */
public final class UplcPrinter {

    private static final HexFormat HEX = HexFormat.of();

    private UplcPrinter() {}

    /**
     * Print a Program to UPLC text format.
     */
    public static String print(Program program) {
        var sb = new StringBuilder();
        sb.append("(program ");
        sb.append(program.versionString());
        sb.append(' ');
        printTerm(program.term(), sb);
        sb.append(')');
        return sb.toString();
    }

    /**
     * Print a Term to UPLC text format.
     */
    public static String print(Term term) {
        var sb = new StringBuilder();
        printTerm(term, sb);
        return sb.toString();
    }

    private static void printTerm(Term term, StringBuilder sb) {
        switch (term) {
            case Term.Var v -> sb.append(sanitizeName(v.name().name()));
            case Term.Lam l -> {
                sb.append("(lam ");
                sb.append(sanitizeName(l.paramName()));
                sb.append(' ');
                printTerm(l.body(), sb);
                sb.append(')');
            }
            case Term.Apply a -> {
                sb.append('[');
                printTerm(a.function(), sb);
                sb.append(' ');
                printTerm(a.argument(), sb);
                sb.append(']');
            }
            case Term.Force f -> {
                sb.append("(force ");
                printTerm(f.term(), sb);
                sb.append(')');
            }
            case Term.Delay d -> {
                sb.append("(delay ");
                printTerm(d.term(), sb);
                sb.append(')');
            }
            case Term.Const c -> {
                sb.append("(con ");
                printConstant(c.value(), sb);
                sb.append(')');
            }
            case Term.Builtin b -> {
                sb.append("(builtin ");
                sb.append(builtinName(b.fun()));
                sb.append(')');
            }
            case Term.Error ignored -> sb.append("(error)");
            case Term.Constr c -> {
                sb.append("(constr ");
                sb.append(Long.toUnsignedString(c.tag()));
                for (var field : c.fields()) {
                    sb.append(' ');
                    printTerm(field, sb);
                }
                sb.append(')');
            }
            case Term.Case cs -> {
                sb.append("(case ");
                printTerm(cs.scrutinee(), sb);
                for (var branch : cs.branches()) {
                    sb.append(' ');
                    printTerm(branch, sb);
                }
                sb.append(')');
            }
        }
    }

    private static void printConstant(Constant constant, StringBuilder sb) {
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
                sb.append("string ");
                printQuotedString(s.value(), sb);
            }
            case Constant.UnitConst ignored -> sb.append("unit ()");
            case Constant.BoolConst b -> {
                sb.append("bool ");
                sb.append(b.value() ? "True" : "False");
            }
            case Constant.DataConst d -> {
                sb.append("data ");
                printPlutusData(d.value(), sb);
            }
            case Constant.ListConst l -> {
                printType(l.type(), sb);
                sb.append(' ');
                sb.append('[');
                for (int i = 0; i < l.values().size(); i++) {
                    if (i > 0) sb.append(", ");
                    printConstantValue(l.values().get(i), sb);
                }
                sb.append(']');
            }
            case Constant.PairConst p -> {
                printType(p.type(), sb);
                sb.append(' ');
                sb.append('(');
                printConstantValue(p.first(), sb);
                sb.append(", ");
                printConstantValue(p.second(), sb);
                sb.append(')');
            }
            case Constant.Bls12_381_G1Element g1 -> {
                sb.append("bls12_381_G1_element 0x");
                sb.append(HEX.formatHex(g1.value()));
            }
            case Constant.Bls12_381_G2Element g2 -> {
                sb.append("bls12_381_G2_element 0x");
                sb.append(HEX.formatHex(g2.value()));
            }
            case Constant.Bls12_381_MlResult ml -> {
                sb.append("bls12_381_mlresult 0x");
                sb.append(HEX.formatHex(ml.value()));
            }
            case Constant.ArrayConst ac -> {
                printType(ac.type(), sb);
                sb.append(' ');
                sb.append('[');
                for (int i = 0; i < ac.values().size(); i++) {
                    if (i > 0) sb.append(",");
                    printConstantValue(ac.values().get(i), sb);
                }
                sb.append(']');
            }
            case Constant.ValueConst vc -> {
                sb.append("value [");
                for (int i = 0; i < vc.entries().size(); i++) {
                    if (i > 0) sb.append(", ");
                    printValueEntry(vc.entries().get(i), sb);
                }
                sb.append(']');
            }
        }
    }

    /**
     * Print just the value portion of a constant (without the type prefix),
     * used for list elements and pair components.
     */
    private static void printConstantValue(Constant constant, StringBuilder sb) {
        switch (constant) {
            case Constant.IntegerConst i -> sb.append(i.value());
            case Constant.ByteStringConst bs -> {
                sb.append('#');
                sb.append(HEX.formatHex(bs.value()));
            }
            case Constant.StringConst s -> printQuotedString(s.value(), sb);
            case Constant.UnitConst ignored -> sb.append("()");
            case Constant.BoolConst b -> sb.append(b.value() ? "True" : "False");
            case Constant.DataConst d -> printPlutusData(d.value(), sb);
            case Constant.ListConst l -> {
                sb.append('[');
                for (int i = 0; i < l.values().size(); i++) {
                    if (i > 0) sb.append(", ");
                    printConstantValue(l.values().get(i), sb);
                }
                sb.append(']');
            }
            case Constant.PairConst p -> {
                sb.append('(');
                printConstantValue(p.first(), sb);
                sb.append(", ");
                printConstantValue(p.second(), sb);
                sb.append(')');
            }
            case Constant.Bls12_381_G1Element g1 -> {
                sb.append("0x");
                sb.append(HEX.formatHex(g1.value()));
            }
            case Constant.Bls12_381_G2Element g2 -> {
                sb.append("0x");
                sb.append(HEX.formatHex(g2.value()));
            }
            case Constant.Bls12_381_MlResult ml -> {
                sb.append("0x");
                sb.append(HEX.formatHex(ml.value()));
            }
            case Constant.ArrayConst ac -> {
                sb.append('[');
                for (int i = 0; i < ac.values().size(); i++) {
                    if (i > 0) sb.append(",");
                    printConstantValue(ac.values().get(i), sb);
                }
                sb.append(']');
            }
            case Constant.ValueConst vc -> {
                sb.append('[');
                for (int i = 0; i < vc.entries().size(); i++) {
                    if (i > 0) sb.append(", ");
                    printValueEntry(vc.entries().get(i), sb);
                }
                sb.append(']');
            }
        }
    }

    private static void printType(DefaultUni type, StringBuilder sb) {
        switch (type) {
            case DefaultUni.Integer ignored -> sb.append("integer");
            case DefaultUni.ByteString ignored -> sb.append("bytestring");
            case DefaultUni.String ignored -> sb.append("string");
            case DefaultUni.Unit ignored -> sb.append("unit");
            case DefaultUni.Bool ignored -> sb.append("bool");
            case DefaultUni.Data ignored -> sb.append("data");
            case DefaultUni.ProtoList ignored -> sb.append("list");
            case DefaultUni.ProtoPair ignored -> sb.append("pair");
            case DefaultUni.Bls12_381_G1_Element ignored -> sb.append("bls12_381_G1_element");
            case DefaultUni.Bls12_381_G2_Element ignored -> sb.append("bls12_381_G2_element");
            case DefaultUni.Bls12_381_MlResult ignored -> sb.append("bls12_381_mlresult");
            case DefaultUni.ProtoArray ignored -> sb.append("array");
            case DefaultUni.ProtoValue ignored -> sb.append("value");
            case DefaultUni.Apply a -> {
                // Special-case pair: Apply(Apply(ProtoPair, typeA), typeB) → (pair typeA typeB)
                if (a.f() instanceof DefaultUni.Apply inner
                        && inner.f() instanceof DefaultUni.ProtoPair) {
                    sb.append("(pair ");
                    printType(inner.arg(), sb);
                    sb.append(' ');
                    printType(a.arg(), sb);
                    sb.append(')');
                } else {
                    sb.append('(');
                    printType(a.f(), sb);
                    sb.append(' ');
                    printType(a.arg(), sb);
                    sb.append(')');
                }
            }
        }
    }

    private static void printValueEntry(Constant.ValueConst.ValueEntry entry, StringBuilder sb) {
        sb.append("(#");
        sb.append(HEX.formatHex(entry.policyId()));
        sb.append(", [");
        for (int i = 0; i < entry.tokens().size(); i++) {
            if (i > 0) sb.append(", ");
            var token = entry.tokens().get(i);
            sb.append("(#");
            sb.append(HEX.formatHex(token.tokenName()));
            sb.append(", ");
            sb.append(token.quantity());
            sb.append(')');
        }
        sb.append("])");
    }

    private static void printPlutusData(PlutusData data, StringBuilder sb) {
        switch (data) {
            case PlutusData.IntData i -> {
                sb.append("I ");
                sb.append(i.value());
            }
            case PlutusData.BytesData bs -> {
                sb.append("B #");
                sb.append(HEX.formatHex(bs.value()));
            }
            case PlutusData.ConstrData c -> {
                sb.append("Constr ");
                sb.append(c.tag());
                sb.append(" [");
                for (int i = 0; i < c.fields().size(); i++) {
                    if (i > 0) sb.append(", ");
                    printPlutusData(c.fields().get(i), sb);
                }
                sb.append(']');
            }
            case PlutusData.ListData l -> {
                sb.append("List [");
                for (int i = 0; i < l.items().size(); i++) {
                    if (i > 0) sb.append(", ");
                    printPlutusData(l.items().get(i), sb);
                }
                sb.append(']');
            }
            case PlutusData.MapData m -> {
                sb.append("Map [");
                for (int i = 0; i < m.entries().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append('(');
                    printPlutusData(m.entries().get(i).key(), sb);
                    sb.append(", ");
                    printPlutusData(m.entries().get(i).value(), sb);
                    sb.append(')');
                }
                sb.append(']');
            }
        }
    }

    /**
     * Print a string with proper escaping.
     */
    private static void printQuotedString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case 7    -> sb.append("\\a");   // bell
                case 11   -> sb.append("\\v");   // vertical tab
                default -> {
                    if (ch < 0x20 || ch == 0x7f) {
                        // Control character: use decimal escape
                        sb.append('\\');
                        sb.append((int) ch);
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Convert a DefaultFun enum value to its UPLC text name.
     * Lowercases the first character: AddInteger → addInteger.
     */
    static String builtinName(DefaultFun fun) {
        String name = fun.name();
        if (name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Sanitize a variable name for UPLC text output.
     * Replaces dots, dollar signs, colons with underscores.
     * Prefixes with underscore if starts with a digit.
     */
    static String sanitizeName(String name) {
        var sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '.' || ch == '$' || ch == ':') {
                sb.append('_');
            } else {
                sb.append(ch);
            }
        }
        String result = sb.toString();
        if (!result.isEmpty() && Character.isDigit(result.charAt(0))) {
            result = "_" + result;
        }
        return result;
    }
}
