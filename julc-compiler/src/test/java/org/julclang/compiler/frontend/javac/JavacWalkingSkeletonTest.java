package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.lower.LoweringFixture;
import org.julclang.core.flat.UplcFlatEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-068 M3a's walking skeleton: {@code return true} through {@code frontend=JAVAC}, to the reference's FLAT bytes. */
class JavacWalkingSkeletonTest {

    private static final String RETURN_TRUE = """
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class V {
                @Entrypoint
                public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return true;
                }
            }
            """;


    @Test
    void returnTrueCompilesToTheReferencesBytesAtEveryLevel() {
        for (var level : OptimizationLevel.values()) {
            var reference = compile(RETURN_TRUE, level, CompilerFrontend.JAVAPARSER);
            var javac = compile(RETURN_TRUE, level, CompilerFrontend.JAVAC);
            assertArrayEquals(UplcFlatEncoder.encodeProgram(reference.program()),
                    UplcFlatEncoder.encodeProgram(javac.program()), level.toString());
        }
    }

    @Test
    void theJavacFrontendRecordsWhatJavacSaw() {
        var provenance = compile(RETURN_TRUE, OptimizationLevel.DEFAULT, CompilerFrontend.JAVAC).javacProvenance();
        assertEquals(JavacProvenance.Outcome.ACCEPTED, provenance.outcome());
        assertEquals(JavacFrontend.javacVersion(), provenance.javacVersion());
        assertTrue(provenance.platformDigest() != null && provenance.stubsDigest() != null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MintingValidator", "WithdrawValidator", "CertifyingValidator", "VotingValidator", "ProposingValidator"})
    void everySinglePurposeWrapper(String annotation) {
        LoweringFixture.assertSameAsReference("""
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;

                @%s
                class V {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return false; }
                }
                """.formatted(annotation));
    }

    @Test
    void spendingWithADatum() {
        LoweringFixture.assertSameAsReference("""
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;

                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """);
    }

    @Test
    void spendingWithAnOptionalDatum() {
        LoweringFixture.assertSameAsReference("""
                import java.util.Optional;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;

                @SpendingValidator
                class V {
                    @Entrypoint
                    static boolean validate(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """);
    }

    @Test
    void multiValidatorsDispatchedByHandAndByPurpose() {
        LoweringFixture.assertSameAsReference("""
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;

                @MultiValidator
                class V {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """);
        LoweringFixture.assertSameAsReference("""
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;

                @MultiValidator
                class V {
                    static final long K = 7;

                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(PlutusData redeemer, ScriptContext ctx) { return true; }

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(PlutusData datum, PlutusData redeemer, ScriptContext ctx) { return false; }

                    @Entrypoint(purpose = Purpose.WITHDRAW)
                    static boolean withdraw(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """);
    }

    /** The source-map guard is on {@code boolean} returns: anything else keeps its value, as in the reference. */
    @ParameterizedTest
    @ValueSource(strings = {"long|5", "String|\"s\"", "Boolean|true"})
    void anEntrypointThatDoesNotReturnABoolean(String typeAndValue) {
        var parts = typeAndValue.split("\\|");
        LoweringFixture.assertSameAsReference("""
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;

                @SpendingValidator
                class V {
                    @Entrypoint
                    static %s validate(PlutusData redeemer, ScriptContext ctx) { return %s; }
                }
                """.formatted(parts[0], parts[1]));
    }

    @Test
    void aMissingBodyIsReportedAsTheReferenceDoes() {
        LoweringFixture.assertSameRejectionAsReference("""
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.*;

                @SpendingValidator
                interface V {
                    @Entrypoint
                    boolean validate(PlutusData redeemer, ScriptContext ctx);
                }
                """);
    }

    private static CompileResult compile(String source, OptimizationLevel level, CompilerFrontend frontend) {
        return new JulcCompiler(null, new CompilerOptions().setOptimizationLevel(level).setFrontend(frontend))
                .compile(source, List.of());
    }
}
