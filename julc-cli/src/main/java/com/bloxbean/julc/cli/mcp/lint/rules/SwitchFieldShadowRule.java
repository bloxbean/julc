package com.bloxbean.julc.cli.mcp.lint.rules;

import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.DRep;
import com.bloxbean.cardano.julc.ledger.Delegatee;
import com.bloxbean.cardano.julc.ledger.GovernanceAction;
import com.bloxbean.cardano.julc.ledger.IntervalBoundType;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.ScriptPurpose;
import com.bloxbean.cardano.julc.ledger.StakingCredential;
import com.bloxbean.cardano.julc.ledger.TxCert;
import com.bloxbean.cardano.julc.ledger.Vote;
import com.bloxbean.cardano.julc.ledger.Voter;
import com.bloxbean.julc.cli.mcp.lint.LintFinding;
import com.bloxbean.julc.cli.mcp.lint.LintRule;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.SwitchEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects when a switch case field binding silently shadows a method
 * parameter — the JULC0021 trap. JuLC's switch lowering binds constructor
 * fields in the case body:
 * {@code case Finite f -> f.time().compareTo(time) > 0} silently rebinds
 * {@code time} to {@code Finite.time} if the parameter is also named
 * {@code BigInteger time}.
 *
 * <p>Detection heuristic: for each method, collect parameter names. For each
 * switch expression in the method body, look at every case label of the
 * shape {@code case X y -> ...}. If {@code X}'s record fields include a
 * method parameter name and the case body references that name, flag it.
 * We also keep the older direct binding-name shadow check because it catches
 * hand-written pattern names before field metadata is available.
 */
public final class SwitchFieldShadowRule implements LintRule {

    private static final Map<String, Set<String>> LEDGER_VARIANT_FIELDS = ledgerVariantFields();

    @Override
    public String id() {
        return "JULC-LINT-SWITCH-SHADOW";
    }

    @Override
    public String description() {
        return "Switch case binding name shadows a method parameter (JULC0021)";
    }

    @Override
    public List<LintFinding> check(CompilationUnit cu) {
        var findings = new ArrayList<LintFinding>();
        Map<String, Set<String>> variantFields = collectVariantFields(cu);
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            Set<String> paramNames = new HashSet<>();
            method.getParameters().forEach(p -> paramNames.add(p.getNameAsString()));

            // Walk every switch expression inside this method.
            method.findAll(SwitchExpr.class).forEach(sw ->
                    sw.findAll(SwitchEntry.class).forEach(entry ->
                            checkEntry(entry, paramNames, variantFields, findings)));
        });
        return findings;
    }

    private void checkEntry(SwitchEntry entry, Set<String> paramNames,
                            Map<String, Set<String>> variantFields,
                            List<LintFinding> out) {
        // SwitchEntry labels are expressions in JavaParser; for type-pattern
        // labels they appear as TypePatternExpr. We use the AST when present
        // and fall back to text for older/parser edge cases.
        for (var label : entry.getLabels()) {
            PatternLabel pattern = parsePatternLabel(label);
            if (pattern == null) continue;

            Set<String> alreadyReported = new HashSet<>();
            Set<String> fields = fieldsForVariant(pattern.typeName(), variantFields);
            for (String param : paramNames) {
                if (!fields.contains(param)) continue;
                if (!isReferenced(entry, param)) continue;
                alreadyReported.add(param);
                addFieldShadowFinding(out, label, pattern.typeName(), pattern.bindingName(), param);
            }

            String bindingName = pattern.bindingName();
            if (paramNames.contains(bindingName)
                    && !alreadyReported.contains(bindingName)
                    && isReferenced(entry, bindingName)) {
                addBindingShadowFinding(out, label, bindingName);
            }
        }
    }

    private static PatternLabel parsePatternLabel(com.github.javaparser.ast.expr.Expression label) {
        if (label instanceof TypePatternExpr typePattern) {
            return new PatternLabel(typePattern.getType().asString(),
                    typePattern.getName().asString());
        }
        String text = label.toString();
        int sp = text.lastIndexOf(' ');
        if (sp <= 0) return null;
        String typeName = text.substring(0, sp).trim();
        String identifier = text.substring(sp + 1).trim();
        if (!isJavaIdentifier(identifier)) return null;
        return new PatternLabel(typeName, identifier);
    }

    private static Set<String> fieldsForVariant(String typeName, Map<String, Set<String>> variantFields) {
        Set<String> fields = new HashSet<>();
        addFields(fields, variantFields.get(typeName));
        int dot = typeName.lastIndexOf('.');
        if (dot >= 0) {
            addFields(fields, variantFields.get(typeName.substring(dot + 1)));
        }
        return fields;
    }

    private static void addFields(Set<String> target, Set<String> fields) {
        if (fields != null) target.addAll(fields);
    }

    private static boolean isReferenced(SwitchEntry entry, String identifier) {
        return entry.getStatements().stream()
                .flatMap(stmt -> stmt.findAll(NameExpr.class).stream())
                .anyMatch(ne -> identifier.equals(ne.getNameAsString()));
    }

    private void addFieldShadowFinding(List<LintFinding> out, Node label, String typeName,
                                       String bindingName, String fieldName) {
            int line = label.getBegin().map(p -> p.line).orElse(0);
            int col = label.getBegin().map(p -> p.column).orElse(0);
            out.add(LintFinding.warning(
                    "JULC-LINT-SWITCH-SHADOW",
                    "JULC0021",
                    "Switch case `" + typeName + " " + bindingName + "` binds a field `" +
                            fieldName + "` that shadows a method parameter of the same name. " +
                            "References to `" + fieldName + "` inside the case body resolve " +
                            "to the case field, NOT the parameter.",
                    line, col,
                    "Rename the parameter. E.g. if the parameter is `BigInteger " +
                            fieldName + "` and the case variant has field `" + fieldName +
                            "`, rename the parameter to `point`."
            ));
    }

    private void addBindingShadowFinding(List<LintFinding> out, Node label, String identifier) {
        int line = label.getBegin().map(p -> p.line).orElse(0);
        int col = label.getBegin().map(p -> p.column).orElse(0);
        out.add(LintFinding.warning(
                "JULC-LINT-SWITCH-SHADOW",
                "JULC0021",
                "Switch case binding `" + identifier + "` shadows a method parameter " +
                        "of the same name and is referenced inside the case body. The " +
                        "reference resolves to the case-bound value, NOT the parameter.",
                line, col,
                "Rename the parameter or the case binding. E.g. if the parameter is " +
                        "`BigInteger " + identifier + "` and the case is `Finite " +
                        identifier + "`, rename the parameter to `point`."
        ));
    }

    private static Map<String, Set<String>> collectVariantFields(CompilationUnit cu) {
        Map<String, Set<String>> fields = new HashMap<>();
        LEDGER_VARIANT_FIELDS.forEach((name, names) -> fields.put(name, new HashSet<>(names)));
        cu.findAll(RecordDeclaration.class).forEach(record -> {
            Set<String> names = new HashSet<>();
            record.getParameters().forEach(p -> names.add(p.getNameAsString()));
            if (names.isEmpty()) return;
            addVariantFields(fields, record.getNameAsString(), names);
            record.findAncestor(ClassOrInterfaceDeclaration.class)
                    .ifPresent(parent -> addVariantFields(fields,
                            parent.getNameAsString() + "." + record.getNameAsString(), names));
        });
        return fields;
    }

    private static boolean isJavaIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    private static Map<String, Set<String>> ledgerVariantFields() {
        Map<String, Set<String>> out = new HashMap<>();
        for (Class<?> sealedType : List.of(
                Credential.class,
                DRep.class,
                Delegatee.class,
                GovernanceAction.class,
                IntervalBoundType.class,
                OutputDatum.class,
                ScriptInfo.class,
                ScriptPurpose.class,
                StakingCredential.class,
                TxCert.class,
                Vote.class,
                Voter.class
        )) {
            for (Class<?> nested : sealedType.getDeclaredClasses()) {
                if (!nested.isRecord()) continue;
                Set<String> names = new HashSet<>();
                for (var component : nested.getRecordComponents()) {
                    names.add(component.getName());
                }
                if (names.isEmpty()) continue;
                addVariantFields(out, nested.getSimpleName(), names);
                addVariantFields(out, sealedType.getSimpleName() + "." + nested.getSimpleName(), names);
            }
        }
        return Map.copyOf(out);
    }

    private static void addVariantFields(Map<String, Set<String>> target, String name, Set<String> fields) {
        target.computeIfAbsent(name, ignored -> new HashSet<>()).addAll(fields);
    }

    private record PatternLabel(String typeName, String bindingName) {}
}
