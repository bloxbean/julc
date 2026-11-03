package org.julclang.tools.java;

import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.frontend.javac.SourceReader;
import org.julclang.tools.model.DiagnosticDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void aTryCatchIsJulc0015OnBothReadings() {
        var javaParser = JavaMetadataExtractor.extract(TRY_CATCH, SourceReader.JAVAPARSER, COMPILER, List.of());
        var javac = JavaMetadataExtractor.extract(TRY_CATCH, SourceReader.JAVAC, COMPILER, List.of());
        assertFalse(javaParser.valid());
        assertFalse(javac.valid());
        assertEquals(site(javaParser.diagnostics().getFirst()), site(javac.diagnostics().getFirst()));
        assertEquals("JULC0015", javac.diagnostics().getFirst().code());
        assertEquals(javaParser.contractName(), javac.contractName());
        assertEquals(javaParser.redeemerFields(), javac.redeemerFields());
    }

    @Test
    void aValidatorKeepingToTheSubsetIsValidOnBoth() {
        String valid = TRY_CATCH.replace("""
                        try {
                            return r.n().signum() > 0;
                        } catch (RuntimeException e) {
                            return false;
                        }""", "return r.n().signum() > 0;");
        assertEquals(JavaMetadataExtractor.extract(valid, SourceReader.JAVAPARSER, COMPILER, List.of()),
                JavaMetadataExtractor.extract(valid, SourceReader.JAVAC, COMPILER, List.of()));
    }

    private static String site(DiagnosticDto d) {
        return d.level() + " " + d.code() + " " + d.startLine() + ":" + d.startCol();
    }
}
