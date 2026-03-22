package com.bloxbean.cardano.julc.crl.ast;

import java.util.List;

/**
 * A CRL rule: {@code rule "name" when ... then allow|deny}.
 */
public record RuleNode(
        String name,
        List<FactPattern> patterns,
        DefaultAction action,
        String traceMessage,    // nullable — optional trace string
        SourceRange sourceRange
) {}
