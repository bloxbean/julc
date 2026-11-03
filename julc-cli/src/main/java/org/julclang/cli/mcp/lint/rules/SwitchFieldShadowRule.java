package org.julclang.cli.mcp.lint.rules;

import org.julclang.ledger.Credential;
import org.julclang.ledger.DRep;
import org.julclang.ledger.Delegatee;
import org.julclang.ledger.GovernanceAction;
import org.julclang.ledger.IntervalBoundType;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.ScriptInfo;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.TxCert;
import org.julclang.ledger.Vote;
import org.julclang.ledger.Voter;
import org.julclang.cli.mcp.lint.LintFinding;
import org.julclang.cli.mcp.lint.LintRule;
import org.julclang.cli.mcp.lint.LintTrees;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.util.TreePath;
import javax.lang.model.element.Modifier;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PatternCaseLabelTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
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

    @Override
    public List<LintFinding> check(JavacSyntax syntax) {
        var findings = new ArrayList<LintFinding>();
        Map<String, Set<String>> variantFields = collectVariantFields(syntax);
        for (TreePath method : LintTrees.methods(syntax)) {
            Set<String> paramNames = new HashSet<>();
            ((MethodTree) method.getLeaf()).getParameters().forEach(p -> paramNames.add(p.getName().toString()));
            for (TreePath sw : LintTrees.findAll(method, SwitchExpressionTree.class)) {
                for (TreePath entry : LintTrees.findAll(sw, CaseTree.class)) {
                    checkEntry(syntax, entry, paramNames, variantFields, findings);
                }
            }
        }
        return findings;
    }

    private void checkEntry(JavacSyntax syntax, TreePath entry, Set<String> paramNames,
                            Map<String, Set<String>> variantFields, List<LintFinding> out) {
        for (CaseLabelTree label : ((CaseTree) entry.getLeaf()).getLabels()) {
            PatternLabel pattern = patternLabel(label);
            if (pattern == null) continue;
            int line = syntax.line(label);
            int col = syntax.column(label);

            Set<String> alreadyReported = new HashSet<>();
            Set<String> fields = fieldsForVariant(pattern.typeName(), variantFields);
            for (String param : paramNames) {
                if (!fields.contains(param)) continue;
                if (!isReferenced(entry, param)) continue;
                alreadyReported.add(param);
                out.add(fieldShadowFinding(line, col, pattern.typeName(), pattern.bindingName(), param));
            }

            String bindingName = pattern.bindingName();
            if (paramNames.contains(bindingName)
                    && !alreadyReported.contains(bindingName)
                    && isReferenced(entry, bindingName)) {
                out.add(bindingShadowFinding(line, col, bindingName));
            }
        }
    }

    /** A type pattern's type and binding; for another label, JavaParser's reading of its printed text. */
    private static PatternLabel patternLabel(CaseLabelTree label) {
        if (label instanceof PatternCaseLabelTree pattern && pattern.getPattern() instanceof BindingPatternTree binding) {
            return new PatternLabel(JavacSyntax.typeName(binding.getVariable().getType()),
                    binding.getVariable().getName().toString());
        }
        String text = label instanceof ConstantCaseLabelTree constant ? constant.getConstantExpression().toString()
                : label.toString();
        int sp = text.lastIndexOf(' ');
        if (sp <= 0) return null;
        String typeName = text.substring(0, sp).trim();
        String identifier = text.substring(sp + 1).trim();
        if (!isJavaIdentifier(identifier)) return null;
        return new PatternLabel(typeName, identifier);
    }

    /** Whether a name expression in the case's body, not its labels or guard, is {@code identifier}. */
    private static boolean isReferenced(TreePath entry, String identifier) {
        var c = (CaseTree) entry.getLeaf();
        List<? extends Tree> body = c.getCaseKind() == CaseTree.CaseKind.RULE ? List.of(c.getBody()) : c.getStatements();
        for (Tree statement : body) {
            for (TreePath path : LintTrees.findAll(new TreePath(entry, statement), IdentifierTree.class)) {
                if (LintTrees.isNameExpression(path)
                        && ((IdentifierTree) path.getLeaf()).getName().contentEquals(identifier)) {
                    return true;
                }
            }
        }
        return false;
    }

    private LintFinding fieldShadowFinding(int line, int col, String typeName, String bindingName, String fieldName) {
        return LintFinding.warning(
                "JULC-LINT-SWITCH-SHADOW",
                "JULC0021",
                "Switch case `" + typeName + " " + bindingName + "` binds a field `" +
                        fieldName + "` that shadows a method parameter of the same name. " +
                        "References to `" + fieldName + "` inside the case body resolve " +
                        "to the case field, NOT the parameter.",
                line, col,
                "Rename the parameter. E.g. if the parameter is `BigInteger " +
                        fieldName + "` and the case variant has field `" + fieldName +
                        "`, rename the parameter to `point`.");
    }

    private LintFinding bindingShadowFinding(int line, int col, String identifier) {
        return LintFinding.warning(
                "JULC-LINT-SWITCH-SHADOW",
                "JULC0021",
                "Switch case binding `" + identifier + "` shadows a method parameter " +
                        "of the same name and is referenced inside the case body. The " +
                        "reference resolves to the case-bound value, NOT the parameter.",
                line, col,
                "Rename the parameter or the case binding. E.g. if the parameter is " +
                        "`BigInteger " + identifier + "` and the case is `Finite " +
                        identifier + "`, rename the parameter to `point`.");
    }

    /**
     * The ledger's variants and the unit's records, each by its simple name and by the name qualified with its nearest
     * enclosing named class or interface. javac's parser lists a record's components first among its members, as
     * fields; a record has no other instance field.
     */
    private static Map<String, Set<String>> collectVariantFields(JavacSyntax syntax) {
        Map<String, Set<String>> fields = new HashMap<>();
        LEDGER_VARIANT_FIELDS.forEach((name, names) -> fields.put(name, new HashSet<>(names)));
        for (TreePath path : LintTrees.findAll(syntax, ClassTree.class)) {
            var record = (ClassTree) path.getLeaf();
            if (record.getKind() != Tree.Kind.RECORD) continue;
            Set<String> names = new HashSet<>();
            for (Tree member : record.getMembers()) {
                if (member instanceof VariableTree component
                        && !component.getModifiers().getFlags().contains(Modifier.STATIC)) {
                    names.add(component.getName().toString());
                }
            }
            if (names.isEmpty()) continue;
            addVariantFields(fields, record.getSimpleName().toString(), names);
            for (TreePath up = path.getParentPath(); up != null; up = up.getParentPath()) {
                if (up.getLeaf() instanceof ClassTree parent && !parent.getSimpleName().isEmpty()
                        && (parent.getKind() == Tree.Kind.CLASS || parent.getKind() == Tree.Kind.INTERFACE)) {
                    addVariantFields(fields, parent.getSimpleName() + "." + record.getSimpleName(), names);
                    break;
                }
            }
        }
        return fields;
    }
}
