package org.julclang.wasm;

import org.julclang.core.text.UplcParser;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;

/** Run as an ordinary native image by {@code nativeImageBlsProbe}: this module's jar must not change what BLS does there. */
public final class BlsProbe {
    private static final String G1_GENERATOR =
            "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb";

    private BlsProbe() {}

    public static void main(String[] args) {
        var program = UplcParser.parseProgram("(program 1.1.0 [(builtin bls12_381_G1_uncompress) (con bytestring #" + G1_GENERATOR + ")])");
        var result = JulcVm.create().evaluate(program);
        System.out.println("uncompress " + (result instanceof EvalResult.Success ? "success" : "failure: " + result));
        String constant;
        try {
            UplcParser.parseProgram("(program 1.1.0 (con bls12_381_G1_element 0x" + "ff".repeat(48) + "))");
            constant = "accepted";
        } catch (RuntimeException e) {
            constant = "rejected";
        }
        System.out.println("invalid constant " + constant);
    }
}
