package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A library's export is a term on its own, which no program binds around: a ledger {@code fromPlutusData} in it carries its
 * own shape check, and the export is closed.
 */
class JavacLibraryShapeCheckTest {

    private static final String LIBRARY = """
            package lib;

            import org.julclang.core.PlutusData;
            import org.julclang.ledger.Credential;
            import org.julclang.stdlib.annotation.OnchainLibrary;

            @OnchainLibrary
            public class Decodes {
                public static boolean credential(PlutusData d) {
                    Credential c = Credential.fromPlutusData(d);
                    return true;
                }
            }
            """;

    @Test
    void anExportsShapeCheckIsItsOwn() {
        var provider = new JavaLibraryProvider(List.of(LIBRARY), StdlibRegistry.defaultRegistry(),
                new CompilerOptions());
        var term = provider.materialize("lib.Decodes.credential");
        assertEquals(List.of(), PirHelpers.freeVariables(term).stream().filter(PirHelpers::isGeneratedName).toList());
    }
}
