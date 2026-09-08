package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Full Java-source pipeline coverage for the invalid serialiseData force fixed in #133. */
class SerialiseDataSourceEvalTest {
    private static final String SOURCE = """
            import com.bloxbean.cardano.julc.core.PlutusData;
            import com.bloxbean.cardano.julc.stdlib.Builtins;
            import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;

            class SerialiseExample {
                static byte[] serialise(PlutusData data) {
                    return %s.serialiseData(data);
                }

                static byte[] commitment(PlutusData data) {
                    return Builtins.blake2b_256(%s.serialiseData(data));
                }
            }
            """;

    static Stream<Arguments> configurations() {
        var cases = Stream.<Arguments>builder();
        for (var api : List.of("Builtins", "ByteStringLib")) {
            for (var level : List.of(OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE)) {
                for (var backend : List.of("Java", "Scalus")) {
                    cases.add(Arguments.of(api, level, backend));
                }
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0}, {1}, {2}")
    @MethodSource("configurations")
    void serialisesRuntimeDataToExactCbor(String api, OptimizationLevel level, String backend) {
        var compiled = compile(api, level, "serialise");
        assertBytes(compiled, backend, PlutusData.integer(0), "00");
        assertBytes(compiled, backend, PlutusData.integer(-1), "20");
        assertBytes(compiled, backend, PlutusData.bytes(new byte[0]), "40");
        assertBytes(compiled, backend, PlutusData.constr(0), "d87980");
        assertBytes(compiled, backend, PlutusData.constr(0, PlutusData.integer(42)), "d8799f182aff");
        assertBytes(compiled, backend,
                PlutusData.constr(0, PlutusData.list(PlutusData.integer(0), PlutusData.integer(-1))),
                "d8799f9f0020ffff");
    }

    @ParameterizedTest(name = "{0}, {1}, {2}")
    @MethodSource("configurations")
    void hashesSerialisedRuntimeDataToExactCommitment(String api, OptimizationLevel level, String backend) {
        var compiled = compile(api, level, "commitment");
        // Independent BLAKE2b-256 vectors, computed with Python hashlib.blake2b(..., digest_size=32)
        // over CBOR d8799f182aff and d8799f182bff respectively, not JuLC's off-chain serializer.
        assertBytes(compiled, backend, PlutusData.constr(0, PlutusData.integer(42)),
                "fcaa61fb85676101d9e3398a484674e71c45c3fd41b492682f3b0054f4cf3273");
        assertBytes(compiled, backend, PlutusData.constr(0, PlutusData.integer(43)),
                "93978341ed4f41d3a7e7afa234e31c4e8cde68f54bd342885f421103c2c2ba02");
    }

    private static CompileResult compile(String api, OptimizationLevel level, String method) {
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level))
                .compileMethod(SOURCE.formatted(api, api), method);
        assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
        return compiled;
    }

    private static void assertBytes(CompileResult compiled, String backend, PlutusData input, String expectedHex) {
        var vm = JulcVm.create(backend);
        // Scalus has no certified explicit ledger target; use its language-only compatibility API.
        var result = backend.equals("Java")
                ? vm.evaluateWithArgs(compiled.program(), compiled.target().ledgerTarget(),
                        List.of(input), null, EvalOptions.DEFAULT)
                : vm.evaluateWithArgs(compiled.program(), List.of(input));
        var success = assertInstanceOf(EvalResult.Success.class, result, backend + ": " + input + ": " + result);
        assertEquals(Term.const_(Constant.byteString(HexFormat.of().parseHex(expectedHex))),
                success.resultTerm(), backend + ": " + input);
    }
}
