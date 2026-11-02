package org.julclang.compiler.frontend.javac.lower;

import javax.lang.model.element.Element;
import java.util.List;
import java.util.Set;

/**
 * The {@code switch} arms around a point, innermost first: an immutable chain, as {@link Scope} is. An arm decodes its
 * variant's fields once, and {@code x.f()} on its pattern variable reads the decoded field and not the Data again.
 * <p>
 * The reuse is sound only where {@link #binding} says. An unguarded arm's fields have fixed names, so an inner arm that
 * destructured a field of that name stands between, and the outer read extracts the field again (#47). A guarded switch's
 * names are chosen against what is visible ({@link Switches}), so its pattern variable reuses them under any inner arm.
 */
final class PatternScope {

    static final PatternScope NONE = new PatternScope(null, Set.of(), List.of(), false, null);

    private final Element variable;
    private final Set<String> fields;
    private final List<String> bindings;
    private final boolean hygienic;
    private final PatternScope outer;

    private PatternScope(Element variable, Set<String> fields, List<String> bindings, boolean hygienic, PatternScope outer) {
        this.variable = variable;
        this.fields = fields;
        this.bindings = bindings;
        this.hygienic = hygienic;
        this.outer = outer;
    }

    /** This scope inside an arm whose pattern variable is {@code variable}, with {@code bindings} by field index. */
    PatternScope arm(Element variable, Set<String> fields, List<String> bindings, boolean hygienic) {
        return new PatternScope(variable, fields, List.copyOf(bindings), hygienic, this);
    }

    /** The name that holds field {@code index}, called {@code field}, of {@code variable}, or null if it is read from the Data. */
    String binding(Element variable, String field, int index) {
        for (var s = this; s != NONE; s = s.outer) {
            if (s.hygienic && s.variable.equals(variable) && s.fields.contains(field)) return s.bindings.get(index);
        }
        for (var s = this; s != NONE; s = s.outer) {
            if (s.fields.contains(field)) return s.variable.equals(variable) ? s.bindings.get(index) : null;
        }
        return null;
    }

    /** Adds every name these arms hold to {@code names}. */
    void addNamesTo(Set<String> names) {
        for (var s = this; s != NONE; s = s.outer) names.addAll(s.bindings);
    }
}
