package com.bloxbean.cardano.julc.compiler.uplc;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.LedgerEvaluationTarget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SerialiseDataLoweringTest {
    @ParameterizedTest
    @ValueSource(ints = {10, 11})
    void generatedSerialiseDataEvaluatesToExpectedCbor(int protocolVersion) {
        var values = List.of(PlutusData.integer(0), PlutusData.integer(-1),
                PlutusData.bytes(new byte[0]), PlutusData.constr(0));
        var expectedCbor = List.of("00", "20", "40", "d87980");
        for (int i = 0; i < values.size(); i++) {
            var pir = new PirTerm.App(new PirTerm.Builtin(DefaultFun.SerialiseData),
                    new PirTerm.Const(Constant.data(values.get(i))));
            var program = Program.plutusV3(new UplcGenerator().generate(pir));
            var target = protocolVersion == 10 ? LedgerEvaluationTarget.pv10(PlutusLanguage.PLUTUS_V3)
                    : LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
            var result = JulcVm.create("Java").evaluate(program, target);
            var success = assertInstanceOf(EvalResult.Success.class, result, protocolVersion + ": " + result);
            assertEquals(Term.const_(Constant.byteString(HexFormat.of().parseHex(expectedCbor.get(i)))),
                    success.resultTerm(), protocolVersion + " vector " + i);
        }
    }
}
