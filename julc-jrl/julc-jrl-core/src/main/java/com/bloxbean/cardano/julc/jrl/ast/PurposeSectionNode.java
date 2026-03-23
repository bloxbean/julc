package com.bloxbean.cardano.julc.jrl.ast;

import java.util.List;

/**
 * A purpose section in a multi-validator JRL contract.
 * Each section has its own redeemer, rules, and default action.
 */
public record PurposeSectionNode(
        PurposeType purpose,
        RedeemerDeclNode redeemer,    // nullable
        List<RuleNode> rules,
        DefaultAction defaultAction,
        SourceRange sourceRange
) {}
