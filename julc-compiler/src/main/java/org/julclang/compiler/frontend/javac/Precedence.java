package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.error.DiagnosticCodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * ADR-068 D8's precedence, decided before javac runs, over every top-level declaration of every unit: roots first,
 * then project units, then JAR units ({@link JavacFrontend.Origin}, carried by each unit of the compilation). javac
 * reads a compilation unit whole, so precedence selects or drops <em>units</em> ("Whole units", D8 as amended for
 * F520):
 * <ul>
 *   <li>A pool unit that declares a name a unit of higher precedence declares is <em>shadowed</em>: it is left off
 *       javac's source path entirely, so javac never parses or enters it. If javac then reports an error, each
 *       shadowed unit gets a note naming it and the declaration that takes precedence; javac's own diagnostics are
 *       kept exactly as they are (JuLC does not re-resolve Java names to attribute them).</li>
 *   <li>Two units of equal standing (both project, or both JAR) that declare one qualified name are an error before
 *       javac runs, whether or not the name is referenced.</li>
 * </ul>
 * The bundled stdlib sources are JAR units after the host's; a host unit declaring a bundled name is {@code JULC0081}
 * already. The outcome depends only on the compilation's own inputs, never on the pool's order.
 */
final class Precedence {

    static final String KEY = "compiler.err.duplicate.class";

    /** A shadowed unit, the qualified name it shares with a unit of higher precedence, and that unit. */
    record Shadowed(JavacSourceFile unit, String name, JavacSourceFile winner) {
    }

    private final List<JavacSourceFile> listed;
    private final List<Shadowed> shadowed;
    private final List<JavacDiagnostic> duplicates;

    private Precedence(List<JavacSourceFile> listed, List<Shadowed> shadowed, List<JavacDiagnostic> duplicates) {
        this.listed = listed;
        this.shadowed = shadowed;
        this.duplicates = duplicates;
    }

    /** {@code origins} gives each pool unit's origin, by position. */
    static Precedence of(List<JavacSourceFile> roots, List<JavacSourceFile> pool, List<JavacFrontend.Origin> origins) {
        var winners = new LinkedHashMap<String, JavacSourceFile>();   // name -> the unit of highest precedence
        for (var unit : roots) for (String name : unit.topLevelNames()) winners.putIfAbsent(name, unit);
        var shadowed = new ArrayList<Shadowed>();
        var kept = new ArrayList<JavacSourceFile>();
        var duplicates = new ArrayList<JavacDiagnostic>();
        for (var origin : List.of(JavacFrontend.Origin.PROJECT, JavacFrontend.Origin.JAR)) {
            var level = new ArrayList<JavacSourceFile>();
            for (int i = 0; i < pool.size(); i++) {
                if (origins.get(i) != origin) continue;
                var unit = pool.get(i);
                var name = unit.topLevelNames().stream().filter(winners::containsKey).findFirst();
                if (name.isPresent()) shadowed.add(new Shadowed(unit, name.get(), winners.get(name.get())));
                else level.add(unit);
            }
            // Equal standing: a name two units of this level declare is an error.
            var declarers = new LinkedHashMap<String, List<JavacSourceFile>>();
            for (var unit : level) {
                for (String n : unit.topLevelNames()) declarers.computeIfAbsent(n, k -> new ArrayList<>()).add(unit);
            }
            declarers.forEach((n, units) -> {
                if (units.size() > 1) duplicates.add(duplicate(n, units));
            });
            for (var unit : level) for (String n : unit.topLevelNames()) winners.putIfAbsent(n, unit);
            kept.addAll(level);
        }
        var listed = new ArrayList<JavacSourceFile>();
        Set<String> taken = new HashSet<>();
        for (var unit : roots) taken.addAll(unit.topLevelNames());
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
        return new Precedence(List.copyOf(listed), List.copyOf(shadowed), List.copyOf(duplicates));
    }

    private static JavacDiagnostic duplicate(String name, List<JavacSourceFile> declarers) {
        String message = "duplicate class: " + name + " (declared by "
                + String.join(" and ", declarers.stream().map(JavacSourceFile::displayName).toList())
                + "; two library sources of equal standing declare it)";
        return JavacDiagnostic.of(DiagnosticCodes.JAVAC_ERROR, KEY, DiagnosticCodes.JAVAC_ERROR.format(message, KEY),
                declarers.get(1).displayName(), 1, 1, -1, -1, "A qualified name may be declared by one source of a"
                        + " compilation only: rename one of the types, or remove one of the sources (ADR-068 D8)");
    }

    /** The source-path entries: a unit per listed name. Shadowed units are not among them. */
    List<JavacSourceFile> listed() {
        return listed;
    }

    /** The shadowed pool units, which javac does not read. */
    List<Shadowed> shadowed() {
        return shadowed;
    }

    /** Units of equal standing declaring one name: rejected before javac runs. */
    List<JavacDiagnostic> duplicates() {
        return duplicates;
    }

    /** One note per shadowed unit, naming it and the declaration that takes precedence (added when javac rejects). */
    List<JavacDiagnostic> notes() {
        var notes = new ArrayList<JavacDiagnostic>();
        for (var s : shadowed) {
            String file = s.unit().displayName();
            notes.add(new JavacDiagnostic(DiagnosticCodes.JAVAC_ERROR.code(), null, file + " was not read: it declares "
                    + s.name() + ", also declared by " + s.winner().displayName() + ", which takes precedence (ADR-068"
                    + " D8). If the program needs another declaration of " + file + ", remove or rename the duplicate.",
                    file, 0, 0, -1, -1, null));
        }
        return notes;
    }

    /** The identities of the shadowed units' texts: what an enforced compilation leaves out of JuLC's pool too. */
    Set<String> droppedIdentities() {
        var dropped = new HashSet<String>();
        for (var s : shadowed) dropped.add(JavacPlatform.identity(s.unit().text()));
        return dropped;
    }
}
