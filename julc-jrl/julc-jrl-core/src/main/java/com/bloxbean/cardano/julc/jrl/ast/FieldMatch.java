package com.bloxbean.cardano.julc.jrl.ast;

/**
 * A field match inside a variant pattern: {@code fieldName: matchValue}.
 */
public record FieldMatch(String fieldName, MatchValue value, SourceRange sourceRange) {}
