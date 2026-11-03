package org.julclang.compiler;

import org.julclang.compiler.frontend.javac.SourceReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tools' reader switch (ADR-068 M6), and the project scan's order on both readings. */
class SourceReadersTest {

    @Test
    void anUnknownReaderFailsWhenUsedNamingTheProperty() {
        String before = System.getProperty(SourceReader.PROPERTY);
        try {
            System.setProperty(SourceReader.PROPERTY, "javac2");
            var error = assertThrows(IllegalArgumentException.class, () -> JavaSourceIntrospector.inspect("class A { }"));
            assertTrue(error.getMessage().contains(SourceReader.PROPERTY), error.getMessage());
            System.setProperty(SourceReader.PROPERTY, "javac");
            assertEquals(SourceReader.JAVAC, SourceReader.configured());
            // The class is still usable: nothing was cached from the bad value.
            System.clearProperty(SourceReader.PROPERTY);
            assertEquals("", JavaSourceIntrospector.inspect("class A { }").packageName());
        } finally {
            if (before == null) System.clearProperty(SourceReader.PROPERTY);
            else System.setProperty(SourceReader.PROPERTY, before);
        }
    }

    @Test
    void anEnumsNestedTypesComeInJavaParsersOrder() {
        String source = """
                package p;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                enum E {
                    A { @OnchainLibrary class InConst { } };
                    @OnchainLibrary static class InEnum { }
                }
                """;
        var javaParser = JavaSourceIntrospector.inspect(source, SourceReader.JAVAPARSER);
        var javac = JavaSourceIntrospector.inspect(source, SourceReader.JAVAC);
        assertEquals(javaParser, javac);
        assertEquals("p.E.InEnum", javac.nestedOnchainLibraries().getFirst().fqcn());
    }
}
