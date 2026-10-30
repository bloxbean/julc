package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-067 outcome-matrix rows with checker U: switches over Data that no strict boundary validated (a
 * {@code compileMethod} argument, or Data cast inside a body). Java cannot build such values, so these tests
 * assert JuLC's evaluation outcome directly, at every optimization level on the Java and Scalus VMs.
 * <p>
 * M0 pinned the base ({@code fd605e77}) behaviour; each test says which milestone changes it and to what.
 */
class SwitchMalformedDataTest {

    private static final List<String> VMS = List.of("Java", "Scalus");

    private static final String S = """
                sealed interface S permits A, B {}
                record A(BigInteger v) implements S {}
                record B(BigInteger v) implements S {}
            """;
    private static final String TU = """
                sealed interface T permits C, D {}
                record C(BigInteger w) implements T {}
                record D(BigInteger w) implements T {}
                sealed interface U permits P, Q {}
                record P(T t) implements U {}
                record Q(BigInteger z) implements U {}
            """;

    private static String source(String declarations, String method) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.stdlib.Builtins;

                public class Probe {
                """ + declarations + method + "\n}\n";
    }

    private record Outcome(String label, EvalResult result) {
    }

    /** Compiles {@code m} at every level and evaluates it on both VMs. */
    private static List<Outcome> run(String source, PlutusData... args) {
        var outcomes = new java.util.ArrayList<Outcome>();
        for (var level : OptimizationLevel.values()) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "m").program();
            for (var vm : VMS) {
                outcomes.add(new Outcome(vm + "/" + level,
                        CompilerTestVm.pv11(vm).evaluateWithArgs(program, List.of(args))));
            }
        }
        return outcomes;
    }

    private static void assertValue(long expected, List<Outcome> outcomes) {
        for (var outcome : outcomes) {
            var success = assertInstanceOf(EvalResult.Success.class, outcome.result(), outcome.label() + ": " + outcome.result());
            assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(expected))), success.resultTerm(), outcome.label());
        }
    }

    private static void assertFails(List<Outcome> outcomes, String forbiddenTrace) {
        for (var outcome : outcomes) {
            assertInstanceOf(EvalResult.Failure.class, outcome.result(), outcome.label() + ": " + outcome.result());
            if (forbiddenTrace != null) {
                assertFalse(outcome.result().traces().contains(forbiddenTrace),
                        outcome.label() + " reached " + forbiddenTrace + ": " + outcome.result().traces());
            }
        }
    }

    // ---- M1 rows (F5a, F11, F3)

    /**
     * F5a: an out-of-range top-level tag in a switch with guards and {@code default}. Base: fails (the guards
     * are ignored, and the dispatch has no branch for tag 5). It must keep failing and never reach default.
     */
    @Test
    void outOfRangeTagWithGuardsAndDefaultFails() {
        var source = source(S, """
                    static BigInteger m(S s) {
                        return switch (s) {
                            case A x when x.v().signum() > 0 -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            default -> Builtins.trace("default", BigInteger.TEN);
                        };
                    }
                """);
        assertFails(run(source, PlutusData.constr(5, PlutusData.integer(1))), "default");
    }

    /**
     * F11: a wrong tag on a one-constructor sum type. Base: accepted as the constructor, because the only
     * branch has no tag test, and the guard is ignored (1). M1: the guard-aware lowering checks the tag, so it fails.
     */
    @Test
    void wrongTagOnSingleConstructorSumType() {
        var source = source("""
                    sealed interface O permits OA {}
                    record OA(BigInteger v) implements O {}
                """, """
                    static BigInteger m(O o) {
                        return switch (o) {
                            case OA x when x.v().signum() < 0 -> BigInteger.ONE;
                            case OA x -> x.v();
                        };
                    }
                """);
        assertFails(run(source, PlutusData.constr(3, PlutusData.integer(5))), null);
    }

    /**
     * F3: {@code instanceof} in a guard over a malformed component. Base: the guard is never compiled, so the
     * guarded arm runs (1). M1: instanceof in a guard is a total dispatch, so tag 7 fails and never reaches a later arm.
     */
    @Test
    void guardInstanceofOverMalformedComponent() {
        var source = source(TU, """
                    static BigInteger m(U u) {
                        return switch (u) {
                            case P p when p.t() instanceof C -> BigInteger.ONE;
                            case P p -> BigInteger.TWO;
                            case Q q -> q.z();
                        };
                    }
                """);
        assertFails(run(source, PlutusData.constr(0, PlutusData.constr(7, PlutusData.integer(1)))), null);
    }

    // ---- M2b rows (malformed nested tags; lenient field contents)

    /** A nested component with an out-of-range tag. Base: the record pattern is dropped, so a later arm runs (2). */
    @Test
    void nestedComponentWithOutOfRangeTag() {
        var source = source(TU, """
                    static BigInteger m(U u) {
                        return switch (u) {
                            case P(C(var w)) -> w;
                            case P p -> BigInteger.TWO;
                            case Q q -> q.z();
                        };
                    }
                """);
        assertValue(2, run(source, PlutusData.constr(0, PlutusData.constr(9, PlutusData.integer(1)))));
    }

    /**
     * A variant-typed component carrying another variant's tag (F20): {@code W(B b)} holding tag 0. Base: the
     * record pattern is dropped, so {@code default} runs (0).
     */
    @Test
    void variantTypedComponentWithAnotherTag() {
        var source = source(S + """
                    record W(B b) {}
                    sealed interface V permits X, Y {}
                    record X(W w) implements V {}
                    record Y(BigInteger z) implements V {}
                """, """
                    static BigInteger m(V val) {
                        return switch (val) {
                            case X(W(B(var v))) -> v;
                            default -> BigInteger.ZERO;
                        };
                    }
                """);
        var w = PlutusData.constr(0, PlutusData.constr(0, PlutusData.integer(1)));
        assertValue(0, run(source, PlutusData.constr(0, w)));
    }

    private static final String FLAG = """
                sealed interface SF permits F, G {}
                record F(boolean on) implements SF {}
                record G(BigInteger z) implements SF {}
            """;

    /** A malformed Bool field (tag 5) decodes as false (ADR-051's lenient contract; threat model). */
    @Test
    void malformedBoolFieldDecodesAsFalse() {
        var source = source(FLAG, """
                    static BigInteger m(SF sf) {
                        return switch (sf) {
                            case F f -> f.on() ? BigInteger.ONE : BigInteger.TWO;
                            case G g -> g.z();
                        };
                    }
                """);
        assertValue(2, run(source, PlutusData.constr(0, PlutusData.constr(5))));
    }

    /** Extra fields are ignored: the field list's length is not checked. */
    @Test
    void extraFieldsAreIgnored() {
        var source = source(S, """
                    static BigInteger m(S s) {
                        return switch (s) {
                            case A x -> x.v();
                            case B y -> BigInteger.ZERO;
                        };
                    }
                """);
        assertValue(5, run(source, PlutusData.constr(0, PlutusData.integer(5), PlutusData.integer(6))));
    }

    /** A field of the wrong Data kind fails in its decoding builtin. */
    @Test
    void wrongFieldKindFails() {
        var source = source(S, """
                    static BigInteger m(S s) {
                        return switch (s) {
                            case A x -> x.v();
                            case B y -> BigInteger.ZERO;
                        };
                    }
                """);
        assertFails(run(source, PlutusData.constr(0, PlutusData.bytes(new byte[]{1}))), null);
    }

    /**
     * A guard over a malformed Bool falls to {@code default}. Base: the guard is ignored, so the guarded arm
     * runs (1); M1 makes the guard read the leniently decoded {@code false}, so {@code default} runs (2).
     */
    @Test
    void failedGuardOverMalformedBoolFallsToDefault() {
        var source = source(FLAG, """
                    static BigInteger m(SF sf) {
                        return switch (sf) {
                            case F f when f.on() -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                        };
                    }
                """);
        assertValue(2, run(source, PlutusData.constr(0, PlutusData.constr(5))));
    }

    /**
     * F31: a parent built with {@code new} from a malformed child keeps the child as given (here an extra
     * field), and reading it is lenient (5).
     */
    @Test
    void malformedChildInsideConstructedParent() {
        var source = source(TU, """
                    static BigInteger m(PlutusData raw) {
                        T t = (T)(Object) raw;
                        U u = new P(t);
                        return switch (u) {
                            case P p -> switch (p.t()) {
                                case C c -> c.w();
                                case D d -> BigInteger.ZERO;
                            };
                            case Q q -> q.z();
                        };
                    }
                """);
        assertValue(5, run(source, PlutusData.constr(0, PlutusData.integer(5), PlutusData.integer(6))));
    }

    // ---- M3b row (F23, #287)

    /**
     * F23: a ledger {@code Rational} assigned to a user {@code Rational} (javac: incompatible types). JuLC accepts
     * it and reads the ledger value through the user's layout (7). A known parity exception owned by #287.
     */
    @Test
    void ledgerRationalAssignedToUserRational() {
        var source = """
                import java.math.BigInteger;

                public class Probe {
                    record Rational(BigInteger denominator, BigInteger numerator) {}

                    static BigInteger m(BigInteger a, BigInteger b) {
                        Rational q = new org.julclang.ledger.Rational(a, b);
                        return q.numerator();
                    }
                }
                """;
        assertValue(7, run(source, PlutusData.integer(5), PlutusData.integer(7)));
    }
}
