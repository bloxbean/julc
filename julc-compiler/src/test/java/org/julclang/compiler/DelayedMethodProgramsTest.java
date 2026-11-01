package org.julclang.compiler;

import org.julclang.compiler.pir.DelayedMethodProbe;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-068 B4 through both frontends' assembly: a validator's helpers, a static field, and each handler's copy of a helper. */
class DelayedMethodProgramsTest {

    private static final String VALIDATOR = """
            import org.julclang.core.PlutusData;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;

            @SpendingValidator
            class V {
                static boolean boom() { Builtins.error(); return true; }

                @Entrypoint
                static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    return redeemer.equals(Builtins.iData(1)) ? boom() : true;
                }
            }
            """;

    private static JulcCompiler compiler(CompilerFrontend frontend) {
        return new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(frontend));
    }

    private static boolean accepts(CompilerFrontend frontend, long redeemer) {
        var data = PlutusData.integer(redeemer);
        var context = PlutusDataCastTest.buildScriptContext(
                PlutusDataCastTest.buildTxInfo(new PlutusData[0], PlutusDataCastTest.alwaysInterval()),
                data, PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        return CompilerTestVm.pv11().evaluateWithArgs(compiler(frontend).compile(VALIDATOR).program(), List.of(context)).isSuccess();
    }

    /** A helper's failure in a branch the validator does not take does not fail it, and in the branch it takes it does. */
    @ParameterizedTest
    @EnumSource(CompilerFrontend.class)
    void aValidatorsHelperRunsOnlyIfCalled(CompilerFrontend frontend) {
        assertTrue(accepts(frontend, 0));
        assertFalse(accepts(frontend, 1));
    }

    /** Reading a total static field is total, so the method stays one shared value. */
    @ParameterizedTest
    @EnumSource(CompilerFrontend.class)
    void aMethodThatReadsATotalFieldStaysShared(CompilerFrontend frontend) {
        var source = """
                import java.math.BigInteger;

                class C {
                    static final BigInteger K = BigInteger.TEN;
                    static BigInteger k() { return K; }
                    public static BigInteger m() { return k().add(k()); }
                }
                """;
        assertFalse(DelayedMethodProbe.delays(compiler(frontend).compileMethod(source, "m").pirTerm(), "C.k"));
    }

    /** Each entry point of a multi-purpose validator has its copy of the helpers, and each copy is delayed. */
    @ParameterizedTest
    @EnumSource(CompilerFrontend.class)
    void eachHandlersCopyIsDelayed(CompilerFrontend frontend) {
        var source = """
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.MultiValidator;
                import org.julclang.stdlib.annotation.Purpose;

                @MultiValidator
                class M {
                    static boolean boom() { Builtins.error(); return true; }

                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(PlutusData redeemer, ScriptContext ctx) { return boom(); }

                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(PlutusData redeemer, ScriptContext ctx) { return true; }
                }
                """;
        assertTrue(DelayedMethodProbe.delays(compiler(frontend).compileWithDetails(source).pirTerm(), "M.boom"));
    }
}
