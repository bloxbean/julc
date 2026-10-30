package org.julclang.compiler;

import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-067 M1 review round 4 (F41 = F80): a {@code @Param} field is read in a guard as the applied parameter, exactly
 * as an ordinary {@code if} reads it, never folded from its initializer, even when it is {@code static final} with a
 * constant initializer (whose JVM meaning is #293). The control in each case is the same read in an {@code if}.
 */
class SwitchGuardParamTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = CompilerTestVm.pv11();
    }

    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Param;

            class Probe {
                sealed interface S permits A, B {}
                record A(BigInteger v) implements S {}
                record B(BigInteger v) implements S {}
            """;

    private static BigInteger run(String source, String method, PlutusData param, long a) {
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, method);
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        assertTrue(result.isParameterized(), "the @Param field must stay a parameter");
        var program = result.program().applyParams(param);
        var evaluated = vm.evaluateWithArgs(program, List.of(PlutusData.integer(a)));
        assertTrue(evaluated.isSuccess(), () -> evaluated.toString());
        return TermExtractor.extractInteger(TermExtractor.extractResultTerm(evaluated));
    }

    /** The guard and the {@code if} control agree for each applied value, and give {@code expected}. */
    private static void agreesWithIf(String fields, String guard, PlutusData param, long a, long expected) {
        String source = HEADER + fields + """
                    static BigInteger g(BigInteger a) {
                        S s = a.signum() > 0 ? new A(a) : new B(a);
                        return switch (s) { case A x when %s -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                    static BigInteger c(BigInteger a) {
                        S s = a.signum() > 0 ? new A(a) : new B(a);
                        return switch (s) {
                            case A x -> { if (%s) { yield BigInteger.ONE; } yield BigInteger.TWO; }
                            default -> BigInteger.TWO;
                        };
                    }
                }
                """.formatted(guard, guard);
        assertEquals(BigInteger.valueOf(expected), run(source, "c", param, a), "control: " + guard);
        assertEquals(BigInteger.valueOf(expected), run(source, "g", param, a), "guard: " + guard);
    }

    @Test
    void booleanParamIsReadAsApplied() {
        String fields = "    @Param static final boolean ENABLED = true;\n";
        agreesWithIf(fields, "ENABLED", PlutusData.constr(0), 5, 2);
        agreesWithIf(fields, "ENABLED", PlutusData.constr(1), 5, 1);
        agreesWithIf(fields, "!ENABLED", PlutusData.constr(0), 5, 1);
    }

    @Test
    void longParamIsReadAsApplied() {
        String fields = "    @Param static final long LIMIT = 5;\n";
        agreesWithIf(fields, "x.v().signum() < LIMIT", PlutusData.integer(0), 5, 2);
        agreesWithIf(fields, "x.v().signum() < LIMIT", PlutusData.integer(5), 5, 1);
        agreesWithIf(fields, "LIMIT > 0", PlutusData.integer(0), 5, 2);
    }

    @Test
    void constantReadingAParamIsNotConstant() {
        // M = LIMIT: M's initializer reads the parameter, so M is not folded either.
        String fields = "    @Param static final long LIMIT = 5;\n    static final long M = LIMIT;\n";
        agreesWithIf(fields, "x.v().signum() < M", PlutusData.integer(0), 5, 2);
        agreesWithIf(fields, "x.v().signum() < M", PlutusData.integer(5), 5, 1);
    }

    @Test
    void paramGuardIsGuardedForExhaustivenessAndDominance() {
        // The folded reading made 'when ENABLED' unguarded: the switch below counted as exhaustive.
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(HEADER + """
                    @Param static final boolean ENABLED = true;
                    static BigInteger g(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when ENABLED -> BigInteger.ONE; case B y -> BigInteger.ZERO; };
                    }
                }
                """, "g"));
        var diagnostic = error.diagnostics().stream().filter(d -> d.level() == CompilerDiagnostic.Level.ERROR)
                .findFirst().orElseThrow();
        assertEquals("JULC0005", diagnostic.code(), diagnostic.message());
        // A later unguarded A arm is not dominated by it.
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(HEADER + """
                    @Param static final boolean ENABLED = true;
                    static BigInteger g(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when ENABLED -> BigInteger.ONE; case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                    }
                }
                """, "g");
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
    }

    @Test
    void mintingValidatorHonoursTheAppliedLimit() {
        // F80's validator shape: with LIMIT applied as 0 the guard is false, so the policy fails, as the if form does.
        String guarded = """
                import java.math.BigInteger;

                @MintingValidator
                class LimitMint {
                    sealed interface S permits A, B {}
                    record A(BigInteger v) implements S {}
                    record B(BigInteger v) implements S {}
                    @Param static final long LIMIT = 5;

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        S s = new A(BigInteger.ONE);
                        return switch (s) { case A x when LIMIT > x.v().signum() -> true; default -> false; };
                    }
                }
                """;
        String control = guarded.replace(
                "return switch (s) { case A x when LIMIT > x.v().signum() -> true; default -> false; };",
                "return switch (s) { case A x -> { if (LIMIT > x.v().signum()) { yield true; } yield false; } default -> false; };");
        for (String source : List.of(guarded, control)) {
            var result = new JulcCompiler().compile(source);
            assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
            assertTrue(evaluate(result, 5).isSuccess(), "LIMIT = 5 accepts");
            assertFalse(evaluate(result, 0).isSuccess(), "LIMIT = 0 rejects");
        }
    }

    private static EvalResult evaluate(CompileResult result, long limit) {
        var concrete = result.program().applyParams(PlutusData.integer(limit));
        var ctx = ParameterizedValidatorTest.buildScriptContext(
                ParameterizedValidatorTest.buildTxInfo(new PlutusData[0]),
                PlutusData.integer(0),
                PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        return vm.evaluateWithArgs(concrete, List.of(ctx));
    }
}
