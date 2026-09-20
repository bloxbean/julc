package org.julclang.compiler.backend;

import org.julclang.compiler.pir.PirType;

import java.util.List;

/** Nominal source identity is retained even for zero-cost Java @NewType representations. */
public record LibraryType(
        String identity, PirType representation, boolean newType, List<Field> fields,
        List<Constructor> constructors) {
    public LibraryType {
        fields = List.copyOf(fields);
        constructors = List.copyOf(constructors);
    }

    public LibraryType(String identity, PirType representation, boolean newType, List<Field> fields) {
        this(identity, representation, newType, fields, List.of());
    }

    public record Constructor(String name, int tag, List<Field> fields) {
        public Constructor { fields = List.copyOf(fields); }
    }

    public record Reference(String name, List<Reference> arguments) {
        public Reference {
            arguments = List.copyOf(arguments);
        }
    }

    public record Field(String name, Reference type) {}
}
