package org.julclang.difftest;

import org.julclang.core.PlutusData;

import java.util.List;

/**
 * What one execution of a {@link DiffCase} produced (ADR-063 D3). The JVM side produces
 * {@link Value}, {@link Failure} or {@link Inconclusive}; the UPLC side can also produce
 * {@link Rejected} and {@link Crash}.
 */
public sealed interface Observation {

    /** The entry method returned; the result as Data under {@link ValueMapping}. */
    record Value(PlutusData data, List<String> traces) implements Observation {
        public Value {
            traces = List.copyOf(traces);
        }
    }

    /** The entry method failed: a JVM exception or a UPLC evaluation error, with the traces before it. */
    record Failure(String detail, List<String> traces) implements Observation {
        public Failure {
            traces = List.copyOf(traces);
        }
    }

    /** JuLC rejected the source with a diagnostic. */
    record Rejected(String code, String message) implements Observation {
    }

    /** The compiler or VM threw something other than a diagnostic: always a defect. */
    record Crash(String detail) implements Observation {
    }

    /** No verdict: a timeout, a stack overflow or an exhausted budget (ADR-063 H5). */
    record Inconclusive(String reason) implements Observation {
    }
}
