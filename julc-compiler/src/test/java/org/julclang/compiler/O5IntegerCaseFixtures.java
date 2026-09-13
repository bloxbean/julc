package org.julclang.compiler;

import java.util.List;

/**
 * Validator sources for the ADR-041 O5 integer Case dispatch tests. The golden bytes in
 * {@code optimization/o5-pre-change-bytes.txt} were captured from these exact sources at the
 * pre-change base commit, so this file must not change without recapturing that fixture.
 */
final class O5IntegerCaseFixtures {

    private O5IntegerCaseFixtures() {
    }

    /** Two constructors, one with a field. */
    static final String TWO = """
            import java.math.BigInteger;
            @MintingValidator class TwoWay {
                sealed interface Action permits Pay, Cancel {}
                record Pay(BigInteger amount) implements Action {}
                record Cancel() implements Action {}
                @Entrypoint static boolean validate(Action r, ScriptContext ctx) {
                    return switch (r) {
                        case Pay p -> p.amount().compareTo(BigInteger.ZERO) > 0;
                        case Cancel c -> true;
                    };
                }
            }
            """;

    /** Three constructors with a {@code default} arm covering the last one. */
    static final String THREE = """
            import java.math.BigInteger;
            @MintingValidator class ThreeWay {
                sealed interface Action permits Mint, Burn, Pause {}
                record Mint(BigInteger amount) implements Action {}
                record Burn(BigInteger amount) implements Action {}
                record Pause() implements Action {}
                @Entrypoint static boolean validate(Action r, ScriptContext ctx) {
                    return switch (r) {
                        case Mint m -> m.amount().compareTo(BigInteger.ZERO) > 0;
                        case Burn b -> b.amount().compareTo(BigInteger.ZERO) < 0;
                        default -> false;
                    };
                }
            }
            """;

    /** Five zero-field constructors: pure dispatch, nothing else to evaluate. */
    static final String FIVE = """
            @MintingValidator class FiveWay {
                sealed interface Step permits A, B, C, D, E {}
                record A() implements Step {}
                record B() implements Step {}
                record C() implements Step {}
                record D() implements Step {}
                record E() implements Step {}
                @Entrypoint static boolean validate(Step s, ScriptContext ctx) {
                    return switch (s) {
                        case A a -> true;
                        case B b -> false;
                        case C c -> true;
                        case D d -> false;
                        case E e -> true;
                    };
                }
            }
            """;

    /** Outer two-way dispatch with an inner three-way dispatch on a field. */
    static final String NESTED = """
            import java.math.BigInteger;
            @MintingValidator class NestedWay {
                sealed interface Mode permits Fast, Slow, Manual {}
                record Fast() implements Mode {}
                record Slow() implements Mode {}
                record Manual(BigInteger limit) implements Mode {}
                sealed interface Action permits Pay, Cancel {}
                record Pay(BigInteger amount, Mode mode) implements Action {}
                record Cancel() implements Action {}
                @Entrypoint static boolean validate(Action r, ScriptContext ctx) {
                    return switch (r) {
                        case Pay p -> switch (p.mode()) {
                            case Fast f -> p.amount().compareTo(BigInteger.TEN) < 0;
                            case Slow s -> true;
                            case Manual m -> p.amount().compareTo(m.limit()) <= 0;
                        };
                        case Cancel c -> true;
                    };
                }
            }
            """;

    /** Single constructor: no dispatch is generated, so O5 must leave it byte-identical. */
    static final String SINGLE = """
            import java.math.BigInteger;
            @MintingValidator class SingleWay {
                sealed interface Action permits Only {}
                record Only(BigInteger value) implements Action {}
                @Entrypoint static boolean validate(Action r, ScriptContext ctx) {
                    return switch (r) { case Only o -> o.value().compareTo(BigInteger.ZERO) > 0; };
                }
            }
            """;

    static final List<String> SOURCES = List.of(TWO, THREE, FIVE, NESTED, SINGLE);

    /** Constructor count of the outer dispatch in each source, for expected failure text. */
    static final List<Integer> OUTER_CONSTRUCTORS = List.of(2, 3, 5, 2, 1);
}
