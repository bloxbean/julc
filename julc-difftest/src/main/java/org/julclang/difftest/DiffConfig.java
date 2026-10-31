package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.OptimizationLevel;

import java.util.Arrays;
import java.util.List;

/**
 * One UPLC execution configuration: the compiler's optimization level, the VM provider (ADR-063 D2) and the Java
 * frontend (ADR-068 D10: a case's verdict is per frontend). The JVM side has no configuration.
 *
 * @param level    the JuLC optimization level
 * @param vm       the {@code JulcVm} provider name, for example {@code "Java"}
 * @param frontend the Java frontend that compiles the case
 */
public record DiffConfig(OptimizationLevel level, String vm, CompilerFrontend frontend) {

    /** The configuration on the default frontend. */
    public DiffConfig(OptimizationLevel level, String vm) {
        this(level, vm, CompilerFrontend.JAVAPARSER);
    }

    /** Every optimization level on the Java VM: the pull-request matrix (ADR-063 open question 4). */
    public static List<DiffConfig> allLevels() {
        return allLevels(CompilerFrontend.JAVAPARSER);
    }

    /** Every optimization level on the Java VM, through {@code frontend}. */
    public static List<DiffConfig> allLevels(CompilerFrontend frontend) {
        return Arrays.stream(OptimizationLevel.values()).map(level -> new DiffConfig(level, "Java", frontend)).toList();
    }

    @Override
    public String toString() {
        return vm + "/" + level + (frontend == CompilerFrontend.JAVAPARSER ? "" : "/" + frontend);
    }
}
