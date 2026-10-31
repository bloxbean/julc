package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.error.DiagnosticCodes;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-068 D8's precedence, decided before javac runs, over every top-level declaration of every unit. javac reads a
 * compilation unit whole, so precedence selects or drops <em>units</em> by every name each declares ("Whole units",
 * D8 as amended for F520):
 * <ul>
 *   <li><b>Roots over the pool.</b> A pool unit that declares a name a root declares is <em>shadowed</em>: the
 *       root's declaration wins, and the pool unit is not read for that name. If the program needs another
 *       declaration of the shadowed unit, javac must read it, and the compilation is rejected with
 *       {@code duplicate class}, naming both declarations, as javac rejects the same pair of files in the user's own
 *       build.</li>
 *   <li><b>Pool units of equal standing.</b> Two pool units that declare one qualified name are an error before
 *       javac runs, whether or not the name is referenced. The gate's pool carries no origin: a host applies its own
 *       precedence (same-project sources over JAR sources, D8) when it builds the pool, keyed by primary name, so
 *       every unit a host passes is of equal standing; the bundled stdlib sources come after them, and a host unit
 *       declaring a bundled name is {@code JULC0081} (ownership) already.</li>
 * </ul>
 * No declaration is silently hidden, and no outcome depends on the pool's order.
 */
final class Precedence {

    static final String KEY = "compiler.err.duplicate.class";

    private final List<JavacSourceFile> listed;
    /** Root-declared name -> its root, then the pool units it shadows. */
    private final Map<String, List<JavacSourceFile>> shadowed;
    private final List<JavacDiagnostic> duplicates;

    private Precedence(List<JavacSourceFile> listed, Map<String, List<JavacSourceFile>> shadowed,
                       List<JavacDiagnostic> duplicates) {
        this.listed = listed;
        this.shadowed = shadowed;
        this.duplicates = duplicates;
    }

    static Precedence of(List<JavacSourceFile> roots, List<JavacSourceFile> pool) {
        var rootDeclarers = new LinkedHashMap<String, JavacSourceFile>();
        for (var unit : roots) for (String name : unit.topLevelNames()) rootDeclarers.putIfAbsent(name, unit);
        var poolDeclarers = new LinkedHashMap<String, List<JavacSourceFile>>();
        for (var unit : pool) {
            for (String name : unit.topLevelNames()) poolDeclarers.computeIfAbsent(name, n -> new ArrayList<>()).add(unit);
        }
        var shadowed = new LinkedHashMap<String, List<JavacSourceFile>>();
        var duplicates = new ArrayList<JavacDiagnostic>();
        poolDeclarers.forEach((name, units) -> {
            JavacSourceFile root = rootDeclarers.get(name);
            if (root != null) {
                var pair = new ArrayList<JavacSourceFile>();
                pair.add(root);
                pair.addAll(units);
                shadowed.put(name, List.copyOf(pair));
            } else if (units.size() > 1) {
                duplicates.add(duplicate(name, units, units.get(1).displayName(),
                        "two library sources of equal standing declare it"));
            }
        });
        var listed = new ArrayList<JavacSourceFile>();
        var taken = new HashSet<>(rootDeclarers.keySet());
        for (var unit : pool) {
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
        return new Precedence(List.copyOf(listed), shadowed, List.copyOf(duplicates));
    }

    private static JavacDiagnostic duplicate(String name, List<JavacSourceFile> declarers, String fileName,
                                             String why) {
        String message = "duplicate class: " + name + " (declared by "
                + String.join(" and ", declarers.stream().map(JavacSourceFile::displayName).toList()) + "; " + why
                + ")";
        return JavacDiagnostic.of(DiagnosticCodes.JAVAC_ERROR, KEY, DiagnosticCodes.JAVAC_ERROR.format(message, KEY),
                fileName, 1, 1, -1, -1, "A qualified name may be declared by one source of a compilation only:"
                        + " rename one of the types, or remove one of the sources (ADR-068 D8)");
    }

    /** The source-path entries: a unit per listed name. */
    List<JavacSourceFile> listed() {
        return listed;
    }

    /** Pool units of equal standing declaring one name: rejected before javac runs. */
    List<JavacDiagnostic> duplicates() {
        return duplicates;
    }

    /**
     * javac's diagnostics, with a shadowed name made explicit: where javac read a shadowed unit, its
     * {@code duplicate class} names both declarations (javac's own message names only the class); if javac read one
     * and reported nothing for the name, the rejection is added.
     */
    List<JavacDiagnostic> withShadowing(Collection<URI> read, List<JavacDiagnostic> diagnostics) {
        var result = new ArrayList<>(diagnostics);
        Set<URI> readUris = new HashSet<>(read);
        shadowed.forEach((name, declarers) -> {
            var readShadowed = declarers.subList(1, declarers.size()).stream()
                    .filter(u -> readUris.contains(u.toUri())).toList();
            if (readShadowed.isEmpty()) return;
            String why = "the root's declaration shadows the library source's, and the program needs another"
                    + " declaration of that source";
            boolean replaced = false;
            for (int i = 0; i < result.size(); i++) {
                var d = result.get(i);
                if (KEY.equals(d.key()) && javacNames(d, name)) {
                    var named = duplicate(name, declarers, d.fileName(), why);
                    result.set(i, new JavacDiagnostic(named.code(), named.key(), named.message(), d.fileName(),
                            d.line(), d.column(), d.start(), d.end(), named.hint()));
                    replaced = true;
                }
            }
            if (!replaced) result.add(duplicate(name, declarers, readShadowed.getFirst().displayName(), why));
        });
        return result;
    }

    /** Whether javac's own {@code duplicate class} message is about {@code name} (fully or simply qualified). */
    private static boolean javacNames(JavacDiagnostic d, String name) {
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return d.message().startsWith("duplicate class: " + name + " (")
                || d.message().startsWith("duplicate class: " + simple + " (");
    }
}
