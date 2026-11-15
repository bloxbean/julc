package org.julclang.compiler;

import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A byte oracle that allows only ADR-075's change (issue #328): two programs are the same up to the order of their
 * {@code let}s when they differ, if at all, only in the order of the bindings of a run of {@code let}s, every binding
 * and every dependency between them kept.
 * <p>
 * The goldens captured before ADR-075 bind library methods in the order they were registered; the compiler now binds
 * them in a canonical order. A test that held a program byte-identical to such a golden holds it to this instead:
 * every other difference, in any term, any constant or any variable, still fails it.
 * <p>
 * FLAT keeps no names, so a binder of a run is named by its value: a digest of the value written out with the run's
 * earlier binders named the same way. Each run is written sorted by those names, a variable bound by a run as its
 * binder's name, and any other variable as its de Bruijn index counted without the binders of runs, which reordering a
 * run does not change.
 */
public final class LibraryOrderEquivalence {

    private LibraryOrderEquivalence() {
    }

    /** {@code actual} is {@code expected}, or differs from it only in the order of a run of {@code let}s. */
    public static void assertSameUpToLibraryOrder(Program expected, Program actual, String label) {
        if (Arrays.equals(UplcFlatEncoder.encodeProgram(expected), UplcFlatEncoder.encodeProgram(actual))) return;
        assertEquals(canonical(expected), canonical(actual), label + " (the same up to the order of library bindings, ADR-075)");
    }

    /** Whether the two programs are the same up to the order of a run of {@code let}s. */
    public static boolean sameUpToLibraryOrder(Program a, Program b) {
        return Arrays.equals(UplcFlatEncoder.encodeProgram(a), UplcFlatEncoder.encodeProgram(b))
                || canonical(a).equals(canonical(b));
    }

    static String canonical(Program program) {
        var text = new StringBuilder(program.versionString()).append(' ');
        write(program.term(), new ArrayList<>(), text);
        return text.toString();
    }

    /** A binder in scope: a run's, named by its value, or any other ({@code name} null). */
    private record Binder(String name) {}

    private static String digest(String text) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Programs in tests are small, so this recurses. */
    private static void write(Term term, List<Binder> scope, StringBuilder text) {
        if (term instanceof Term.Apply(Term.Lam(var first, var ignored), var ignoredValue)) {
            // A run of lets: each value in the scope it has, its binder named by it, the run sorted, then the body.
            var run = new ArrayList<String>();
            int bound = 0;
            Term rest = term;
            while (rest instanceof Term.Apply(Term.Lam(var name, var body), var value)) {
                var valueText = new StringBuilder();
                write(value, scope, valueText);
                String binder = "b" + digest(valueText.toString());
                run.add(binder + "=" + valueText);
                scope.add(new Binder(binder));
                bound++;
                rest = body;
            }
            text.append("(lets");
            run.sort(null);
            for (var binding : run) text.append(' ').append(binding);
            text.append(" in ");
            write(rest, scope, text);
            text.append(')');
            for (int i = 0; i < bound; i++) scope.removeLast();
            return;
        }
        switch (term) {
            case Term.Var(var name) -> {
                int position = scope.size() - name.index();
                if (position < 0 || position >= scope.size()) {
                    text.append("(free ").append(name.index()).append(')');
                    return;
                }
                var binder = scope.get(position);
                if (binder.name() != null) {
                    text.append('@').append(binder.name());
                } else {
                    int index = 0;
                    for (int i = scope.size() - 1; i >= position; i--) if (scope.get(i).name() == null) index++;
                    text.append('v').append(index);
                }
            }
            case Term.Lam(var name, var body) -> {
                text.append("(\\");
                scope.add(new Binder(null));
                write(body, scope, text);
                scope.removeLast();
                text.append(')');
            }
            case Term.Apply(var function, var argument) -> {
                text.append('[');
                write(function, scope, text);
                text.append(' ');
                write(argument, scope, text);
                text.append(']');
            }
            case Term.Force(var inner) -> {
                text.append("(force ");
                write(inner, scope, text);
                text.append(')');
            }
            case Term.Delay(var inner) -> {
                text.append("(delay ");
                write(inner, scope, text);
                text.append(')');
            }
            case Term.Constr constr -> {
                text.append("(constr ").append(constr.tag());
                for (var field : constr.fields()) {
                    text.append(' ');
                    write(field, scope, text);
                }
                text.append(')');
            }
            case Term.Case match -> {
                text.append("(case ");
                write(match.scrutinee(), scope, text);
                for (var branch : match.branches()) {
                    text.append(' ');
                    write(branch, scope, text);
                }
                text.append(')');
            }
            case Term.Const constant -> text.append("(const ")
                    .append(HexFormat.of().formatHex(UplcFlatEncoder.encodeProgram(Program.plutusV3(constant)))).append(')');
            case Term.Builtin builtin -> text.append(builtin.fun());
            case Term.Error _ -> text.append("(error)");
        }
    }
}
