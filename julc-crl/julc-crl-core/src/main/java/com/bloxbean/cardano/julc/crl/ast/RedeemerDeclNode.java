package com.bloxbean.cardano.julc.crl.ast;

import java.util.List;

/**
 * Redeemer type declaration. Either variant-based (sealed interface with variants)
 * or record-based (simple record with fields), but not both.
 */
public record RedeemerDeclNode(
        String name,
        List<VariantNode> variants,  // non-empty for sealed interface style
        List<FieldNode> fields,      // non-empty for simple record style
        SourceRange sourceRange
) {
    public boolean isVariantStyle() {
        return variants != null && !variants.isEmpty();
    }
}
