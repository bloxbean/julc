package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.resolve.TypeResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Evidence tooling for ADR-068 M2, never a compilation path: javac's reading of sources it rejects. The registration
 * reads declarations, so a source whose only errors are in method bodies (JuLC's dialect) still has a type model, and
 * its types can be compared with the reference. {@link JavacProgram#analyze} refuses such a source; this does not.
 */
public final class TolerantJavacTypes {

    private TolerantJavacTypes() {
    }

    /** The registry javac's model gives, whatever javac's verdict; an unresolved declaration throws. */
    public static TypeResolver.Registry read(List<String> roots) {
        var frontend = JavacFrontend.shared();
        var attribution = frontend.attribute(roots.stream().map(JavacFrontend.Unit::of).toList(), List.of());
        if (attribution.trees() == null) throw new IllegalStateException("rejected before javac");
        var facts = JavacFacts.of(attribution, new TypeResolver());
        var registrar = new JavacTypeRegistrar(facts);
        registrar.registerLedger(frontend.platform());
        var units = new ArrayList<>(attribution.libraries());
        units.addAll(attribution.roots());
        registrar.registerSources(units);
        return facts.registry().registry();
    }
}
