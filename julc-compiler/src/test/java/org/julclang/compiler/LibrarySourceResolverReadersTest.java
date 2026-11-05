package org.julclang.compiler;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link LibrarySourceResolver} reads off a source with javac's parser (ADR-068 M6). */
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
    void theTopLevelNameIsTheFirstTypes() {
        assertEquals(Optional.of("Kind"), LibrarySourceResolver.extractTopLevelTypeName(SOURCE));
    }

    /**
     * A comment next to a qualifier is no part of it: javac's reading keeps every reference, so the library is offered.
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
        assertEquals(3, count(LibrarySourceResolver.describeSourceContext(source)));
    }

    private static long count(String context) {
        return context.split("simpleName=Builtins", -1).length - 1;
    }
}
