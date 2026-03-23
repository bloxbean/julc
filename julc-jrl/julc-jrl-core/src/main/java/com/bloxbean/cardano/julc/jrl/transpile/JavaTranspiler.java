package com.bloxbean.cardano.julc.jrl.transpile;

import com.bloxbean.cardano.julc.jrl.ast.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Transpiles a validated JRL AST into julc-compatible Java source code.
 * <p>
 * The generated Java uses julc annotations ({@code @SpendingValidator}, {@code @Entrypoint}, etc.)
 * and stdlib calls ({@code ListsLib}, {@code IntervalLib}, etc.) so it can be fed directly
 * into {@code JulcCompiler.compile()}.
 */
public final class JavaTranspiler {

    private final StringBuilder sb = new StringBuilder();
    private int indent = 0;
    private int varCounter = 0;

    private JavaTranspiler() {}

    /**
     * Transpile a JRL contract AST to a Java source string.
     */
    public static String transpile(ContractNode contract) {
        var transpiler = new JavaTranspiler();
        transpiler.generate(contract);
        return transpiler.sb.toString();
    }

    private void generate(ContractNode contract) {
        emitImports();
        emitLine();

        if (contract.isMultiValidator()) {
            emitLine("@MultiValidator");
        } else {
            emitLine("@" + purposeAnnotation(contract.purpose()));
        }
        emitLine("public class " + sanitizeClassName(contract.name()) + " {");
        indent++;

        emitParams(contract.params());
        emitRecordTypes(contract);
        emitLine();

        if (contract.isMultiValidator()) {
            for (var section : contract.purposeSections()) {
                emitEntrypointForSection(section, contract);
                emitLine();
            }
        } else {
            emitEntrypoint(contract.purpose(), contract.rules(), contract.defaultAction(),
                    contract.datum(), contract.redeemer());
        }

        indent--;
        emitLine("}");
    }

    // ── Imports ─────────────────────────────────────────────────

    private void emitImports() {
        emitLine("import com.bloxbean.cardano.julc.stdlib.annotation.*;");
        emitLine("import com.bloxbean.cardano.julc.ledger.*;");
        emitLine("import com.bloxbean.cardano.julc.stdlib.lib.*;");
        emitLine("import com.bloxbean.cardano.julc.stdlib.Builtins;");
        emitLine("import com.bloxbean.cardano.julc.core.PlutusData;");
        emitLine("import java.math.BigInteger;");
        emitLine("import java.util.Optional;");
    }

    // ── Params ──────────────────────────────────────────────────

    private void emitParams(List<ParamNode> params) {
        for (var param : params) {
            emitLine("@Param static " + TypeMapper.toJavaType(param.type())
                    + " " + param.name() + ";");
        }
    }

    // ── Record types (datum, redeemer, helper records) ───────────

    private void emitRecordTypes(ContractNode contract) {
        // Datum
        if (contract.datum() != null) {
            emitLine();
            emitRecord(contract.datum().name(), contract.datum().fields());
        }

        // Helper records
        for (var rec : contract.records()) {
            emitLine();
            emitRecord(rec.name(), rec.fields());
        }

        // Top-level redeemer
        emitRedeemerType(contract.redeemer());

        // Purpose section redeemers
        for (var section : contract.purposeSections()) {
            emitRedeemerType(section.redeemer());
        }
    }

    private void emitRecord(String name, List<FieldNode> fields) {
        var fieldStr = fields.stream()
                .map(f -> TypeMapper.toJavaType(f.type()) + " " + f.name())
                .reduce((a, b) -> a + ", " + b).orElse("");
        emitLine("record " + name + "(" + fieldStr + ") {}");
    }

    private void emitRedeemerType(RedeemerDeclNode redeemer) {
        if (redeemer == null) return;
        emitLine();
        if (redeemer.isVariantStyle()) {
            // julc sealed interfaces: permits clause lists variants, no extends
            var variantNames = redeemer.variants().stream()
                    .map(VariantNode::name)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            emitLine("sealed interface " + redeemer.name() + " permits " + variantNames + " {}");
            for (var variant : redeemer.variants()) {
                var fieldStr = variant.fields().stream()
                        .map(f -> TypeMapper.toJavaType(f.type()) + " " + f.name())
                        .reduce((a, b) -> a + ", " + b).orElse("");
                emitLine("record " + variant.name() + "(" + fieldStr
                        + ") implements " + redeemer.name() + " {}");
            }
        } else {
            emitRecord(redeemer.name(), redeemer.fields());
        }
    }

    // ── Entrypoint methods ──────────────────────────────────────

    private void emitEntrypoint(PurposeType purpose, List<RuleNode> rules,
                                DefaultAction defaultAction, DatumDeclNode datum,
                                RedeemerDeclNode redeemer) {
        emitLine("@Entrypoint");
        if (purpose == PurposeType.SPENDING) {
            emitLine("public static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {");
        } else {
            emitLine("public static boolean validate(PlutusData redeemer, ScriptContext ctx) {");
        }
        indent++;
        emitLine("var txInfo = ctx.txInfo();");
        emitLine();

        emitRuleBlocks(rules, datum, redeemer);

        // Default
        emitLine("// default: " + defaultAction.name().toLowerCase());
        emitLine("return " + (defaultAction == DefaultAction.ALLOW) + ";");

        indent--;
        emitLine("}");
    }

    private void emitEntrypointForSection(PurposeSectionNode section, ContractNode contract) {
        String methodName = "handle" + capitalize(section.purpose().name().toLowerCase());
        String purposeEnum = switch (section.purpose()) {
            case SPENDING -> "Purpose.SPEND";
            case MINTING -> "Purpose.MINT";
            case WITHDRAW -> "Purpose.WITHDRAW";
            case CERTIFYING -> "Purpose.CERTIFY";
            case VOTING -> "Purpose.VOTE";
            case PROPOSING -> "Purpose.PROPOSE";
        };

        emitLine("@Entrypoint(purpose = " + purposeEnum + ")");
        if (section.purpose() == PurposeType.SPENDING) {
            emitLine("public static boolean " + methodName
                    + "(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {");
        } else {
            emitLine("public static boolean " + methodName
                    + "(PlutusData redeemer, ScriptContext ctx) {");
        }
        indent++;
        emitLine("var txInfo = ctx.txInfo();");
        emitLine();

        emitRuleBlocks(section.rules(), contract.datum(), section.redeemer());

        emitLine("// default: " + section.defaultAction().name().toLowerCase());
        emitLine("return " + (section.defaultAction() == DefaultAction.ALLOW) + ";");

        indent--;
        emitLine("}");
    }

    // ── Rule blocks ─────────────────────────────────────────────

    private void emitRuleBlocks(List<RuleNode> rules, DatumDeclNode datum,
                                RedeemerDeclNode redeemer) {
        for (var rule : rules) {
            emitLine("// Rule: \"" + rule.name() + "\"");
            emitRuleBlock(rule, datum, redeemer);
            emitLine();
        }
    }

    private void emitRuleBlock(RuleNode rule, DatumDeclNode datum,
                               RedeemerDeclNode redeemer) {
        var conditionExprs = new ArrayList<String>();
        FactPattern.RedeemerPattern variantRedeemerPattern = null;

        for (var pattern : rule.patterns()) {
            switch (pattern) {
                case FactPattern.DatumPattern dp ->
                        emitCastAndExtract(dp.match(), "datum");
                case FactPattern.RedeemerPattern rp -> {
                    if (redeemer != null && redeemer.isVariantStyle()) {
                        variantRedeemerPattern = rp;
                    } else {
                        emitCastAndExtract(rp.match(), "redeemer");
                    }
                }
                case FactPattern.TransactionPattern tp -> {
                    if (tp.field() == FactPattern.TxField.FEE) {
                        // Fee binding: var $name = txInfo.fee();
                        String varName = FactTranslator.translateExpr(tp.value());
                        emitLine("var " + varName + " = txInfo.fee();");
                    } else {
                        conditionExprs.add(FactTranslator.translateTransaction(tp));
                    }
                }
                case FactPattern.ConditionPattern cp ->
                        conditionExprs.add(FactTranslator.translateExpr(cp.condition()));
                case FactPattern.OutputPattern op ->
                        emitOutputConditions(op, conditionExprs);
                case FactPattern.InputPattern ip ->
                        emitInputPattern(ip, conditionExprs);
                case FactPattern.MintPattern mp ->
                        emitMintPattern(mp, conditionExprs);
                case FactPattern.ContinuingOutputPattern cop ->
                        emitContinuingOutputPattern(cop, conditionExprs);
            }
        }

        if (variantRedeemerPattern != null) {
            // Variant redeemer: tag check guards field extraction inside the if body.
            // The body always returns (either the conjunction of conditions, or the action
            // directly). This ensures thenBranchReturns()=true, so the JuLC compiler can
            // use remaining statements as the else branch — avoiding the unit() type
            // mismatch that occurs with else-less nested ifs.
            int tag = findVariantTag(variantRedeemerPattern.match(), redeemer);
            emitLine("if (Builtins.constrTag(redeemer) == " + tag + ") {");
            indent++;
            emitCastAndExtract(variantRedeemerPattern.match(), "redeemer");
            if (!conditionExprs.isEmpty()) {
                if (rule.traceMessage() != null) {
                    emitLine("// trace: " + rule.traceMessage());
                }
                String combined = joinConditions(conditionExprs);
                if (rule.action() == DefaultAction.ALLOW) {
                    emitLine("return " + combined + ";");
                } else {
                    emitLine("return !(" + combined + ");");
                }
            } else {
                emitReturnAction(rule);
            }
            indent--;
            emitLine("}");
        } else if (!conditionExprs.isEmpty()) {
            emitLine("if (" + joinConditions(conditionExprs) + ") {");
            indent++;
            emitReturnAction(rule);
            indent--;
            emitLine("}");
        } else {
            emitReturnAction(rule);
        }
    }

    // ── Output pattern emission ────────────────────────────────

    private void emitOutputConditions(FactPattern.OutputPattern op,
                                       List<String> conditionExprs) {
        if (op.to() == null) return;
        String toExpr;
        if (op.value() != null && op.datum() != null) {
            // Cache address expression to avoid duplicating complex expressions
            String addrVar = "_addr" + (varCounter++);
            emitLine("var " + addrVar + " = " + FactTranslator.translateExpr(op.to()) + ";");
            toExpr = addrVar;
        } else {
            toExpr = FactTranslator.translateExpr(op.to());
        }
        if (op.value() != null) {
            conditionExprs.add(FactTranslator.translateValueConstraint(op.value(), toExpr));
        }
        if (op.datum() != null) {
            String datumVar = "_outDatum" + (varCounter++);
            emitLine("var " + datumVar + " = (" + op.datum().typeName()
                    + ") OutputLib.getInlineDatum(OutputLib.uniqueOutputAt(txInfo.outputs(), " + toExpr + "));");
            for (String cond : FactTranslator.translateDatumConstraint(op.datum(), datumVar)) {
                conditionExprs.add(cond);
            }
        }
    }

    // ── Input pattern emission ───────────────────────────────────

    private void emitInputPattern(FactPattern.InputPattern ip,
                                   List<String> conditionExprs) {
        if (ip.from() != null && ip.from() instanceof Expression.OwnAddressExpr && ip.valueBinding() != null) {
            emitLine("var " + ip.valueBinding() + " = ContextsLib.findOwnInput(ctx).get().resolved().value();");
        }
        if (ip.token() != null) {
            conditionExprs.add(FactTranslator.translateInputTokenConstraint(ip.token()));
        }
    }

    // ── Mint pattern emission ────────────────────────────────────

    private void emitMintPattern(FactPattern.MintPattern mp,
                                  List<String> conditionExprs) {
        String policyExpr = mp.policy() != null ? FactTranslator.translateExpr(mp.policy()) : "ownPolicyId";
        String tokenExpr = mp.token() != null ? FactTranslator.translateExpr(mp.token()) : null;
        String assetExpr = mintAssetExpr(policyExpr, tokenExpr);

        if (mp.amountBinding() != null) {
            emitLine("var " + mp.amountBinding() + " = " + assetExpr + ";");
        } else if (mp.burned()) {
            conditionExprs.add(assetExpr + ".compareTo(BigInteger.ZERO) < 0");
        } else if (tokenExpr != null) {
            conditionExprs.add(assetExpr + ".compareTo(BigInteger.ZERO) > 0");
        }
    }

    private String mintAssetExpr(String policyExpr, String tokenExpr) {
        String token = tokenExpr != null ? tokenExpr : "new byte[]{}";
        return "ValuesLib.assetOf(txInfo.mint(), " + policyExpr + ", " + token + ")";
    }

    // ── ContinuingOutput pattern emission ────────────────────────

    private void emitContinuingOutputPattern(FactPattern.ContinuingOutputPattern cop,
                                              List<String> conditionExprs) {
        String continuingVar = "_continuing" + (varCounter++);
        emitLine("var " + continuingVar + " = ContextsLib.getContinuingOutputs(ctx);");

        if (cop.value() != null) {
            conditionExprs.add(FactTranslator.translateValueConstraintForOutputs(
                    cop.value(), continuingVar));
        }

        if (cop.datum() != null) {
            String datumVar = "_coDatum" + (varCounter++);
            emitLine("var " + datumVar + " = (" + cop.datum().typeName()
                    + ") OutputLib.getInlineDatum(" + continuingVar + ".head());");
            for (String cond : FactTranslator.translateDatumConstraint(cop.datum(), datumVar)) {
                conditionExprs.add(cond);
            }
        }
    }

    /**
     * Find the tag (constructor index) for a variant in a sealed interface redeemer.
     */
    private int findVariantTag(VariantMatch match, RedeemerDeclNode redeemer) {
        for (int i = 0; i < redeemer.variants().size(); i++) {
            if (redeemer.variants().get(i).name().equals(match.typeName())) {
                return i;
            }
        }
        throw new IllegalStateException("Unknown variant: " + match.typeName());
    }

    /**
     * Emit a cast-and-extract for record types (datum, non-variant redeemers).
     * Does not add nesting depth — variables are declared at the current scope.
     */
    private void emitCastAndExtract(VariantMatch match, String varName) {
        String typeName = match.typeName();
        String localVar = "_" + varName.charAt(0) + (varCounter++);

        if (!match.fields().isEmpty()) {
            emitLine("var " + localVar + " = (" + typeName + ") " + varName + ";");
            for (var fm : match.fields()) {
                if (fm.value() instanceof MatchValue.Binding binding) {
                    emitLine("var " + binding.varName() + " = " + localVar
                            + "." + fm.fieldName() + "();");
                }
                // Literal and NestedMatch are rejected by the type checker (JRL050/JRL051)
            }
        }
    }

    private void emitReturnAction(RuleNode rule) {
        if (rule.traceMessage() != null) {
            emitLine("// trace: " + rule.traceMessage());
        }
        boolean returnValue = rule.action() == DefaultAction.ALLOW;
        emitLine("return " + returnValue + ";");
    }

    // ── Code generation helpers ─────────────────────────────────

    private String joinConditions(List<String> conditions) {
        return String.join("\n" + indentStr() + "    && ", conditions);
    }

    private String purposeAnnotation(PurposeType purpose) {
        return switch (purpose) {
            case SPENDING -> "SpendingValidator";
            case MINTING -> "MintingValidator";
            case WITHDRAW -> "WithdrawValidator";
            case CERTIFYING -> "CertifyingValidator";
            case VOTING -> "VotingValidator";
            case PROPOSING -> "ProposingValidator";
        };
    }

    private String sanitizeClassName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "");
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void emitLine(String line) {
        sb.append(indentStr()).append(line).append("\n");
    }

    private void emitLine() {
        sb.append("\n");
    }

    private String indentStr() {
        return "    ".repeat(indent);
    }
}
