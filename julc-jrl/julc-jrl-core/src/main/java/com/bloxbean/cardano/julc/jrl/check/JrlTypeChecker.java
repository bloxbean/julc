package com.bloxbean.cardano.julc.jrl.check;

import com.bloxbean.cardano.julc.jrl.ast.*;

import java.util.*;

/**
 * Validates a parsed JRL AST for structural correctness, type consistency,
 * and variable scoping. Produces a list of {@link JrlDiagnostic} errors/warnings.
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
public final class JrlTypeChecker {

    private final List<JrlDiagnostic> diagnostics = new ArrayList<>();
    private final Set<String> declaredTypes = new HashSet<>();

    private JrlTypeChecker() {}

    /**
     * Check the given contract AST and return all diagnostics.
     */
    public static List<JrlDiagnostic> check(ContractNode contract) {
        var checker = new JrlTypeChecker();
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
            error("JRL010", "Cannot mix top-level 'purpose' with purpose sections",
                    contract.sourceRange(),
                    "Use either 'purpose spending' in header OR 'purpose spending:' sections, not both");
        }

        // Single-purpose: must have default and at least one rule
        if (hasSinglePurpose && !hasMultiSections) {
            if (!hasTopLevelDefault) {
                error("JRL009", "Missing 'default:' clause", contract.sourceRange(),
                        "Add 'default: deny' or 'default: allow' at the end");
            }
            if (!hasTopLevelRules) {
                error("JRL001", "No rules defined", contract.sourceRange());
            }
        }

        // Multi-purpose: each section must have default and rules
        if (hasMultiSections) {
            for (var section : contract.purposeSections()) {
                if (section.rules().isEmpty()) {
                    error("JRL001", "Purpose section '" + section.purpose()
                            + "' has no rules", section.sourceRange());
                }
            }
        }

        // No purpose at all
        if (!hasSinglePurpose && !hasMultiSections) {
            if (hasTopLevelRules) {
                error("JRL001", "Contract has rules but no 'purpose' declaration",
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
                    error("JRL002", "Unknown type '" + st.name() + "'", st.sourceRange());
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
                error("JRL005", "Duplicate rule name '" + rule.name() + "'",
                        rule.sourceRange());
            }
        }
    }

    private void checkDuplicateFieldNames(List<FieldNode> fields, String context) {
        var seen = new HashSet<String>();
        for (var field : fields) {
            if (!seen.add(field.name())) {
                error("JRL005", "Duplicate field '" + field.name() + "' in " + context,
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
                error("JRL005", "Duplicate variant '" + v.name() + "' in redeemer "
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
                    case FactPattern.TransactionPattern tp -> {
                        if (tp.field() == FactPattern.TxField.FEE) {
                            // Fee is a binding, not a check — the value expression should be a $var
                            if (tp.value() instanceof Expression.VarRefExpr vr) {
                                boundVars.add(vr.name());
                            } else {
                                checkExprVars(tp.value(), boundVars, paramNames, purpose);
                            }
                        } else {
                            checkExprVars(tp.value(), boundVars, paramNames, purpose);
                        }
                    }
                    case FactPattern.ConditionPattern cp ->
                            checkExprVars(cp.condition(), boundVars, paramNames, purpose);
                    case FactPattern.OutputPattern op -> {
                        checkOutputPattern(op, boundVars, paramNames, purpose);
                    }
                    case FactPattern.InputPattern ip -> {
                        checkInputPattern(ip, boundVars, paramNames, purpose);
                    }
                    case FactPattern.MintPattern mp -> {
                        checkMintPattern(mp, boundVars, paramNames, purpose);
                    }
                    case FactPattern.ContinuingOutputPattern cop -> {
                        checkContinuingOutputPattern(cop, boundVars, paramNames, purpose);
                    }
                }
            }
        }
    }

    private void checkOutputPattern(FactPattern.OutputPattern op, Set<String> boundVars,
                                     Set<String> paramNames, PurposeType purpose) {
        if (op.to() == null && op.value() == null && op.datum() == null) {
            error("JRL033", "Empty Output pattern has no effect",
                    op.sourceRange(),
                    "Add 'to:' and 'value:' fields, e.g. Output( to: addr, value: minADA(2000000) )");
        }
        if (op.to() != null && op.value() == null && op.datum() == null) {
            error("JRL032", "Output pattern requires 'value:' or 'Datum:' field when 'to:' is specified",
                    op.sourceRange(),
                    "Add a value or datum constraint, e.g. Output( to: addr, value: minADA(2000000) )");
        }
        if (op.to() == null && op.value() != null) {
            error("JRL031", "Output pattern requires 'to:' field when 'value:' is specified",
                    op.sourceRange(),
                    "Add a recipient, e.g. Output( to: addr, value: minADA(2000000) )");
        }
        if (op.to() != null) checkExprVars(op.to(), boundVars, paramNames, purpose);
        if (op.value() != null) checkValueConstraintVars(op.value(), boundVars, paramNames, purpose);
        if (op.datum() != null) checkDatumConstraintVars(op.datum(), boundVars, paramNames, purpose);
    }

    private void checkInputPattern(FactPattern.InputPattern ip, Set<String> boundVars,
                                    Set<String> paramNames, PurposeType purpose) {
        if (ip.from() == null && ip.valueBinding() == null && ip.token() == null) {
            error("JRL040", "Empty Input pattern has no effect", ip.sourceRange(),
                    "Add 'from:', 'value:', or 'token:' fields");
        }
        // C2: Input value binding only supported with 'from: ownAddress'
        if (ip.valueBinding() != null && ip.from() != null
                && !(ip.from() instanceof Expression.OwnAddressExpr)) {
            error("JRL046", "Input value binding is only supported with 'from: ownAddress'",
                    ip.sourceRange(),
                    "Use 'Input( from: ownAddress, value: $val )' or remove the value binding");
        }
        // C2: Input(from: expr) without token or value is not useful
        if (ip.from() != null && !(ip.from() instanceof Expression.OwnAddressExpr)
                && ip.token() == null && ip.valueBinding() == null) {
            error("JRL047", "Input( from: expr ) without 'token:' or 'value:' is not yet supported",
                    ip.sourceRange(),
                    "Add a token constraint, e.g. Input( from: addr, token: contains(policy, name, 1) )");
        }
        if (ip.from() != null) checkExprVars(ip.from(), boundVars, paramNames, purpose);
        if (ip.token() != null) checkValueConstraintVars(ip.token(), boundVars, paramNames, purpose);
        // valueBinding introduces a new variable
        if (ip.valueBinding() != null) boundVars.add(ip.valueBinding());
    }

    private void checkMintPattern(FactPattern.MintPattern mp, Set<String> boundVars,
                                   Set<String> paramNames, PurposeType purpose) {
        if (mp.policy() == null) {
            error("JRL041", "Mint pattern requires 'policy:' field", mp.sourceRange(),
                    "Add policy, e.g. Mint( policy: ownPolicyId, token: \"MyToken\", amount: $amt )");
        }
        // H2: Mint requires at least one of token/amount/burned to have an effect
        if (mp.token() == null && mp.amountBinding() == null && !mp.burned()) {
            error("JRL044", "Mint pattern requires 'token:', 'amount:', or 'burned'",
                    mp.sourceRange(),
                    "Add a constraint, e.g. Mint( policy: ownPolicyId, token: \"MyToken\", amount: $amt )");
        }
        // H3: burned and amount are mutually exclusive
        if (mp.burned() && mp.amountBinding() != null) {
            error("JRL045", "'burned' and 'amount:' are mutually exclusive in Mint pattern",
                    mp.sourceRange(),
                    "Use 'burned' to check for negative amount, or 'amount: $var' to bind the amount");
        }
        if (mp.policy() != null) checkExprVars(mp.policy(), boundVars, paramNames, purpose);
        if (mp.token() != null) checkExprVars(mp.token(), boundVars, paramNames, purpose);
        // amountBinding introduces a new variable
        if (mp.amountBinding() != null) boundVars.add(mp.amountBinding());
    }

    private void checkContinuingOutputPattern(FactPattern.ContinuingOutputPattern cop,
                                               Set<String> boundVars, Set<String> paramNames,
                                               PurposeType purpose) {
        if (purpose != null && purpose != PurposeType.SPENDING) {
            error("JRL042", "'ContinuingOutput' is only available in spending validators",
                    cop.sourceRange());
        }
        if (cop.value() == null && cop.datum() == null) {
            error("JRL043", "ContinuingOutput pattern requires 'value:' or 'datum:' field",
                    cop.sourceRange(),
                    "Add a constraint, e.g. ContinuingOutput( value: minADA(2000000) )");
        }
        if (cop.value() != null) checkValueConstraintVars(cop.value(), boundVars, paramNames, purpose);
        if (cop.datum() != null) checkDatumConstraintVars(cop.datum(), boundVars, paramNames, purpose);
    }

    private void checkDatumConstraintVars(DatumConstraint dc, Set<String> boundVars,
                                           Set<String> paramNames, PurposeType purpose) {
        if (!declaredTypes.contains(dc.typeName())) {
            // No source range on DatumConstraint — just validate the type name
            diagnostics.add(JrlDiagnostic.error("JRL002",
                    "Unknown type '" + dc.typeName() + "' in datum constraint", null));
        }
        for (var field : dc.fields()) {
            checkExprVars(field.value(), boundVars, paramNames, purpose);
        }
    }

    private void collectBindings(VariantMatch match, Set<String> boundVars) {
        for (var fm : match.fields()) {
            switch (fm.value()) {
                case MatchValue.Binding b -> boundVars.add(b.varName());
                case MatchValue.NestedMatch nm -> {
                    // C4: Nested match values not yet supported in transpiler
                    error("JRL051", "Nested match values in datum/redeemer patterns are not yet supported",
                            fm.sourceRange(),
                            "Use a $variable binding and extract fields in a separate pattern");
                    for (var inner : nm.fields()) {
                        collectBindingsFromMatch(inner, boundVars);
                    }
                }
                case MatchValue.Literal lit -> {
                    // C4: Literal match values not yet supported in transpiler
                    error("JRL050", "Literal match values in datum/redeemer patterns are not yet supported",
                            fm.sourceRange(),
                            "Use a $variable binding and a Condition() check instead");
                }
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
                    error("JRL003", "Undefined variable '$" + vr.name() + "'",
                            vr.sourceRange(),
                            "Bind it in a Redeemer(...) or Datum(...) pattern first");
                }
            }
            case Expression.IdentRefExpr ir -> {
                if (!paramNames.contains(ir.name()) && !declaredTypes.contains(ir.name())) {
                    error("JRL003", "Unknown identifier '" + ir.name() + "'",
                            ir.sourceRange(),
                            "Is this a param? Declare it in 'params:'");
                }
            }
            case Expression.OwnAddressExpr oa -> {
                if (purpose != null && purpose != PurposeType.SPENDING) {
                    error("JRL007", "'ownAddress' is only available in spending validators",
                            oa.sourceRange());
                }
            }
            case Expression.OwnPolicyIdExpr op -> {
                if (purpose != null && purpose != PurposeType.MINTING) {
                    error("JRL008", "'ownPolicyId' is only available in minting validators",
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
                    error("JRL011", "Unknown function '" + fc.name() + "'",
                            fc.sourceRange());
                } else if (fc.args().size() != sig.get().argTypes().length) {
                    error("JRL004", "Function '" + fc.name() + "' expects "
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
        diagnostics.add(JrlDiagnostic.error(code, message, range));
    }

    private void error(String code, String message, SourceRange range, String suggestion) {
        diagnostics.add(JrlDiagnostic.error(code, message, range, suggestion));
    }
}
