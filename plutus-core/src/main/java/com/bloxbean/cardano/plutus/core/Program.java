package com.bloxbean.cardano.plutus.core;

import java.util.Objects;
import java.util.List;

/**
 * A versioned UPLC program.
 * <p>
 * A Program wraps a UPLC {@link Term} together with the Plutus language version.
 * The version is a semantic triple (major, minor, patch) that determines which
 * built-in functions and term constructors are available.
 * <p>
 * Standard versions:
 * <ul>
 *   <li>Plutus V1/V2: (1, 0, 0)</li>
 *   <li>Plutus V3: (1, 1, 0)</li>
 * </ul>
 *
 * @param major the major version
 * @param minor the minor version
 * @param patch the patch version
 * @param term  the program body
 */
public record Program(int major, int minor, int patch, Term term) {

    public Program {
        Objects.requireNonNull(term, "Program term must not be null");
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException(
                    "Version components must be non-negative: " + major + "." + minor + "." + patch);
        }
    }

    /** Create a Plutus V1 program (version 1.0.0). */
    public static Program plutusV1(Term term) {
        return new Program(1, 0, 0, term);
    }

    /** Create a Plutus V2 program (version 1.0.0). */
    public static Program plutusV2(Term term) {
        return new Program(1, 0, 0, term);
    }

    /** Create a Plutus V3 program (version 1.1.0). */
    public static Program plutusV3(Term term) {
        return new Program(1, 1, 0, term);
    }

    /**
     * Apply parameter values to a parameterized contract via UPLC partial application.
     * Each parameter is wrapped as a Data constant and applied in order.
     *
     * @param params the parameter values to apply
     * @return a new Program with parameters applied
     */
    public Program applyParams(PlutusData... params) {
        Term current = this.term;
        for (PlutusData param : params) {
            current = new Term.Apply(current, new Term.Const(Constant.data(param)));
        }
        return new Program(major, minor, patch, current);
    }

    /**
     * Apply parameter values from a list.
     *
     * @param params the parameter values to apply
     * @return a new Program with parameters applied
     */
    public Program applyParams(List<PlutusData> params) {
        return applyParams(params.toArray(new PlutusData[0]));
    }

    /** Return the version as a human-readable string "major.minor.patch". */
    public String versionString() {
        return major + "." + minor + "." + patch;
    }

    @Override
    public String toString() {
        return "(program " + versionString() + " " + term + ")";
    }
}
