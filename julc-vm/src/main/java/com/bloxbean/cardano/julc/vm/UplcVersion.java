package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.Program;

import java.util.Objects;

/** A version of the Untyped Plutus Core language. */
public record UplcVersion(int major, int minor, int patch) {

    public static final UplcVersion V1_0_0 = new UplcVersion(1, 0, 0);
    public static final UplcVersion V1_1_0 = new UplcVersion(1, 1, 0);

    public UplcVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException(
                    "UPLC version must be non-negative: " + major + "." + minor + "." + patch);
        }
    }

    public static UplcVersion from(Program program) {
        Objects.requireNonNull(program, "program");
        return new UplcVersion(program.major(), program.minor(), program.patch());
    }

    public boolean supportsConstrAndCase() {
        return major > 1 || (major == 1 && minor >= 1);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
