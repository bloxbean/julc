package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.Tree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.lower.Scope.Binder;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.source.SourceLocation;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import java.util.IdentityHashMap;

/**
 * The only code that builds a {@code Var} or {@code Lam} for a source element (ADR-068 I13), and the only recorder of
 * source positions (I7). Both go in side tables keyed by term identity, written while the term is built: {@link PirTerm}
 * carries neither, and nothing is renamed or located afterwards.
 */
public final class Terms {

    private final JavacFacts facts;
    private final boolean sourceMaps;
    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final Provenance provenance = new Provenance();

    Terms(JavacFacts facts, CompilationContext context) {
        this.facts = facts;
        this.sourceMaps = context.isSourceMapEnabled();
    }

    /**
     * A read of {@code binder}, for {@code element}: what javac bound the name to, not what the scope answered, so I13's
     * check compares two independent sources and a wrong lookup fails it.
     */
    PirTerm.Var var(Element element, Binder binder) {
        var read = new PirTerm.Var(binder.name(), binder.type());
        provenance.read(read, element);
        return read;
    }

    /**
     * A read of a static method's binder, for {@code element}. Unlike a variable's, the binder's name is a function of the
     * method's element, with a dot no variable can have, so no scope can capture it (ADR-060), and an overload is
     * {@code JULC0054} unless it is interchangeable.
     */
    PirTerm.Var method(ExecutableElement element, String binder, PirType type) {
        var read = new PirTerm.Var(binder, type);
        provenance.read(read, facts.binderMethod(element));
        return read;
    }

    PirTerm.Lam lam(Binder binder, PirTerm body) {
        var lambda = new PirTerm.Lam(binder.name(), binder.type(), body);
        provenance.binder(lambda, binder.name(), binder.element());
        return lambda;
    }

    /** A binder the assembly built for {@code element}: a static field's, a {@code @Param}'s or a method's. */
    public void assembled(PirTerm binding, String name, Element element) {
        provenance.binder(binding, name, element);
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

    /** I13's construction-time provenance. */
    public Provenance provenance() {
        return provenance;
    }
}
