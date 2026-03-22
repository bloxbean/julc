package com.bloxbean.cardano.julc.crl.ast;

import java.util.List;

/**
 * A variant (constructor) in a sealed-interface-style redeemer declaration.
 */
public record VariantNode(String name, List<FieldNode> fields, SourceRange sourceRange) {}
