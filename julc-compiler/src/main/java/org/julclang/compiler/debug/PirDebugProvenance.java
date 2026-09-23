package org.julclang.compiler.debug;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.pir.PirTerm;

import java.util.IdentityHashMap;
import java.util.Map;

/** Optional identity side table used only by explicit Java-locals compilation. */
public final class PirDebugProvenance {
    public record Association(String bindingId, String sourceName, String scopeId,
                              String typeId, String layoutId) {}

    private final IdentityHashMap<PirTerm, Association> binders = new IdentityHashMap<>();
    private final Map<String, String> unavailableReasons = new java.util.LinkedHashMap<>();

    public void associate(PirTerm binder, Association association) {
        if (!isMatchingBinder(binder, association.sourceName())) {
            throw new CompilerException("Invalid Java debug binder association for " + association.bindingId());
        }
        Association previous = binders.put(binder, association);
        if (previous != null && !previous.equals(association)) {
            throw new CompilerException("Conflicting Java debug binder association for " + association.bindingId());
        }
    }

    public Association association(PirTerm binder) {
        return binders.get(binder);
    }

    /** Explicit preserve/replace/drop contract for every PIR rebuilding pass. */
    public void transfer(PirTerm original, PirTerm replacement, String pass) {
        Association association = binders.get(original);
        if (association == null || original == replacement) return;
        if (sameBinder(original, replacement, association.sourceName())) {
            Association previous = binders.put(replacement, association);
            if (previous != null && !previous.equals(association)) {
                throw new CompilerException("Ambiguous Java debug provenance after " + pass);
            }
        } else {
            unavailableReasons.putIfAbsent(association.bindingId(), "unsupported lowering in " + pass);
        }
    }

    public Map<String, String> unavailableReasons() {
        return Map.copyOf(unavailableReasons);
    }

    private static boolean sameBinder(PirTerm original, PirTerm replacement, String name) {
        return original instanceof PirTerm.Lam && replacement instanceof PirTerm.Lam
                && isMatchingBinder(replacement, name)
                || original instanceof PirTerm.Let && replacement instanceof PirTerm.Let
                && isMatchingBinder(replacement, name);
    }

    private static boolean isMatchingBinder(PirTerm term, String name) {
        return term instanceof PirTerm.Lam lambda && lambda.param().equals(name)
                || term instanceof PirTerm.Let let && let.name().equals(name);
    }
}
