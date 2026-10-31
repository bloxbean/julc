package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.Tree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.core.source.SourceLocation;

import javax.lang.model.element.Element;
import java.util.IdentityHashMap;

/**
 * The only code that builds a {@code Var}, {@code Lam} or {@code Let} for a source element (ADR-068 I13), and the only
 * recorder of source positions (I7). Both are side tables keyed by term identity, written while the term is built:
 * {@link PirTerm} carries neither, and nothing is renamed or located afterwards.
 */
public final class Terms {

    private final JavacFacts facts;
    private final boolean sourceMaps;
    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final IdentityHashMap<PirTerm, Element> provenance = new IdentityHashMap<>();

    Terms(JavacFacts facts, CompilationContext context) {
        this.facts = facts;
        this.sourceMaps = context.isSourceMapEnabled();
    }

    PirTerm.Var var(Binder binder) {
        return recorded(new PirTerm.Var(binder.name(), binder.type()), binder);
    }

    PirTerm.Lam lam(Binder binder, PirTerm body) {
        return recorded(new PirTerm.Lam(binder.name(), binder.type(), body), binder);
    }

    private <T extends PirTerm> T recorded(T term, Binder binder) {
        provenance.put(term, binder.element());
        return term;
    }

    /** A binder the assembly built for {@code element}, whose source name it is (a static field or {@code @Param}). */
    public void assembled(PirTerm binding, Element element) {
        provenance.put(binding, element);
    }

    /** Records where {@code term} came from, when this compilation has a source map. */
    <T extends PirTerm> T at(Tree tree, T term) {
        if (sourceMaps) positions.put(term, facts.location(tree));
        return term;
    }

    /** The PIR term to Java source location map the backend takes. */
    public IdentityHashMap<PirTerm, SourceLocation> positions() {
        return positions;
    }

    /** Each {@code Var} and binder built for a source element, to that element: I13's construction-time provenance. */
    public IdentityHashMap<PirTerm, Element> provenance() {
        return provenance;
    }
}
