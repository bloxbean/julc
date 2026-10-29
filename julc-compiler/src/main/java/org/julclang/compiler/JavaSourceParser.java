package org.julclang.compiler;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

/**
 * How JuLC reads Java source, everywhere it reads it (ADR-062 T24): Java 21, with Unicode escapes
 * translated before anything else, as javac does (JLS 21 §3.3).
 * <p>
 * javac compiles the same source for the JVM, so a reading that differs from javac's makes the
 * script differ from the code its tests run. JavaParser's default configuration leaves Unicode
 * escapes untranslated: an escape is then not a line terminator inside a comment, not the end of a
 * comment, and not the same identifier as the character it denotes. Positions still refer to the
 * source as written.
 * <p>
 * A {@link JavaParser} is not thread-safe, so each call makes a new one.
 */
public final class JavaSourceParser {

    private JavaSourceParser() {
    }

    /** A new configuration; callers may add to it, but must keep its language level and escape translation. */
    public static ParserConfiguration configuration() {
        return new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setPreprocessUnicodeEscapes(true);
    }

    /** A new parser with {@link #configuration()}. */
    public static JavaParser create() {
        return new JavaParser(configuration());
    }

    /** Parses a compilation unit with a new parser. */
    public static ParseResult<CompilationUnit> parse(String source) {
        return create().parse(source);
    }

    /** As {@link #parse}, throwing a {@link ParseProblemException} on failure, as {@code StaticJavaParser.parse} does. */
    public static CompilationUnit parseCompilationUnit(String source) {
        var result = parse(source);
        if (!result.isSuccessful()) throw new ParseProblemException(result.getProblems());
        return result.getResult().orElseThrow();
    }
}
