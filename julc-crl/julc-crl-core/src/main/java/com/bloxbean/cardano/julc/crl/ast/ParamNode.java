package com.bloxbean.cardano.julc.crl.ast;

/**
 * A contract parameter declaration (baked into the script via partial application).
 */
public record ParamNode(String name, TypeRef type, SourceRange sourceRange) {}
