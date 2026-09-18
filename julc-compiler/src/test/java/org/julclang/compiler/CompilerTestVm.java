package org.julclang.compiler;

import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;

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
        var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
        vm.setCostModelParams(profile.costModelParameters(), profile.target());
        return vm;
    }
}
