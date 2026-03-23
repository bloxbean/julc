package com.bloxbean.cardano.julc.jrl.ast;

import java.util.List;

/**
 * A JRL rule: {@code rule "name" when ... then allow|deny}.
 */
public record RuleNode(
        String name,
        List<FactPattern> patterns,
        DefaultAction action,
        String traceMessage,    // nullable — optional trace string
        SourceRange sourceRange
) {}
