package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.pir.TypeMethodRegistry;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoweringRequirementsTest {

    private static final String BUILTINS =
            "com.bloxbean.cardano.julc.stdlib.Builtins";

    @Test
    void stdlibDeclaresEveryInitiallyTargetGatedBuiltinRoute() {
        var registry = StdlibRegistry.defaultRegistry();
        var expected = Map.ofEntries(
                Map.entry("dropList", DefaultFun.DropList),
                Map.entry("listToArray", DefaultFun.ListToArray),
                Map.entry("lengthOfArray", DefaultFun.LengthOfArray),
                Map.entry("indexArray", DefaultFun.IndexArray),
                Map.entry("multiIndexArray", DefaultFun.MultiIndexArray),
                Map.entry("bls12_381_G1_multiScalarMul",
                        DefaultFun.Bls12_381_G1_multiScalarMul),
                Map.entry("bls12_381_G2_multiScalarMul",
                        DefaultFun.Bls12_381_G2_multiScalarMul),
                Map.entry("expModInteger", DefaultFun.ExpModInteger),
                Map.entry("insertCoin", DefaultFun.InsertCoin),
                Map.entry("lookupCoin", DefaultFun.LookupCoin),
                Map.entry("unionValue", DefaultFun.UnionValue),
                Map.entry("valueContains", DefaultFun.ValueContains),
                Map.entry("valueData", DefaultFun.ValueData),
                Map.entry("unValueData", DefaultFun.UnValueData),
                Map.entry("scaleValue", DefaultFun.ScaleValue));

        expected.forEach((method, builtin) -> assertEquals(
                LoweringRequirements.builtin(builtin),
                registry.requirements(BUILTINS, method),
                method));
    }

    @Test
    void arrayTypeMethodsDeclareTheirExactBuiltinRequirements() {
        var registry = TypeMethodRegistry.defaultRegistry();
        var listType = new PirType.ListType(new PirType.DataType());
        var arrayType = new PirType.ArrayType(new PirType.DataType());

        assertEquals(LoweringRequirements.builtin(DefaultFun.ListToArray),
                registry.requirements(listType, "toArray"));
        assertEquals(LoweringRequirements.builtin(DefaultFun.LengthOfArray),
                registry.requirements(arrayType, "length"));
        assertEquals(LoweringRequirements.builtin(DefaultFun.IndexArray),
                registry.requirements(arrayType, "get"));
        assertEquals(LoweringRequirements.NONE,
                registry.requirements(listType, "length"));
    }
}
