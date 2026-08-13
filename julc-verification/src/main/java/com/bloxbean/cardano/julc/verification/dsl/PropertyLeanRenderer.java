package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

/** Deterministic, auditable Lean expression renderer for admitted DSL v1 nodes. */
public final class PropertyLeanRenderer {
    private PropertyLeanRenderer() { }

    public static String render(DslPropertySet properties) {
        var result = new StringBuilder();
        for (DslProperty property : properties.properties()) {
            result.append("def ").append(leanName(property.id())).append(" (ctx : ScriptContext) : Bool :=\n  ")
                    .append(renderNode(property.expression())).append("\n");
        }
        return result.toString();
    }

    private static String renderNode(PropertyNode node) {
        if (node instanceof RootNode root) {
            return switch (root.name()) {
                case "context" -> "ctx";
                case "exactUplcSucceeds" -> "exactUplcSucceeds ctx";
                case "validSpendingContext" -> "validSpendingContext ctx";
                case "datum" -> "strictDatum ctx";
                default -> root.name();
            };
        }
        if (node instanceof FieldNode field) {
            String target = renderNode(field.target());
            if (field.target().resultType() == DslType.DATA) {
                return target + "." + field.name();
            }
            return switch (field.target().resultType() + "." + field.name()) {
                case "SCRIPT_CONTEXT.txInfo" -> target + ".scriptContextTxInfo";
                case "TX_INFO.signatories" -> target + ".txInfoSignatories";
                case "TX_INFO.outputs" -> target + ".txInfoOutputs";
                case "TX_OUT.address" -> target + ".txOutAddress";
                case "TX_OUT.value" -> target + ".txOutValue";
                case "ADDRESS.credential" -> target + ".addressCredential";
                case "VALUE.lovelace" -> "lovelaceOf " + target;
                default -> throw new IllegalArgumentException("No Lean field mapping for " + field);
            };
        }
        if (node instanceof BoolBinaryNode binary) {
            String op = switch (binary.operator()) {
                case AND -> "&&";
                case OR -> "||";
                case IMPLIES -> "(!" + parenthesize(renderNode(binary.left())) + ") ||";
            };
            if (binary.operator() == BoolOperator.IMPLIES) {
                return parenthesize(op + " " + parenthesize(renderNode(binary.right())));
            }
            return parenthesize(renderNode(binary.left()) + " " + op + " "
                    + renderNode(binary.right()));
        }
        if (node instanceof ContainsNode contains) {
            return "List.elem " + renderNode(contains.value()) + " "
                    + renderNode(contains.collection());
        }
        if (node instanceof CompareNode comparison) {
            String op = switch (comparison.operator()) {
                case EQ -> "==";
                case NE -> "!=";
                case LT -> "<";
                case LE -> "<=";
                case GT -> ">";
                case GE -> ">=";
            };
            return parenthesize(renderNode(comparison.left()) + " " + op + " "
                    + renderNode(comparison.right()));
        }
        if (node instanceof CredentialKeyHashNode match) {
            return parenthesize(renderNode(match.credential())
                    + " == Credential.PubKeyCredential " + renderNode(match.keyHash()));
        }
        if (node instanceof ExistsNode exists) {
            return "List.any (fun " + exists.variable() + " => "
                    + renderNode(exists.predicate()) + ") " + renderNode(exists.collection());
        }
        if (node instanceof LiteralNode literal) return literal.value();
        throw new IllegalArgumentException("Unsupported node " + node);
    }

    private static String parenthesize(String value) { return "(" + value + ")"; }

    private static String leanName(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
