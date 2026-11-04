package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.JulcCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D8 (r11) on the default frontend, javac, from M5: a library unit the program references is read and compiled, so a
 * validator annotation on it is rejected, as the JavaParser frontend rejects it on every unit; a unit nothing references is
 * not read. {@code JulcCompilerTest} and {@code MultiFileCompilerTest} hold the JavaParser frontend's side.
 */
class JavacLibraryRolesTest {

    private static final String VALIDATOR = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class MyValidator {
                @Entrypoint
                static boolean validate(BigInteger r, PlutusData c) { return %s; }
            }
            """;

    private static String library(String annotations) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.MintingValidator;
                import org.julclang.stdlib.annotation.OnchainLibrary;

                %s
                class Lib {
                    static boolean ok(BigInteger r) { return r.signum() > 0; }
                    @Entrypoint
                    static boolean validate(BigInteger r, PlutusData c) { return true; }
                }
                """.formatted(annotations);
    }

    @Test
    void aReferencedLibraryWithAValidatorAnnotationIsRejected() {
        var e = assertThrows(CompilerException.class,
                () -> new JulcCompiler().compile(VALIDATOR.formatted("Lib.ok(r)"), List.of(library("@MintingValidator"))));
        assertTrue(e.getMessage().contains("validator annotation"), e.getMessage());
    }

    @Test
    void aReferencedLibraryThatAlsoDeclaresAValidatorIsRejected() {
        var e = assertThrows(CompilerException.class, () -> new JulcCompiler().compile(VALIDATOR.formatted("Lib.ok(r)"),
                List.of(library("@OnchainLibrary\n@MintingValidator"))));
        assertTrue(e.getMessage().contains("validator annotation"), e.getMessage());
    }

    @Test
    void anUnreferencedLibraryIsNotRead() {
        assertFalse(new JulcCompiler().compile(VALIDATOR.formatted("true"), List.of(library("@MintingValidator"))).hasErrors());
    }
}
