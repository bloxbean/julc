package org.julclang.difftest;

import java.util.List;

/**
 * How one UPLC observation compares with the JVM observation (ADR-063 D3). There is no
 * normalization (H2): any difference in value, outcome kind or trace sequence is a
 * {@link Kind#MISMATCH}.
 *
 * @param kind   the comparison result
 * @param reason why, for anything other than {@link Kind#AGREE}
 */
public record Verdict(Kind kind, String reason) {

    public enum Kind {
        /** Equal Data with equal traces, or both failed with equal traces. */
        AGREE,
        /** A difference in value, outcome kind or traces. */
        MISMATCH,
        /** JuLC rejected the program. Expected for negative programs, a mismatch for positive ones (H7). */
        REJECTED,
        /** The compiler or VM threw: always a defect. */
        CRASH,
        /** Either side timed out, overflowed its stack or exhausted its budget: not a pass (H5). */
        INCONCLUSIVE
    }

    public static Verdict compare(Observation jvm, Observation uplc) {
        if (uplc instanceof Observation.Crash c) return new Verdict(Kind.CRASH, c.detail());
        if (jvm instanceof Observation.Inconclusive i) return new Verdict(Kind.INCONCLUSIVE, "JVM: " + i.reason());
        if (uplc instanceof Observation.Inconclusive i) return new Verdict(Kind.INCONCLUSIVE, "UPLC: " + i.reason());
        if (uplc instanceof Observation.Rejected r) return new Verdict(Kind.REJECTED, r.code() + ": " + r.message());
        if (jvm instanceof Observation.Value jv && uplc instanceof Observation.Value uv) {
            if (!jv.data().equals(uv.data())) {
                return new Verdict(Kind.MISMATCH, "JVM returned " + jv.data() + ", UPLC returned " + uv.data());
            }
            return traces(jv.traces(), uv.traces());
        }
        if (jvm instanceof Observation.Failure jf && uplc instanceof Observation.Failure uf) {
            return traces(jf.traces(), uf.traces());
        }
        return new Verdict(Kind.MISMATCH, "JVM: " + jvm + "; UPLC: " + uplc);
    }

    private static Verdict traces(List<String> jvm, List<String> uplc) {
        if (jvm.equals(uplc)) return new Verdict(Kind.AGREE, null);
        return new Verdict(Kind.MISMATCH, "JVM traced " + jvm + ", UPLC traced " + uplc);
    }
}
