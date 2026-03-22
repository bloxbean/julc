package com.bloxbean.cardano.julc.jrl.ast;

import java.util.List;

/**
 * Datum type declaration for a spending validator.
 */
public record DatumDeclNode(String name, List<FieldNode> fields, SourceRange sourceRange) {}
