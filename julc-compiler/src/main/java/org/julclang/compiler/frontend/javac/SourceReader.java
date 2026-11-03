package org.julclang.compiler.frontend.javac;

/**
 * Which parser JuLC's tools read Java source with while both exist (ADR-068 M6): JavaParser's, through
 * {@code JavaSourceParser}, or javac's, through {@link JavacSyntax}. Each tool has one {@code DEFAULT_READER} from
 * {@link #configured()}; M5 makes javac the default, and M6 deletes this type with the JavaParser readings.
 */
public enum SourceReader {
    JAVAPARSER, JAVAC;

    /** The system property that chooses the reader, {@code JAVAPARSER} or {@code JAVAC}, for every tool (tests). */
    public static final String PROPERTY = "julc.tools.reader";

    /** The reader {@link #PROPERTY} names; JavaParser's when it is unset, until ADR-068 M5. */
    public static SourceReader configured() {
        String value = System.getProperty(PROPERTY, JAVAPARSER.name()).trim();
        for (SourceReader reader : values()) {
            if (reader.name().equalsIgnoreCase(value)) return reader;
        }
        throw new IllegalArgumentException(PROPERTY + " must be JAVAPARSER or JAVAC, not '" + value + "'");
    }
}
