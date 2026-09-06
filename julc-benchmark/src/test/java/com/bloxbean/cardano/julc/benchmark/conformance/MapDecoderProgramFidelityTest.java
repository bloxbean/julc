package com.bloxbean.cardano.julc.benchmark.conformance;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.vm.EvalOptions;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.OptimizationCostProfiles;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-037: serialized Data constants retain map associations on every VM. */
class MapDecoderProgramFidelityTest {
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

    @ParameterizedTest(name = "{0}: serialized map {1}")
    @MethodSource("backendVectors")
    void flatReadBackPreservesResultsAndBudgets(String backend, Vector vector) {
        var literal = Term.const_(Constant.data(vector.data()));
        var program = Program.plutusV3(Term.apply(Term.builtin(DefaultFun.SerialiseData), literal));
        var before = assertInstanceOf(EvalResult.Success.class, evaluate(backend, program, List.of()));
        byte[] flat = UplcFlatEncoder.encodeProgram(program);
        var decoded = UplcFlatDecoder.decodeProgram(flat);
        assertArrayEquals(flat, UplcFlatEncoder.encodeProgram(decoded));
        var after = assertInstanceOf(EvalResult.Success.class, evaluate(backend, decoded, List.of()));
        var constant = assertInstanceOf(Term.Const.class, after.resultTerm());
        var bytes = assertInstanceOf(Constant.ByteStringConst.class, constant.value());
        assertEquals(vector.expectedHex(), HexFormat.of().formatHex(bytes.value()));
        assertEquals(before.resultTerm(), after.resultTerm());
        assertEquals(before.budgetConsumed(), after.budgetConsumed());
        assertEquals(before.traces(), after.traces());
    }

    @ParameterizedTest(name = "{0}: equality with supplied argument {1}")
    @MethodSource("backendVectors")
    void decodedLiteralEqualsOriginalArgument(String backend, Vector vector) {
        var program = Program.plutusV3(Term.lam("data", Term.apply(
                Term.apply(Term.builtin(DefaultFun.EqualsData), Term.const_(Constant.data(vector.data()))),
                Term.var(1))));
        var decoded = UplcFlatDecoder.decodeProgram(UplcFlatEncoder.encodeProgram(program));
        var result = assertInstanceOf(EvalResult.Success.class,
                evaluate(backend, decoded, List.of(vector.data())));
        assertEquals(Term.const_(Constant.bool(true)), result.resultTerm());
    }

    private static EvalResult evaluate(String backend, Program program, List<PlutusData> arguments) {
        var profile = OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11;
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
