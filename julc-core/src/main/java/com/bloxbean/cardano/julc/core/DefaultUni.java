package com.bloxbean.cardano.julc.core;

/**
 * The universe of built-in types in UPLC.
 * <p>
 * DefaultUni represents the type system for UPLC constants. Each variant
 * corresponds to a built-in type. Compound types (lists, pairs) are
 * constructed via {@link Apply}.
 */
public sealed interface DefaultUni {
    record Integer()     implements DefaultUni {}
    record ByteString()  implements DefaultUni {}
    record String()      implements DefaultUni {}
    record Unit()        implements DefaultUni {}
    record Bool()        implements DefaultUni {}
    record Data()        implements DefaultUni {}
    record ProtoList()   implements DefaultUni {}
    record ProtoPair()   implements DefaultUni {}
    record Bls12_381_G1_Element() implements DefaultUni {}
    record Bls12_381_G2_Element() implements DefaultUni {}
    record Bls12_381_MlResult()   implements DefaultUni {}

    /** Type application, e.g. List(Integer) = Apply(ProtoList, Integer) */
    record Apply(DefaultUni f, DefaultUni arg) implements DefaultUni {}

    // Convenience constructors for compound types
    static DefaultUni listOf(DefaultUni elemType) {
        return new Apply(new ProtoList(), elemType);
    }

    static DefaultUni pairOf(DefaultUni a, DefaultUni b) {
        return new Apply(new Apply(new ProtoPair(), a), b);
    }

    // Singleton instances for base types
    DefaultUni INTEGER     = new Integer();
    DefaultUni BYTESTRING  = new ByteString();
    DefaultUni STRING      = new String();
    DefaultUni UNIT        = new Unit();
    DefaultUni BOOL        = new Bool();
    DefaultUni DATA        = new Data();
    DefaultUni BLS12_381_G1 = new Bls12_381_G1_Element();
    DefaultUni BLS12_381_G2 = new Bls12_381_G2_Element();
    DefaultUni BLS12_381_ML = new Bls12_381_MlResult();
}
