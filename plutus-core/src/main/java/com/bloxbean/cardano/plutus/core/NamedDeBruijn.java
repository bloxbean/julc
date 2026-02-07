package com.bloxbean.cardano.plutus.core;

/**
 * A named De Bruijn index for UPLC variables.
 * <p>
 * In UPLC, variables are identified by De Bruijn indices (integers that count
 * the number of enclosing lambda abstractions). The name is optional and used
 * only for debugging/pretty-printing.
 *
 * @param name  the optional name (for debugging)
 * @param index the De Bruijn index (0-based)
 */
public record NamedDeBruijn(String name, int index) {

    public NamedDeBruijn {
        if (index < 0) throw new IllegalArgumentException("De Bruijn index must be non-negative: " + index);
    }

    public NamedDeBruijn(int index) {
        this("i" + index, index);
    }

    @Override
    public String toString() {
        return name + "_" + index;
    }
}
