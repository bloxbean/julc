package org.julclang.tools.java;

import org.julclang.compiler.JulcCompiler;
import org.julclang.tools.model.DiagnosticDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The playground's check reports the subset's diagnostics on either reading (ADR-068 M6): JavaParser's frontend's
 * validator, or the javac frontend's subset check, which a compilation applies, with the same code at the same place.
 */
class JavaMetadataExtractorReadersTest {

    private static final JulcCompiler COMPILER = new JulcCompiler();

    private static final String TRY_CATCH = """
            package p;

            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            import java.math.BigInteger;

            @SpendingValidator
            public class TryCatchValidator {
                record Redeemer(BigInteger n) { }

                @Entrypoint
                public static boolean validate(Redeemer r, ScriptContext ctx) {
                    try {
                        return r.n().signum() > 0;
                    } catch (RuntimeException e) {
                        return false;
                    }
                }
            }
            """;

    @Test
    void aTryCatchIsJulc0015() {
        var javac = JavaMetadataExtractor.extract(TRY_CATCH, COMPILER, List.of());
        assertFalse(javac.valid());
        assertEquals("JULC0015", javac.diagnostics().getFirst().code());
    }

    @Test
    void aValidatorKeepingToTheSubsetIsValid() {
        String valid = TRY_CATCH.substring(0, TRY_CATCH.indexOf("try {")) + "return r.n().signum() > 0;\n"
                + TRY_CATCH.substring(TRY_CATCH.indexOf("return false;") + "return false;".length()).replaceFirst("\\}", "");
        assertFalse(valid.contains("try {"), valid);
        var response = JavaMetadataExtractor.extract(valid, COMPILER, List.of());
        assertTrue(response.valid(), response::toString);
    }

    private static String site(DiagnosticDto d) {
        return d.level() + " " + d.code() + " " + d.startLine() + ":" + d.startCol();
    }
}
