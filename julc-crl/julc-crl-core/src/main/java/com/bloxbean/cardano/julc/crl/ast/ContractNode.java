package com.bloxbean.cardano.julc.crl.ast;

import java.util.List;

/**
 * Root AST node for a CRL contract.
 * <p>
 * A contract is either single-purpose (with top-level rules and default) or
 * multi-purpose (with purpose sections). The two modes are mutually exclusive.
 */
public record ContractNode(
        String name,
        String version,
        PurposeType purpose,                       // null if multi-validator
        List<ParamNode> params,
        DatumDeclNode datum,                       // nullable
        List<RecordDeclNode> records,
        RedeemerDeclNode redeemer,                 // nullable (single-purpose only)
        List<RuleNode> rules,                      // single-purpose rules
        DefaultAction defaultAction,               // nullable if multi-validator
        List<PurposeSectionNode> purposeSections,  // multi-validator sections
        SourceRange sourceRange
) {
    public boolean isMultiValidator() {
        return purposeSections != null && !purposeSections.isEmpty();
    }
}
