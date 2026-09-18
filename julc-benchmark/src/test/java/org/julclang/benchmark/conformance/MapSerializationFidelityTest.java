package org.julclang.benchmark.conformance;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #55: builtin serialization and VM argument conversion preserve map order and duplicates
 * on every backend. Serialized-program (FLAT) read-back of map literals, including the
 * literal {@code serialiseData} bytes, is covered by {@link MapDecoderProgramFidelityTest}
 * since the ADR-037 / PR #127 decoder fix. This suite pins the paths that test does not
 * exercise: serialization of a map supplied as a VM argument, the default Scalus
 * configuration, and adapter identity round-trips of the supplied argument.
 */
class MapSerializationFidelityTest {
    private record Vector(String name, PlutusData data, String expectedHex) {
        @Override public String toString() { return name; }
    }

    private static List<Vector> vectors() {
        var unsorted = PlutusData.map(pair(3, 30), pair(1, 10));
        var duplicates = PlutusData.map(pair(1, 10), pair(1, 20));
        return List.of(
                new Vector("issue-55-unsorted", unsorted, "a203181e010a"),
                new Vector("issue-55-duplicates", duplicates, "a2010a0114"),
                new Vector("empty", PlutusData.map(), "a0"),
                new Vector("sorted-control", PlutusData.map(pair(1, 10), pair(3, 30)), "a2010a03181e"),
                new Vector("interleaved-duplicates", PlutusData.map(pair(3, 30), pair(1, 10), pair(3, 40)),
                        "a303181e010a031828"),
                new Vector("negative-key", PlutusData.map(pair(2, 20), pair(-1, 10), pair(2, 30)),
                        "a30214200a02181e"),
                new Vector("nested-unsorted", PlutusData.map(new PlutusData.Pair(PlutusData.integer(0), unsorted)),
                        "a100a203181e010a"),
                new Vector("nested-duplicates", PlutusData.map(new PlutusData.Pair(PlutusData.integer(0), duplicates)),
                        "a100a2010a0114"));
    }

    private static Stream<Arguments> backendVectors() {
        return Stream.of("Java", "Truffle", "Scalus").flatMap(backend ->
                vectors().stream().map(vector -> Arguments.of(backend, vector)));
    }

    @ParameterizedTest(name = "{0}: serialise supplied argument {1}")
    @MethodSource("backendVectors")
    void serialiseDataPreservesExactMapBytesOfSuppliedArgument(String backend, Vector vector) {
        var program = Program.plutusV3(Term.lam("data",
                Term.apply(Term.builtin(DefaultFun.SerialiseData), Term.var(1))));
        var result = evaluate(backend, program, List.of(vector.data()));
        assertSerializedBytes(vector, result);
    }

    @ParameterizedTest(name = "Scalus defaults: {0}")
    @MethodSource("vectors")
    void defaultScalusCompatibilityPathPreservesMaps(Vector vector) {
        var program = Program.plutusV3(Term.apply(Term.builtin(DefaultFun.SerialiseData),
                Term.const_(Constant.data(vector.data()))));
        assertSerializedBytes(vector, JulcVm.create("Scalus").evaluate(program));
    }

    private static void assertSerializedBytes(Vector vector, EvalResult result) {
        var success = assertInstanceOf(EvalResult.Success.class, result);
        var constant = assertInstanceOf(Term.Const.class, success.resultTerm());
        var bytes = assertInstanceOf(Constant.ByteStringConst.class, constant.value());
        // Fixed CBOR expectations, not bytes computed by any tested backend/encoder.
        assertEquals(vector.expectedHex(), HexFormat.of().formatHex(bytes.value()));
        assertEquals(List.of(), success.traces());
    }

    @ParameterizedTest(name = "{0}: adapter roundtrip {1}")
    @MethodSource("backendVectors")
    void adapterPreservesOrderedEntriesAndDuplicates(String backend, Vector vector) {
        var identity = Program.plutusV3(Term.lam("data", Term.var(1)));
        var result = evaluate(backend, identity, List.of(vector.data()));
        assertEquals(Term.const_(Constant.data(vector.data())),
                assertInstanceOf(EvalResult.Success.class, result).resultTerm());
    }

    private static EvalResult evaluate(String backend, Program program, List<PlutusData> arguments) {
        var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
        var vm = JulcVm.create(backend);
        vm.setCostModelParams(profile.costModelParameters(), profile.target());
        // Scalus remains uncertified for explicit ledger targets. Exercise its
        // configured PV11 language-only API without implying ledger certification.
        if (backend.equals("Scalus")) {
            return arguments.isEmpty() ? vm.evaluate(program) : vm.evaluateWithArgs(program, arguments);
        }
        return arguments.isEmpty() ? vm.evaluate(program, profile.target(), null, EvalOptions.DEFAULT)
                : vm.evaluateWithArgs(program, profile.target(), arguments, null, EvalOptions.DEFAULT);
    }

    private static PlutusData.Pair pair(long key, long value) {
        return new PlutusData.Pair(PlutusData.integer(key), PlutusData.integer(value));
    }
}
