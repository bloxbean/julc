package org.julclang.jrl.ast;

import java.util.List;

/**
 * Helper record type declaration used inside datum or redeemer types.
 */
public record RecordDeclNode(String name, List<FieldNode> fields, SourceRange sourceRange) {}
