package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.error.DiagnosticCodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * ADR-068 D8's precedence, decided before javac runs, over every top-level declaration of every unit. javac reads a
 * compilation unit whole, so precedence selects or drops <em>units</em> by every name each declares ("Whole units",
 * D8 as amended for F520):
 * <ul>
 *   <li><b>Roots over the pool.</b> A pool unit that declares a name a root declares is <em>shadowed</em>: it is left
 *       off javac's source path entirely, so javac never parses or enters it. Its declarations are kept in an index:
 *       where javac then cannot resolve a name that only a shadowed unit declares, that diagnostic becomes D8's
 *       named conflict, citing both declarations and their sources.</li>
 *   <li><b>Pool units of equal standing.</b> Among the pool units that are not shadowed, two that declare one
 *       qualified name are an error before javac runs, whether or not the name is referenced. Each host resolves
 *       same-project over JAR sources over every declared name when it builds its pool
 *       ({@code LibrarySourceResolver.overlay}), so the units a host passes are of equal standing; the bundled
 *       stdlib sources come last, and a host unit declaring a bundled name is {@code JULC0081} already.</li>
 * </ul>
 * No declaration is silently hidden, and no outcome depends on the pool's order.
 */
final class Precedence {

    static final String KEY = "compiler.err.duplicate.class";

    /** A declaration of a shadowed unit: the unit, and the root declaration that shadows the unit. */
    private record Shadowed(JavacSourceFile unit, String rootName, JavacSourceFile root) {
    }

    private final List<JavacSourceFile> listed;
    /** Qualified name -> the shadowed unit declaring it (a name declared by no listed unit or root). */
    private final Map<String, Shadowed> shadowed;
    private final List<JavacDiagnostic> duplicates;

    private Precedence(List<JavacSourceFile> listed, Map<String, Shadowed> shadowed, List<JavacDiagnostic> duplicates) {
        this.listed = listed;
        this.shadowed = shadowed;
        this.duplicates = duplicates;
    }

    static Precedence of(List<JavacSourceFile> roots, List<JavacSourceFile> pool) {
        var rootDeclarers = new LinkedHashMap<String, JavacSourceFile>();
        for (var unit : roots) for (String name : unit.topLevelNames()) rootDeclarers.putIfAbsent(name, unit);
        var shadowedUnits = new LinkedHashMap<JavacSourceFile, String>();   // unit -> the root name it declares
        var kept = new ArrayList<JavacSourceFile>();
        for (var unit : pool) {
            String rootName = unit.topLevelNames().stream().filter(rootDeclarers::containsKey).findFirst().orElse(null);
            if (rootName != null) shadowedUnits.put(unit, rootName);
            else kept.add(unit);
        }
        var poolDeclarers = new LinkedHashMap<String, List<JavacSourceFile>>();
        for (var unit : kept) {
            for (String name : unit.topLevelNames()) poolDeclarers.computeIfAbsent(name, n -> new ArrayList<>()).add(unit);
        }
        var duplicates = new ArrayList<JavacDiagnostic>();
        poolDeclarers.forEach((name, units) -> {
            if (units.size() > 1) {
                duplicates.add(conflict(name, units.stream().map(JavacSourceFile::displayName).toList(),
                        units.get(1).displayName(), 1, 1, -1, -1, "two library sources of equal standing declare it"));
            }
        });
        var listed = new ArrayList<JavacSourceFile>();
        var taken = new HashSet<>(rootDeclarers.keySet());
        for (var unit : kept) {
            if (unit.topLevelNames().isEmpty()) {
                listed.add(unit);   // a malformed unit, under its synthetic name; javac reports it if read
                continue;
            }
            for (String name : unit.topLevelNames()) {
                if (!taken.add(name)) continue;
                String simple = name.substring(name.lastIndexOf('.') + 1);
                listed.add(simple.equals(unit.simpleName()) ? unit : unit.listedAs(simple));
            }
        }
        var shadowed = new LinkedHashMap<String, Shadowed>();
        shadowedUnits.forEach((unit, rootName) -> {
            for (String name : unit.topLevelNames()) {
                if (!taken.contains(name)) shadowed.putIfAbsent(name, new Shadowed(unit, rootName, rootDeclarers.get(rootName)));
            }
        });
        return new Precedence(List.copyOf(listed), shadowed, List.copyOf(duplicates));
    }

    private static JavacDiagnostic conflict(String name, List<String> declaredBy, String fileName, long line,
                                            long column, long start, long end, String why) {
        String message = "duplicate class: " + name + " (declared by " + String.join(" and ", declaredBy) + "; " + why
                + ")";
        return JavacDiagnostic.of(DiagnosticCodes.JAVAC_ERROR, KEY, DiagnosticCodes.JAVAC_ERROR.format(message, KEY),
                fileName, line, column, start, end, "A qualified name may be declared by one source of a compilation"
                        + " only: rename one of the types, or remove one of the sources (ADR-068 D8)");
    }

    /** The source-path entries: a unit per listed name. Shadowed units are not among them. */
    List<JavacSourceFile> listed() {
        return listed;
    }

    /** Pool units of equal standing declaring one name: rejected before javac runs. */
    List<JavacDiagnostic> duplicates() {
        return duplicates;
    }

    /**
     * {@code d} itself, or, when it is javac's failure to resolve a name that only a shadowed unit declares, D8's
     * named conflict at the same position. The name is resolved to its exact qualified name from the unit's scope,
     * as Java resolves a simple name: a single-type import, the qualifier javac names, the unit's own package, then
     * the on-demand imports (one candidate only). Each javac diagnostic is considered once.
     */
    JavacDiagnostic explain(JavacDiagnostic d, JavacHints.UnitContext context) {
        if (shadowed.isEmpty() || d.key() == null) return d;
        String name = null;
        if (d.key().startsWith("compiler.err.cant.resolve")) {
            Matcher symbol = JavacHints.SYMBOL.matcher(d.message());
            if (!symbol.find() || symbol.group(1).equals("method") || symbol.group(1).equals("package")) return d;
            Matcher location = JavacHints.LOCATION_PACKAGE.matcher(d.message());
            name = resolve(symbol.group(2), location.find() ? location.group(1) : null, context);
        } else if (d.key().startsWith("compiler.err.doesnt.exist")) {
            Matcher missing = JavacHints.MISSING_PACKAGE.matcher(d.message());
            if (!missing.find()) return d;
            String pkg = missing.group(1);
            name = context.singleImports().stream().filter(i -> i.startsWith(pkg + ".")
                    && i.lastIndexOf('.') == pkg.length() && shadowed.containsKey(i)).findFirst().orElse(null);
        }
        Shadowed declaration = name == null ? null : shadowed.get(name);
        if (declaration == null) return d;
        return conflict(declaration.rootName(), List.of(declaration.root().displayName(),
                        declaration.unit().displayName()), d.fileName(), d.line(), d.column(), d.start(), d.end(),
                "the root's declaration shadows " + declaration.unit().displayName() + ", which javac therefore"
                        + " does not read, and the program needs its " + name);
    }

    private String resolve(String simple, String qualifier, JavacHints.UnitContext context) {
        if (simple.contains(".")) return simple;
        for (String single : context.singleImports()) {
            if (single.endsWith("." + simple)) return single;
        }
        if (qualifier != null) return qualifier + "." + simple;
        String own = context.packageName().isEmpty() ? simple : context.packageName() + "." + simple;
        if (shadowed.containsKey(own)) return own;
        var onDemand = context.wildcardImports().stream().map(p -> p + "." + simple).filter(shadowed::containsKey)
                .toList();
        return onDemand.size() == 1 ? onDemand.getFirst() : own;
    }
}
