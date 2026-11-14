package org.julclang.cli.cmd.verify;

import org.julclang.cli.cmd.BuildCommand;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.OptimizationConfiguration;
import org.julclang.compiler.OptimizationLevel;
import picocli.CommandLine;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The optimizer level a verification run builds with (ADR-030 R.1).
 *
 * <p>The ordinary {@code julc build} that writes {@code plutus.json} and the
 * observational compile that resolves the property must produce the same bytes,
 * so both take their options from this one value.
 */
record VerificationBuild(OptimizationLevel level) {

    static final String OPTION_DESCRIPTION =
            "Optimizer level to build and verify: none, baseline, pv11-safe or pv11-costed "
                    + "(default: " + OptimizationLevel.DEFAULT_PROFILE_ID + "). "
                    + "Verify the level you deploy.";

    VerificationBuild {
        Objects.requireNonNull(level, "level");
    }

    /**
     * Resolve a public level ID exactly. Every level is accepted: builtins outside the
     * pinned Blaster model are rejected by the runner's fail-closed preflight, not here.
     */
    static VerificationBuild forProfileId(String profileId) {
        return new VerificationBuild(OptimizationLevel.forProfileId(
                profileId == null ? OptimizationLevel.DEFAULT_PROFILE_ID : profileId));
    }

    /** Options for the observational compile, matching {@link #runOrdinaryBuild(Path)}. */
    CompilerOptions compilerOptions() {
        return OptimizationConfiguration.apply(new CompilerOptions(), level.profileId(), null);
    }

    /** Run the ordinary project build at this level; returns the command's exit code. */
    int runOrdinaryBuild(Path project) {
        return new CommandLine(new BuildCommand())
                .execute(project.toString(), "--optimization", level.profileId());
    }

    /**
     * Warn that PV11 rules produce shapes the pinned Blaster preprocessing handles slowly.
     * ADR-032 recorded a PV11-safe proof that stayed compute-bound in Lean preprocessing;
     * ADR-030 R.1 observed the same for every evidence suite.
     */
    void warnIfSlowForBlaster(PrintStream out) {
        if (!level.pv11SafeRulesEnabled()) {
            return;
        }
        out.println("Note: --optimization " + level.profileId() + " emits UPLC `case` on "
                + "builtin values, which the pinned Blaster stack preprocesses very slowly. "
                + "A run can stay in Lean preprocessing until the runner times out "
                + "(COULD-NOT-EVALUATE: verification-timeout). To verify now, "
                + "build, verify and deploy with --optimization "
                + OptimizationLevel.BASELINE.profileId() + ".");
    }
}
