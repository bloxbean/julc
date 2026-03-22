package com.bloxbean.cardano.julc.crl.ast;

import java.util.List;

/**
 * A purpose section in a multi-validator CRL contract.
 * Each section has its own redeemer, rules, and default action.
 */
public record PurposeSectionNode(
        PurposeType purpose,
        RedeemerDeclNode redeemer,    // nullable
        List<RuleNode> rules,
        DefaultAction defaultAction,
        SourceRange sourceRange
) {}
