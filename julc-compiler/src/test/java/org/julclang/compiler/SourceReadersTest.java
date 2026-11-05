package org.julclang.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tools' reader switch (ADR-068 M6), and the project scan's order on both readings. */
class SourceReadersTest {

    @Test
    void anEnumsMembersComeBeforeItsConstants() {
        String source = """
                package p;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                enum E {
                    A { @OnchainLibrary class InConst { } };
                    @OnchainLibrary static class InEnum { }
                }
                """;
        var javac = JavaSourceIntrospector.inspect(source);
        assertEquals("p.E.InEnum", javac.nestedOnchainLibraries().getFirst().fqcn());
    }
}
