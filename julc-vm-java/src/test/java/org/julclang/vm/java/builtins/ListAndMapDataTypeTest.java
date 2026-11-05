package org.julclang.vm.java.builtins;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.vm.java.JavaVmProvider;
import org.julclang.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `listData` and `mapData` take a list of one type, which Plutus checks whole, so an empty list of another type fails too. */
class ListAndMapDataTypeTest {

    private static final DefaultUni PAIR = DefaultUni.pairOf(DefaultUni.DATA, DefaultUni.DATA);
    private final JavaVmProvider provider = new JavaVmProvider();

    private boolean runs(DefaultFun builtin, DefaultUni element, List<Constant> values) {
        var term = Term.apply(Term.builtin(builtin), Term.const_(new Constant.ListConst(element, values)));
        return provider.evaluate(Program.plutusV3(term), PlutusLanguage.PLUTUS_V3, null).isSuccess();
    }

    private static Constant data(long value) {
        return new Constant.DataConst(new PlutusData.IntData(BigInteger.valueOf(value)));
    }

    @Test
    void listDataTakesAListOfData() {
        assertTrue(runs(DefaultFun.ListData, DefaultUni.DATA, List.of()));
        assertTrue(runs(DefaultFun.ListData, DefaultUni.DATA, List.of(data(1))));
        assertFalse(runs(DefaultFun.ListData, PAIR, List.of()));
        assertFalse(runs(DefaultFun.ListData, DefaultUni.INTEGER, List.of()));
    }

    @Test
    void mapDataTakesAListOfPairs() {
        assertTrue(runs(DefaultFun.MapData, PAIR, List.of()));
        assertTrue(runs(DefaultFun.MapData, PAIR, List.of(new Constant.PairConst(data(1), data(2)))));
        assertFalse(runs(DefaultFun.MapData, DefaultUni.DATA, List.of()));
        assertFalse(runs(DefaultFun.MapData, DefaultUni.DATA, List.of(data(1))));
    }
}
