package org.julclang.difftest;

import org.julclang.core.PlutusData;

import java.util.List;

/**
 * One program and one input: the source of a public top-level class, the static entry method to
 * run, and its arguments as Data. The JVM side converts each argument to the method's declared
 * parameter type with {@link ValueMapping#fromData}; the UPLC side passes the Data unchanged.
 *
 * @param name      a label for reports and regression files
 * @param source    the complete Java source, imports included
 * @param className the public top-level class that declares the method
 * @param method    the static entry method; it must not be overloaded
 * @param args      the arguments, one per parameter
 */
public record DiffCase(String name, String source, String className, String method, List<PlutusData> args) {

    public DiffCase {
        args = List.copyOf(args);
    }
}
