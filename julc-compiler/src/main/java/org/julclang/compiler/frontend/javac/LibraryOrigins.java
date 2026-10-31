package org.julclang.compiler.frontend.javac;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which library texts came from a JAR (ADR-068 D8: same-project sources win over JAR sources). A host that merges
 * a class-path pool with project sources registers its JAR texts here
 * ({@code LibrarySourceResolver.withProjectPrecedence}), so that the gate can apply D8's merge to the pool it hands
 * javac while, in {@code report} mode, JuLC's own compilation keeps B0's pool. A text is identified by
 * {@link JavacPlatform#identity}; a JAR text is a JAR text wherever it appears, so the set only grows.
 */
public final class LibraryOrigins {

    private static final Set<String> JAR = ConcurrentHashMap.newKeySet();

    private LibraryOrigins() {
    }

    /** Records {@code texts} as JAR sources. */
    public static void jar(Collection<String> texts) {
        for (String text : texts) JAR.add(JavacPlatform.identity(text));
    }

    static boolean isJar(JavacSourceFile unit) {
        return !JAR.isEmpty() && JAR.contains(JavacPlatform.identity(unit.text()));
    }
}
