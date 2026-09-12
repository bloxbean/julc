package org.julclang.jrl.ast;

import java.util.List;

/**
 * A variant (constructor) in a sealed-interface-style redeemer declaration.
 */
public record VariantNode(String name, List<FieldNode> fields, SourceRange sourceRange) {}
