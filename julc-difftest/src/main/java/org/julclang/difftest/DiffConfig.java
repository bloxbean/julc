package org.julclang.difftest;

import org.julclang.compiler.OptimizationLevel;

import java.util.Arrays;
import java.util.List;

/**
 * One UPLC execution configuration: the compiler's optimization level and the VM provider
 * (ADR-063 D2). The JVM side has no configuration.
 *
 * @param level the JuLC optimization level
 * @param vm    the {@code JulcVm} provider name, for example {@code "Java"}
 */
public record DiffConfig(OptimizationLevel level, String vm) {

    /** Every optimization level on the Java VM: the pull-request matrix (ADR-063 open question 4). */
    public static List<DiffConfig> allLevels() {
        return Arrays.stream(OptimizationLevel.values()).map(level -> new DiffConfig(level, "Java")).toList();
    }

    @Override
    public String toString() {
        return vm + "/" + level;
    }
}
