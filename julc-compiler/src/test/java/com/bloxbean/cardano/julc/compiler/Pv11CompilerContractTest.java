package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the current compiler contract to the Plutus V3/PV11 feature set.
 * The expected builtin boundary was verified against Plutus 1.63.0.0 at
 * f92b7d7d8, as shipped by cardano-node 11.0.1.
 */
class Pv11CompilerContractTest {

    @Test
    void allReleasedBatch6BuiltinsCanBeLowered() {
        var generator = new UplcGenerator();
        for (int tag = 87; tag <= 100; tag++) {
            var builtin = DefaultFun.fromFlatCode(tag);
            assertDoesNotThrow(
                    () -> generator.generate(new PirTerm.Builtin(builtin)),
                    () -> "Released PV11 builtin must compile: " + builtin);
        }
    }

    @Test
    void directPirCannotEmitFutureMultiIndexArray() {
        var error = assertThrows(CompilerException.class,
                () -> new UplcGenerator().generate(
                        new PirTerm.Builtin(DefaultFun.MultiIndexArray)));

        assertFutureBuiltinDiagnostic(error);
    }

    @Test
    void stdlibRegistryPathCannotEmitFutureMultiIndexArray() {
        var dummy = new PirTerm.Const(Constant.unit());
        var pir = StdlibRegistry.defaultRegistry()
                .lookup("com.bloxbean.cardano.julc.stdlib.Builtins", "multiIndexArray",
                        List.of(dummy, dummy))
                .orElseThrow();

        var error = assertThrows(CompilerException.class,
                () -> new UplcGenerator().generate(pir));

        assertFutureBuiltinDiagnostic(error);
    }

    @Test
    void publicBuiltinsCallFailsDuringCompilation() {
        var source = """
                import com.bloxbean.cardano.julc.core.PlutusData;
                import com.bloxbean.cardano.julc.stdlib.Builtins;

                class FutureArrayCall {
                    static PlutusData select(PlutusData array, PlutusData indices) {
                        return Builtins.multiIndexArray(array, indices);
                    }
                }
                """;

        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
        var error = assertThrows(CompilerException.class,
                () -> compiler.compileMethod(source, "select"));

        assertFutureBuiltinDiagnostic(error);
    }

    private static void assertFutureBuiltinDiagnostic(CompilerException error) {
        assertAll(
                () -> assertTrue(error.getMessage().contains("MultiIndexArray (FLAT tag 101)")),
                () -> assertTrue(error.getMessage().contains("future/unreleased")),
                () -> assertTrue(error.getMessage().contains("protocol version 11")),
                () -> assertTrue(error.getMessage().contains("Use IndexArray repeatedly")));
    }
}
