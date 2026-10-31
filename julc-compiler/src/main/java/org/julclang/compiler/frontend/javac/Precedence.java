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
 * ADR-068 D8's precedence, per qualified name, decided before javac runs. Each top-level name of a pool unit is
 * listed on the source path unless a root declares it or an earlier pool unit already lists it; so every name that
 * no other unit declares stays visible, and javac never sees a name twice through the source path.
 * <p>
 * javac reads a unit whole: it cannot serve one declaration of a unit and not another. A pool unit that declares a
 * <em>contested</em> name (one a root or another pool unit also declares) is therefore safe only while javac does
 * not read it. When it does, the compilation fails closed with {@code duplicate class}, naming the contested name
 * and the units that declare it: the Java meaning of the same sources compiled together. The outcome does not depend
 * on the pool's order: whichever unit lists a contested name, reading either unit fails. (Provenance precedence,
 * same-project sources over JAR sources, is the host's, applied when it builds the pool; a pool holds one unit per
 * primary name.)
 */
final class Precedence {

    private final List<JavacSourceFile> listed;
    /** Contested name -> every unit declaring it, roots first, in order. */
    private final Map<String, List<JavacSourceFile>> contested;
    private final Set<URI> poolUris;

    private Precedence(List<JavacSourceFile> listed, Map<String, List<JavacSourceFile>> contested, Set<URI> poolUris) {
        this.listed = listed;
        this.contested = contested;
        this.poolUris = poolUris;
    }

    static Precedence of(List<JavacSourceFile> roots, List<JavacSourceFile> pool) {
        var declarers = new LinkedHashMap<String, List<JavacSourceFile>>();
        for (var unit : roots) for (String name : unit.topLevelNames()) declarers.computeIfAbsent(name, n -> new ArrayList<>()).add(unit);
        var rootNames = new HashSet<>(declarers.keySet());
        for (var unit : pool) for (String name : unit.topLevelNames()) declarers.computeIfAbsent(name, n -> new ArrayList<>()).add(unit);
        var contested = new LinkedHashMap<String, List<JavacSourceFile>>();
        declarers.forEach((name, units) -> {
            if (units.size() > 1) contested.put(name, List.copyOf(units));
        });
        var listed = new ArrayList<JavacSourceFile>();
        var taken = new HashSet<>(rootNames);
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
        var poolUris = new HashSet<URI>();
        for (var unit : pool) poolUris.add(unit.toUri());
        return new Precedence(List.copyOf(listed), contested, poolUris);
    }

    /** The source-path entries: a unit per listed name. */
    List<JavacSourceFile> listed() {
        return listed;
    }

    /**
     * {@code duplicate class} for each contested name that a unit javac read declares, unless javac reported it
     * already; in the order of the contested names.
     */
    List<JavacDiagnostic> conflicts(Collection<URI> read, List<JavacDiagnostic> reported) {
        var result = new ArrayList<JavacDiagnostic>();
        contested.forEach((name, units) -> {
            var readPoolUnits = units.stream().filter(u -> poolUris.contains(u.toUri()) && read.contains(u.toUri()))
                    .toList();
            if (readPoolUnits.isEmpty() || reportedAlready(name, reported)) return;
            String message = "duplicate class: " + name + " (declared by "
                    + String.join(" and ", units.stream().map(JavacSourceFile::displayName).toList())
                    + "; javac cannot read one declaration of a source without the others)";
            String file = readPoolUnits.getFirst().displayName();
            result.add(JavacDiagnostic.of(DiagnosticCodes.JAVAC_ERROR, KEY,
                    DiagnosticCodes.JAVAC_ERROR.format(message, KEY), file, 1, 1, -1, -1,
                    "A qualified name may be declared by one source of a compilation only: rename one of the types,"
                            + " or remove the duplicate source (ADR-068 D8)"));
        });
        return result;
    }

    static final String KEY = "compiler.err.duplicate.class";

    private static boolean reportedAlready(String name, List<JavacDiagnostic> reported) {
        return reported.stream().anyMatch(d -> KEY.equals(d.key())
                && d.message().startsWith("duplicate class: " + name + " ("));
    }
}
