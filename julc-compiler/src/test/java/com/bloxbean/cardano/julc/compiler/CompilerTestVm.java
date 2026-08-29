package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.vm.JulcVm;
import com.bloxbean.cardano.julc.vm.OptimizationCostProfiles;

/** Creates compiler-test VMs configured for the compiler's exact PV11 target. */
public final class CompilerTestVm {

    private CompilerTestVm() {
    }

    public static JulcVm pv11() {
        return configure(JulcVm.create());
    }

    public static JulcVm pv11(String providerName) {
        return configure(JulcVm.create(providerName));
    }

    private static JulcVm configure(JulcVm vm) {
        var profile = OptimizationCostProfiles.CARDANO_NODE_11_0_1_PLUTUS_V3_PV11;
        vm.setCostModelParams(profile.costModelParameters(), profile.target());
        return vm;
    }
}
