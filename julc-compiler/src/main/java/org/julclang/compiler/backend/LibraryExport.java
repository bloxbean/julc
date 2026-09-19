package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirType;

/** A concrete export. Generic schemes are deliberately not erased to Data. */
public record LibraryExport(String symbol, int arity, PirType type) {}
