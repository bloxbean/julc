package org.julclang.difftest;

import org.julclang.core.PlutusData;

import java.util.List;
import java.util.Objects;

/**
 * One program and one input: the source of a public top-level class, the static entry method to
 * run, and its arguments as Data. The JVM side converts each argument to the method's declared
 * parameter type with {@link ValueMapping#fromData}; the UPLC side passes the Data unchanged.
 * <p>
 * The tag table and the generation profile are part of the case (ADR-066 D3, D6): the same source and
 * arguments under another tag table are other JVM values, and so another case.
 *
 * @param name      a label for reports and regression files
 * @param source    the complete Java source, imports included
 * @param className the public top-level class that declares the method
 * @param method    the static entry method; it must not be overloaded
 * @param args      the arguments, one per parameter
 * @param tags      the constructor tags of the program's sealed variants; empty when it has none
 * @param profile   the generation profile that produced the program, or null for none (G0, hand-written cases)
 */
public record DiffCase(String name, String source, String className, String method, List<PlutusData> args,
                       TagTable tags, String profile) {

    public DiffCase {
        args = List.copyOf(args);
        Objects.requireNonNull(tags, "tags; TagTable.EMPTY for none");
        if (profile != null && (profile.isBlank() || profile.lines().count() != 1 || !profile.equals(profile.trim()))) {
            throw new HarnessException("A profile is one trimmed line: \"" + profile + "\"");
        }
    }

    /** A case without sealed values or a generation profile. */
    public DiffCase(String name, String source, String className, String method, List<PlutusData> args) {
        this(name, source, className, method, args, TagTable.EMPTY, null);
    }
}
