package com.bloxbean.cardano.julc.stdlib;

import com.bloxbean.cardano.julc.compiler.pir.PirHofBuilders;
import com.bloxbean.cardano.julc.compiler.pir.PirTerm;

/**
 * Higher-order function (HOF) list operations built as PIR term builders.
 * <p>
 * Delegates to {@link PirHofBuilders} which contains the pure PIR construction logic.
 * This class exists in {@code julc-stdlib} for backward compatibility with
 * {@link StdlibRegistry} registrations.
 */
public final class ListsLibHof {

    private ListsLibHof() {}

    public static PirTerm any(PirTerm list, PirTerm predicate) {
        return PirHofBuilders.any(list, predicate);
    }

    public static PirTerm all(PirTerm list, PirTerm predicate) {
        return PirHofBuilders.all(list, predicate);
    }

    public static PirTerm find(PirTerm list, PirTerm predicate) {
        return PirHofBuilders.find(list, predicate);
    }

    public static PirTerm foldl(PirTerm f, PirTerm init, PirTerm list) {
        return PirHofBuilders.foldl(f, init, list);
    }

    public static PirTerm map(PirTerm list, PirTerm f) {
        return PirHofBuilders.map(list, f);
    }

    public static PirTerm filter(PirTerm list, PirTerm predicate) {
        return PirHofBuilders.filter(list, predicate);
    }

    public static PirTerm zip(PirTerm a, PirTerm b) {
        return PirHofBuilders.zip(a, b);
    }
}
