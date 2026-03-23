package com.bloxbean.cardano.julc.jrl.transpile;

import com.bloxbean.cardano.julc.jrl.ast.*;
import com.bloxbean.cardano.julc.jrl.check.BuiltinFunctionRegistry;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Translates JRL fact patterns and expressions into Java code strings.
 * <p>
 * Each fact pattern type maps to a specific julc stdlib call pattern.
 * This class is stateless and used by {@link JavaTranspiler}.
 */
public final class FactTranslator {

    private FactTranslator() {}

    // ── Expression translation ──────────────────────────────────

    /**
     * Translate a JRL expression to a Java expression string.
     */
    public static String translateExpr(Expression expr) {
        return switch (expr) {
            case Expression.BinaryExpr be -> {
                String left = translateExpr(be.left());
                String right = translateExpr(be.right());
                yield switch (be.op()) {
                    case EQ -> translateEquality(left, right);
                    case NEQ -> "!(" + translateEquality(left, right) + ")";
                    case GT -> left + ".compareTo(" + right + ") > 0";
                    case GTE -> left + ".compareTo(" + right + ") >= 0";
                    case LT -> left + ".compareTo(" + right + ") < 0";
                    case LTE -> left + ".compareTo(" + right + ") <= 0";
                    case ADD -> left + ".add(" + right + ")";
                    case SUB -> left + ".subtract(" + right + ")";
                    case MUL -> left + ".multiply(" + right + ")";
                    case AND -> left + " && " + right;
                    case OR -> left + " || " + right;
                };
            }
            case Expression.UnaryExpr ue -> switch (ue.op()) {
                    case NOT -> "!" + translateExpr(ue.operand());
                };
            case Expression.FieldAccessExpr fa ->
                    translateExpr(fa.target()) + "." + fa.field() + "()";
            case Expression.FunctionCallExpr fc ->
                    translateFunctionCall(fc);
            case Expression.VarRefExpr vr -> vr.name();
            case Expression.IdentRefExpr ir -> ir.name();
            case Expression.IntLiteralExpr il ->
                    "BigInteger.valueOf(" + il.value() + ")";
            case Expression.StringLiteralExpr sl ->
                    "\"" + sl.value() + "\"";
            case Expression.HexLiteralExpr hl ->
                    "new byte[]{" + hexToByteArrayLiteral(hl.value()) + "}";
            case Expression.BoolLiteralExpr bl ->
                    String.valueOf(bl.value());
            case Expression.OwnAddressExpr _ -> "ownAddress";
            case Expression.OwnPolicyIdExpr _ -> "ownPolicyId";
        };
    }

    private static String translateEquality(String left, String right) {
        return left + " == " + right;
    }

    private static String translateFunctionCall(Expression.FunctionCallExpr fc) {
        var sig = BuiltinFunctionRegistry.lookup(fc.name());
        if (sig.isPresent()) {
            String args = fc.args().stream()
                    .map(FactTranslator::translateExpr)
                    .collect(Collectors.joining(", "));
            return sig.get().javaCall().formatted(args);
        }
        String args = fc.args().stream()
                .map(FactTranslator::translateExpr)
                .collect(Collectors.joining(", "));
        return fc.name() + "(" + args + ")";
    }

    // ── Transaction pattern translation ─────────────────────────

    /**
     * Translate a Transaction fact pattern into a Java boolean expression.
     * FEE is handled as variable binding in JavaTranspiler — calling this method with FEE is a bug.
     */
    public static String translateTransaction(FactPattern.TransactionPattern tp) {
        String value = translateExpr(tp.value());
        return switch (tp.field()) {
            case SIGNED_BY ->
                    "ListsLib.contains(txInfo.signatories(), " + value + ")";
            case VALID_AFTER ->
                    "IntervalLib.finiteLowerBound(txInfo.validRange()).compareTo(" + value + ") >= 0";
            case VALID_BEFORE ->
                    "IntervalLib.finiteUpperBound(txInfo.validRange()).compareTo(" + value + ") <= 0";
            case FEE -> throw new IllegalStateException("FEE handled as binding, not condition");
        };
    }

    // ── Output pattern translation ──────────────────────────────

    /**
     * Translate an Output value constraint into a Java boolean expression.
     * Uses uniqueOutputAt to find the single output at the address, then checks its value.
     */
    public static String translateValueConstraint(ValueConstraint vc, String toExpr) {
        return switch (vc) {
            case ValueConstraint.MinADA ma ->
                    "OutputLib.lovelacePaidTo(txInfo.outputs(), " + toExpr + ").compareTo("
                            + translateExpr(ma.amount()) + ") >= 0";
            case ValueConstraint.Contains c ->
                    "ValuesLib.assetOf(OutputLib.uniqueOutputAt(txInfo.outputs(), " + toExpr + ").value(), "
                            + translateExpr(c.policy()) + ", "
                            + translateExpr(c.token()) + ").compareTo("
                            + translateExpr(c.amount()) + ") >= 0";
        };
    }

    /**
     * Translate a value constraint against a specific output list (for ContinuingOutput).
     * Checks the first continuing output's value directly — these outputs are already
     * filtered to own address, so re-filtering would be redundant.
     */
    public static String translateValueConstraintForOutputs(ValueConstraint vc, String outputsExpr) {
        return switch (vc) {
            case ValueConstraint.MinADA ma ->
                    "ValuesLib.lovelaceOf(" + outputsExpr + ".head().value()).compareTo("
                            + translateExpr(ma.amount()) + ") >= 0";
            case ValueConstraint.Contains c ->
                    "ValuesLib.assetOf(" + outputsExpr + ".head().value(), "
                            + translateExpr(c.policy()) + ", "
                            + translateExpr(c.token()) + ").compareTo("
                            + translateExpr(c.amount()) + ") >= 0";
        };
    }

    // ── Input token constraint translation ─────────────────────

    /**
     * Translate an Input token constraint into a Java boolean expression
     * that checks valueSpent for the required token/ADA amount.
     */
    public static String translateInputTokenConstraint(ValueConstraint vc) {
        return switch (vc) {
            case ValueConstraint.MinADA ma ->
                    "ValuesLib.lovelaceOf(ContextsLib.valueSpent(txInfo)).compareTo("
                            + translateExpr(ma.amount()) + ") >= 0";
            case ValueConstraint.Contains c ->
                    "ValuesLib.assetOf(ContextsLib.valueSpent(txInfo), "
                            + translateExpr(c.policy()) + ", "
                            + translateExpr(c.token()) + ").compareTo("
                            + translateExpr(c.amount()) + ") >= 0";
        };
    }

    // ── Datum constraint translation ────────────────────────────

    /**
     * Translate a datum constraint into Java field comparison expressions.
     * Returns a list of expressions to AND together.
     */
    public static List<String> translateDatumConstraint(DatumConstraint dc, String datumVarName) {
        return dc.fields().stream()
                .map(f -> datumVarName + "." + f.fieldName() + "() == " + translateExpr(f.value()))
                .toList();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static String hexToByteArrayLiteral(byte[] bytes) {
        var sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("(byte)0x").append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}
