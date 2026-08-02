package com.bloxbean.cardano.julc.vm;

/** Haskell {@code DefaultFunSemanticsVariant} selected by language and protocol. */
public enum BuiltinSemanticsVariant {
    A,
    B,
    C,
    D,
    E;

    /** Variants D/E use Cardano-bounded integer and ByteString unlifting. */
    public boolean usesCardanoBounds() {
        return this == D || this == E;
    }

    /** Variants D/E cost selected Text arguments by UTF-8 byte length. */
    public boolean usesUtf8StringCosting() {
        return this == D || this == E;
    }
}
