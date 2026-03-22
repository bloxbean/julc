package com.bloxbean.cardano.julc.crl.check;

import com.bloxbean.cardano.julc.crl.ast.*;

import java.util.*;

/**
 * Validates a parsed CRL AST for structural correctness, type consistency,
 * and variable scoping. Produces a list of {@link CrlDiagnostic} errors/warnings.
 * <p>
 * Validation passes:
 * <ol>
 *   <li>Structure — contract fields, single vs multi-purpose, defaults</li>
 *   <li>Types — all type references resolve to known types</li>
 *   <li>Names — no duplicates for rules, fields, variants</li>
 *   <li>Variables — $var bound before use, params resolve, context-specific refs</li>
 *   <li>Expressions — type compatibility in binary ops, function call validation</li>
 * </ol>
 */
public final class CrlTypeChecker {

    private final List<CrlDiagnostic> diagnostics = new ArrayList<>();
    private final Set<String> declaredTypes = new HashSet<>();

    private CrlTypeChecker() {}

    /**
     * Check the given contract AST and return all diagnostics.
     */
    public static List<CrlDiagnostic> check(ContractNode contract) {
        var checker = new CrlTypeChecker();
        checker.run(contract);
        return Collections.unmodifiableList(checker.diagnostics);
    }

    private void run(ContractNode contract) {
        checkStructure(contract);
        collectDeclaredTypes(contract);
        checkTypes(contract);
        checkNames(contract);
        if (contract.isMultiValidator()) {
            for (var section : contract.purposeSections()) {
                checkVariables(section.rules(), contract.params(), section.purpose(),
                        contract.datum(), section.redeemer());
            }
        } else {
            checkVariables(contract.rules(), contract.params(), contract.purpose(),
                    contract.datum(), contract.redeemer());
        }
    }

    // ── Pass 1: Structure ───────────────────────────────────────

    private void checkStructure(ContractNode contract) {
        boolean hasSinglePurpose = contract.purpose() != null;
        boolean hasTopLevelRules = !contract.rules().isEmpty();
        boolean hasTopLevelDefault = contract.defaultAction() != null;
        boolean hasMultiSections = contract.isMultiValidator();

        // Must not mix single-purpose and multi-purpose
        if (hasSinglePurpose && hasMultiSections) {
            error("CRL010", "Cannot mix top-level 'purpose' with purpose sections",
                    contract.sourceRange(),
                    "Use either 'purpose spending' in header OR 'purpose spending:' sections, not both");
        }

        // Single-purpose: must have default and at least one rule
        if (hasSinglePurpose && !hasMultiSections) {
            if (!hasTopLevelDefault) {
                error("CRL009", "Missing 'default:' clause", contract.sourceRange(),
                        "Add 'default: deny' or 'default: allow' at the end");
            }
            if (!hasTopLevelRules) {
                error("CRL001", "No rules defined", contract.sourceRange());
            }
        }

        // Multi-purpose: each section must have default and rules
        if (hasMultiSections) {
            for (var section : contract.purposeSections()) {
                if (section.rules().isEmpty()) {
                    error("CRL001", "Purpose section '" + section.purpose()
                            + "' has no rules", section.sourceRange());
                }
            }
        }

        // No purpose at all
        if (!hasSinglePurpose && !hasMultiSections) {
            if (hasTopLevelRules) {
                error("CRL001", "Contract has rules but no 'purpose' declaration",
                        contract.sourceRange(),
                        "Add 'purpose spending' (or minting/withdraw/...) to the header");
            }
        }
    }

    // ── Collect declared type names ─────────────────────────────

    private void collectDeclaredTypes(ContractNode contract) {
        // Add all known built-in types
        declaredTypes.addAll(BuiltinFunctionRegistry.KNOWN_TYPES);

        // Add user-declared datum
        if (contract.datum() != null) {
            declaredTypes.add(contract.datum().name());
        }

        // Add user-declared records
        for (var rec : contract.records()) {
            declaredTypes.add(rec.name());
        }

        // Add user-declared redeemer + variants (top-level)
        addRedeemerTypes(contract.redeemer());

        // Add redeemers from purpose sections
        for (var section : contract.purposeSections()) {
            addRedeemerTypes(section.redeemer());
        }
    }

    private void addRedeemerTypes(RedeemerDeclNode redeemer) {
        if (redeemer == null) return;
        declaredTypes.add(redeemer.name());
        for (var v : redeemer.variants()) {
            declaredTypes.add(v.name());
        }
    }

    // ── Pass 2: Types ───────────────────────────────────────────

    private void checkTypes(ContractNode contract) {
        for (var param : contract.params()) {
            checkTypeRef(param.type());
        }
        if (contract.datum() != null) {
            for (var field : contract.datum().fields()) {
                checkTypeRef(field.type());
            }
        }
        for (var rec : contract.records()) {
            for (var field : rec.fields()) {
                checkTypeRef(field.type());
            }
        }
        checkRedeemerTypes(contract.redeemer());
        for (var section : contract.purposeSections()) {
            checkRedeemerTypes(section.redeemer());
        }
    }

    private void checkRedeemerTypes(RedeemerDeclNode redeemer) {
        if (redeemer == null) return;
        for (var field : redeemer.fields()) {
            checkTypeRef(field.type());
        }
        for (var variant : redeemer.variants()) {
            for (var field : variant.fields()) {
                checkTypeRef(field.type());
            }
        }
    }

    private void checkTypeRef(TypeRef ref) {
        switch (ref) {
            case TypeRef.SimpleType st -> {
                if (!declaredTypes.contains(st.name())) {
                    error("CRL002", "Unknown type '" + st.name() + "'", st.sourceRange());
                }
            }
            case TypeRef.ListType lt -> checkTypeRef(lt.elementType());
            case TypeRef.OptionalType ot -> checkTypeRef(ot.elementType());
        }
    }

    // ── Pass 3: Names ───────────────────────────────────────────

    private void checkNames(ContractNode contract) {
        // Check duplicate rule names in each scope
        checkDuplicateRuleNames(contract.rules());
        for (var section : contract.purposeSections()) {
            checkDuplicateRuleNames(section.rules());
        }

        // Check duplicate field names in declarations
        if (contract.datum() != null) {
            checkDuplicateFieldNames(contract.datum().fields(), "datum " + contract.datum().name());
        }
        for (var rec : contract.records()) {
            checkDuplicateFieldNames(rec.fields(), "record " + rec.name());
        }
        checkRedeemerDuplicates(contract.redeemer());
        for (var section : contract.purposeSections()) {
            checkRedeemerDuplicates(section.redeemer());
        }
    }

    private void checkDuplicateRuleNames(List<RuleNode> rules) {
        var seen = new HashSet<String>();
        for (var rule : rules) {
            if (!seen.add(rule.name())) {
                error("CRL005", "Duplicate rule name '" + rule.name() + "'",
                        rule.sourceRange());
            }
        }
    }

    private void checkDuplicateFieldNames(List<FieldNode> fields, String context) {
        var seen = new HashSet<String>();
        for (var field : fields) {
            if (!seen.add(field.name())) {
                error("CRL005", "Duplicate field '" + field.name() + "' in " + context,
                        field.sourceRange());
            }
        }
    }

    private void checkRedeemerDuplicates(RedeemerDeclNode redeemer) {
        if (redeemer == null) return;
        // Variant names
        var seen = new HashSet<String>();
        for (var v : redeemer.variants()) {
            if (!seen.add(v.name())) {
                error("CRL005", "Duplicate variant '" + v.name() + "' in redeemer "
                        + redeemer.name(), v.sourceRange());
            }
            checkDuplicateFieldNames(v.fields(), "variant " + v.name());
        }
        // Record-style fields
        checkDuplicateFieldNames(redeemer.fields(), "redeemer " + redeemer.name());
    }

    // ── Pass 4: Variables ───────────────────────────────────────

    private void checkVariables(List<RuleNode> rules, List<ParamNode> params,
                                PurposeType purpose, DatumDeclNode datum,
                                RedeemerDeclNode redeemer) {
        // Build param name set
        var paramNames = new HashSet<String>();
        for (var p : params) {
            paramNames.add(p.name());
        }

        for (var rule : rules) {
            // Each rule has its own variable scope
            var boundVars = new HashSet<String>();

            for (var pattern : rule.patterns()) {
                switch (pattern) {
                    case FactPattern.RedeemerPattern rp ->
                            collectBindings(rp.match(), boundVars);
                    case FactPattern.DatumPattern dp ->
                            collectBindings(dp.match(), boundVars);
                    case FactPattern.TransactionPattern tp ->
                            checkExprVars(tp.value(), boundVars, paramNames, purpose);
                    case FactPattern.ConditionPattern cp ->
                            checkExprVars(cp.condition(), boundVars, paramNames, purpose);
                    case FactPattern.OutputPattern op -> {
                        if (op.to() != null) checkExprVars(op.to(), boundVars, paramNames, purpose);
                        if (op.value() != null) checkValueConstraintVars(op.value(), boundVars, paramNames, purpose);
                    }
                }
            }
        }
    }

    private void collectBindings(VariantMatch match, Set<String> boundVars) {
        for (var fm : match.fields()) {
            switch (fm.value()) {
                case MatchValue.Binding b -> boundVars.add(b.varName());
                case MatchValue.NestedMatch nm -> {
                    for (var inner : nm.fields()) {
                        collectBindingsFromMatch(inner, boundVars);
                    }
                }
                case MatchValue.Literal _ -> {} // no bindings
            }
        }
    }

    private void collectBindingsFromMatch(FieldMatch fm, Set<String> boundVars) {
        switch (fm.value()) {
            case MatchValue.Binding b -> boundVars.add(b.varName());
            case MatchValue.NestedMatch nm -> {
                for (var inner : nm.fields()) {
                    collectBindingsFromMatch(inner, boundVars);
                }
            }
            case MatchValue.Literal _ -> {}
        }
    }

    private void checkExprVars(Expression expr, Set<String> boundVars,
                               Set<String> paramNames, PurposeType purpose) {
        switch (expr) {
            case Expression.VarRefExpr vr -> {
                if (!boundVars.contains(vr.name())) {
                    error("CRL003", "Undefined variable '$" + vr.name() + "'",
                            vr.sourceRange(),
                            "Bind it in a Redeemer(...) or Datum(...) pattern first");
                }
            }
            case Expression.IdentRefExpr ir -> {
                if (!paramNames.contains(ir.name()) && !declaredTypes.contains(ir.name())) {
                    error("CRL003", "Unknown identifier '" + ir.name() + "'",
                            ir.sourceRange(),
                            "Is this a param? Declare it in 'params:'");
                }
            }
            case Expression.OwnAddressExpr oa -> {
                if (purpose != null && purpose != PurposeType.SPENDING) {
                    error("CRL007", "'ownAddress' is only available in spending validators",
                            oa.sourceRange());
                }
            }
            case Expression.OwnPolicyIdExpr op -> {
                if (purpose != null && purpose != PurposeType.MINTING) {
                    error("CRL008", "'ownPolicyId' is only available in minting validators",
                            op.sourceRange());
                }
            }
            case Expression.BinaryExpr be -> {
                checkExprVars(be.left(), boundVars, paramNames, purpose);
                checkExprVars(be.right(), boundVars, paramNames, purpose);
            }
            case Expression.UnaryExpr ue ->
                    checkExprVars(ue.operand(), boundVars, paramNames, purpose);
            case Expression.FieldAccessExpr fa ->
                    checkExprVars(fa.target(), boundVars, paramNames, purpose);
            case Expression.FunctionCallExpr fc -> {
                var sig = BuiltinFunctionRegistry.lookup(fc.name());
                if (sig.isEmpty()) {
                    error("CRL011", "Unknown function '" + fc.name() + "'",
                            fc.sourceRange());
                } else if (fc.args().size() != sig.get().argTypes().length) {
                    error("CRL004", "Function '" + fc.name() + "' expects "
                            + sig.get().argTypes().length + " argument(s) but got "
                            + fc.args().size(), fc.sourceRange());
                }
                for (var arg : fc.args()) {
                    checkExprVars(arg, boundVars, paramNames, purpose);
                }
            }
            case Expression.IntLiteralExpr _, Expression.StringLiteralExpr _,
                 Expression.HexLiteralExpr _, Expression.BoolLiteralExpr _ -> {}
        }
    }

    private void checkValueConstraintVars(ValueConstraint vc, Set<String> boundVars,
                                          Set<String> paramNames, PurposeType purpose) {
        switch (vc) {
            case ValueConstraint.MinADA ma ->
                    checkExprVars(ma.amount(), boundVars, paramNames, purpose);
            case ValueConstraint.Contains c -> {
                checkExprVars(c.policy(), boundVars, paramNames, purpose);
                checkExprVars(c.token(), boundVars, paramNames, purpose);
                checkExprVars(c.amount(), boundVars, paramNames, purpose);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void error(String code, String message, SourceRange range) {
        diagnostics.add(CrlDiagnostic.error(code, message, range));
    }

    private void error(String code, String message, SourceRange range, String suggestion) {
        diagnostics.add(CrlDiagnostic.error(code, message, range, suggestion));
    }
}
