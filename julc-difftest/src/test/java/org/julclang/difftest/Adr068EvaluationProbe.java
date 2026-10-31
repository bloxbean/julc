package org.julclang.difftest;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;
import org.julclang.vm.JulcVmProvider;

import java.util.ServiceLoader;

/**
 * F312's and F315's probe, run by {@link Adr068EvaluationsTest} in a child JVM that records evaluations: the same
 * {@code expModInteger 2 3 5} program on a default Java VM (PV10: the builtin is unavailable, a failure), on a
 * PV11-configured one (success, 3), and on a provider the {@code JulcVm} is handed (configuration unknown).
 */
public final class Adr068EvaluationProbe {

    static final Program EXP_MOD = new Program(1, 1, 0, new Term.Apply(new Term.Apply(new Term.Apply(
            new Term.Builtin(DefaultFun.ExpModInteger), new Term.Const(Constant.integer(2))),
            new Term.Const(Constant.integer(3))), new Term.Const(Constant.integer(5))));

    private Adr068EvaluationProbe() {
    }

    public static void main(String[] args) {
        var pv10 = JulcVm.create("Java");
        var pv11 = JulcVm.create("Java");
        var profile = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
        pv11.setCostModelParams(profile.costModelParameters(), profile.target());
        var handed = JulcVm.withProvider(ServiceLoader.load(JulcVmProvider.class).stream().map(ServiceLoader.Provider::get)
                .filter(p -> p.name().equals("Java")).findFirst().orElseThrow());
        System.out.println("pv10 " + pv10.evaluate(EXP_MOD).getClass().getSimpleName());
        System.out.println("pv11 " + pv11.evaluate(EXP_MOD).getClass().getSimpleName());
        System.out.println("handed " + handed.evaluate(EXP_MOD).getClass().getSimpleName());
    }
}
