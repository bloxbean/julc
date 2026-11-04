package org.julclang.compiler;

import org.julclang.compiler.frontend.javac.SourceReader;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LibrarySourceResolver} reads a source alike on JavaParser's and javac's parser (ADR-068 M6), but for one fix. */
class LibrarySourceResolverReadersTest {

    private static final String SOURCE = """
            package com.example;

            import org.julclang.stdlib.lib.ListsLib;
            import com.example.lib.*;

            enum Kind {
                A(Fees.base());
                Kind(Object o) { }
                static Object late() { return Rates.one(); }
            }

            class V {
                static Object m(Object x) {
                    return switch (x) {
                        case String s when Guard.ok(s) -> Body.of(s);
                        default -> ListsLib.empty();
                    };
                }
            }
            """;

    @Test
    void bothReadersListTheSameReferencesInTheSameOrder() {
        assertEquals(LibrarySourceResolver.describeSourceContext(SOURCE, SourceReader.JAVAPARSER),
                LibrarySourceResolver.describeSourceContext(SOURCE, SourceReader.JAVAC));
        assertEquals(Optional.of("Kind"), LibrarySourceResolver.extractTopLevelTypeName(SOURCE, SourceReader.JAVAC));
    }

    /**
     * JavaParser attaches an end-of-line comment to a node near it and prints the comment into the qualifier's text, so
     * the reference is lost and the library may not be offered; javac's reading keeps every one.
     */
    @Test
    void aCommentNearAQualifierDoesNotHideTheReference() {
        String source = """
                class V {
                    static byte[] m() {
                        // "Hi" as chained calls
                        return Builtins.consByteString(72,   // H
                                Builtins.consByteString(105,  // i
                                Builtins.emptyByteString()));
                    }
                }
                """;
        long onJavaParser = count(LibrarySourceResolver.describeSourceContext(source, SourceReader.JAVAPARSER));
        long onJavac = count(LibrarySourceResolver.describeSourceContext(source, SourceReader.JAVAC));
        assertEquals(3, onJavac);
        assertTrue(onJavaParser < onJavac, onJavaParser + " of " + onJavac);
    }

    private static long count(String context) {
        return context.split("simpleName=Builtins", -1).length - 1;
    }
}
