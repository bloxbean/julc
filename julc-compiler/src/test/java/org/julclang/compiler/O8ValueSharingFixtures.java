package org.julclang.compiler;

import org.julclang.core.PlutusData;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * ADR-042 (O8) fixtures: {@code compileMethod} sources whose native Value conversions are
 * repeated in shapes the strict-prefix sharing rule must share, must leave alone, or must
 * share only partially. Every fixture is evaluated on Java, Truffle and Scalus by
 * {@link O8ValueSharingTest}; the {@code shares} flag is the expected rule provenance at the
 * safe profile and {@code conversionsBefore/After} the expected number of {@code unValueData}
 * applications in the emitted program.
 */
final class O8ValueSharingFixtures {

    record Input(String name, List<PlutusData> args, boolean success) {
        @Override
        public String toString() { return name; }
    }

    record Fixture(String name, String source, String method, boolean shares,
                   int conversionsBefore, int conversionsAfter, List<Input> inputs) {}

    static final String IMPORTS = """
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcValue;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.ContextsLib;
            import org.julclang.stdlib.lib.NativeValueLib;
            import java.math.BigInteger;
            """;

    static final byte[] POLICY = policy(0x11);
    static final byte[] OTHER_POLICY = policy(0x22);
    static final byte[] TOKEN = "token".getBytes(StandardCharsets.UTF_8);
    static final byte[] OTHER_TOKEN = "other".getBytes(StandardCharsets.UTF_8);

    /** Canonical Value Data: one policy, one token, quantity 42. */
    static final PlutusData VALID = value(POLICY, TOKEN, 42);
    /** Canonical Value Data with a different policy: valid, but neither contains the other. */
    static final PlutusData DIFFERENT = value(OTHER_POLICY, TOKEN, 7);
    /** Java VM failure: "UnValueData: expected Map data, got IntegerData". */
    static final PlutusData NOT_A_MAP = PlutusData.integer(1);
    /** Java VM failure: "UnValueData: zero quantity". */
    static final PlutusData ZERO_QUANTITY = value(POLICY, TOKEN, 0);
    /** Non-canonical: policies out of order. Java VM failure: "UnValueData: currencies not strictly ordered or duplicate". */
    static final PlutusData UNSORTED = PlutusData.map(
            new PlutusData.Pair(PlutusData.bytes(OTHER_POLICY),
                    PlutusData.map(new PlutusData.Pair(PlutusData.bytes(TOKEN), PlutusData.integer(1)))),
            new PlutusData.Pair(PlutusData.bytes(POLICY),
                    PlutusData.map(new PlutusData.Pair(PlutusData.bytes(TOKEN), PlutusData.integer(42)))));

    static final PlutusData P = PlutusData.bytes(POLICY);
    static final PlutusData T = PlutusData.bytes(TOKEN);
    static final PlutusData T2 = PlutusData.bytes(OTHER_TOKEN);
    static final PlutusData TRUE = PlutusData.constr(1);
    static final PlutusData FALSE = PlutusData.constr(0);
    static final PlutusData TOKENS = PlutusData.list(T, T2, T);
    static final PlutusData NO_TOKENS = PlutusData.list();
    static final PlutusData BAD_TOKENS = PlutusData.list(T, PlutusData.integer(9));

    /** 0: the O8 motivating shape; both conversions lead, one survives. */
    static final String REPEATED = IMPORTS + """
            class Repeated {
                static BigInteger repeated(PlutusData data, byte[] policy, byte[] token) {
                    return NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data))
                            .add(NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data)));
                }
            }
            """;

    /** 1: the documented manual workaround; the safe-profile bytes of 0 must equal these. */
    static final String SHARED = IMPORTS + """
            class Shared {
                static BigInteger shared(PlutusData data, byte[] policy, byte[] token) {
                    JulcValue value = NativeValueLib.fromData(data);
                    return NativeValueLib.lookupCoin(policy, token, value)
                            .add(NativeValueLib.lookupCoin(policy, token, value));
                }
            }
            """;

    /** 2: both spellings of the conversion are one conversion. */
    static final String MIXED = IMPORTS + """
            class Mixed {
                static BigInteger mixed(PlutusData data, byte[] policy, byte[] token) {
                    return NativeValueLib.lookupCoin(policy, token, Builtins.unValueData(data))
                            .add(NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data)));
                }
            }
            """;

    /** 3: a trace precedes the conversions; sharing happens after the trace, never before. */
    static final String TRACE_FIRST = IMPORTS + """
            class TraceFirst {
                static BigInteger traceFirst(PlutusData data, byte[] policy, byte[] token) {
                    ContextsLib.trace("before");
                    return NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data))
                            .add(NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data)));
                }
            }
            """;

    /** 4: only one branch converts first; the other traces first. Nothing may be shared. */
    static final String BRANCH_ONLY = IMPORTS + """
            class BranchOnly {
                static BigInteger branchOnly(PlutusData data, byte[] policy, byte[] token, boolean flag) {
                    if (flag) {
                        return NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data));
                    }
                    ContextsLib.trace("other");
                    return NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data)).add(BigInteger.ONE);
                }
            }
            """;

    /**
     * 5: both branches convert first under a trivial condition. Branches are exclusive, so
     * every path evaluates one conversion either way: sharing above the conditional would only
     * add a binding, and the pass leaves it alone.
     */
    static final String BOTH_BRANCHES = IMPORTS + """
            class BothBranches {
                static BigInteger bothBranches(PlutusData data, byte[] policy, byte[] token, byte[] other, boolean flag) {
                    if (flag) {
                        return NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data));
                    }
                    return NativeValueLib.lookupCoin(policy, other, NativeValueLib.fromData(data));
                }
            }
            """;

    /** 6: two variables, each converted twice, both leading in turn; order a then b is kept. */
    static final String TWO_VARS = IMPORTS + """
            class TwoVars {
                static boolean sameValue(PlutusData a, PlutusData b) {
                    return NativeValueLib.contains(NativeValueLib.fromData(a), NativeValueLib.fromData(b))
                            && NativeValueLib.contains(NativeValueLib.fromData(b), NativeValueLib.fromData(a));
                }
            }
            """;

    /**
     * 7: statement-sequenced. {@code a} leads at the method body and is shared (three sites
     * become one); {@code b} sits behind a saturated {@code lookupCoin} at that scope but leads
     * at the inner {@code need} binding, whose subtree holds both of its sites, so it is shared
     * there. Four conversions become two.
     */
    static final String SEQUENTIAL = IMPORTS + """
            class Sequential {
                static BigInteger sequential(PlutusData a, PlutusData b, byte[] policy, byte[] token) {
                    BigInteger have = NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(a));
                    BigInteger need = NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(b));
                    return have.add(need)
                            .add(NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(a)))
                            .add(NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(b)));
                }
            }
            """;

    /**
     * 8: the documented limitation. In one flat expression the smallest scope holding both
     * conversions of {@code b} starts with a saturated {@code lookupCoin} on {@code a}, so
     * {@code b} is never first and stays as written; only {@code a} is shared.
     */
    static final String INTERLEAVED = IMPORTS + """
            class Interleaved {
                static BigInteger interleaved(PlutusData a, PlutusData b, byte[] policy, byte[] token) {
                    return NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(a))
                            .add(NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(b)))
                            .add(NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(a)))
                            .add(NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(b)));
                }
            }
            """;

    /** 9: a helper rebinds the name {@code data}; its conversion is a different variable. */
    static final String SHADOW = IMPORTS + """
            class Shadow {
                static BigInteger helper(PlutusData data, byte[] policy, byte[] token) {
                    return NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data));
                }
                static BigInteger shadow(PlutusData data, PlutusData other, byte[] policy, byte[] token) {
                    return NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data))
                            .add(helper(other, policy, token))
                            .add(NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data)));
                }
            }
            """;

    /** 10: one leading conversion before a loop and one per iteration inside it: hoisted out. */
    static final String LOOP_HOIST = IMPORTS + """
            class LoopHoist {
                static BigInteger loopHoist(PlutusData data, JulcList<PlutusData> tokens, byte[] policy, byte[] token) {
                    BigInteger total = NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data));
                    for (PlutusData t : tokens) {
                        total = total.add(NativeValueLib.lookupCoin(policy, Builtins.unBData(t), NativeValueLib.fromData(data)));
                    }
                    return total;
                }
            }
            """;

    /** 11: two conversions per iteration and none outside: shared once per iteration. */
    static final String LOOP_BODY = IMPORTS + """
            class LoopBody {
                static BigInteger loopBody(PlutusData data, JulcList<PlutusData> tokens, byte[] policy) {
                    BigInteger total = BigInteger.ZERO;
                    for (PlutusData t : tokens) {
                        JulcValue doubled = NativeValueLib.scale(BigInteger.valueOf(2), NativeValueLib.fromData(data));
                        total = total.add(NativeValueLib.lookupCoin(policy, Builtins.unBData(t), doubled))
                                .add(NativeValueLib.lookupCoin(policy, Builtins.unBData(t), NativeValueLib.fromData(data)));
                    }
                    return total;
                }
            }
            """;

    /** 12: control; one conversion is never touched. */
    static final String SINGLE = IMPORTS + """
            class Single {
                static BigInteger single(PlutusData data, byte[] policy, byte[] token) {
                    return NativeValueLib.lookupCoin(policy, token, NativeValueLib.fromData(data));
                }
            }
            """;

    private static final List<Input> DATA_POLICY_TOKEN = List.of(
            new Input("valid", List.of(VALID, P, T), true),
            new Input("absent-token", List.of(VALID, P, T2), true),
            new Input("not-a-map", List.of(NOT_A_MAP, P, T), false),
            new Input("zero-quantity", List.of(ZERO_QUANTITY, P, T), false),
            new Input("unsorted-policies", List.of(UNSORTED, P, T), false));

    private static final List<Input> TWO_DATA_POLICY_TOKEN = List.of(
            new Input("valid", List.of(VALID, DIFFERENT, P, T), true),
            new Input("a-not-a-map", List.of(NOT_A_MAP, VALID, P, T), false),
            new Input("a-unsorted-b-valid", List.of(UNSORTED, VALID, P, T), false),
            new Input("a-valid-b-unsorted", List.of(VALID, UNSORTED, P, T), false),
            new Input("b-zero-quantity", List.of(VALID, ZERO_QUANTITY, P, T), false),
            new Input("a-not-a-map-b-zero-quantity", List.of(NOT_A_MAP, ZERO_QUANTITY, P, T), false),
            new Input("a-zero-quantity-b-not-a-map", List.of(ZERO_QUANTITY, NOT_A_MAP, P, T), false));

    static final List<Fixture> FIXTURES = List.of(
            new Fixture("REPEATED", REPEATED, "repeated", true, 2, 1, DATA_POLICY_TOKEN),
            new Fixture("SHARED", SHARED, "shared", false, 1, 1, DATA_POLICY_TOKEN),
            new Fixture("MIXED", MIXED, "mixed", true, 2, 1, DATA_POLICY_TOKEN),
            new Fixture("TRACE_FIRST", TRACE_FIRST, "traceFirst", true, 2, 1, DATA_POLICY_TOKEN),
            new Fixture("BRANCH_ONLY", BRANCH_ONLY, "branchOnly", false, 2, 2, List.of(
                    new Input("valid-then", List.of(VALID, P, T, TRUE), true),
                    new Input("valid-else", List.of(VALID, P, T, FALSE), true),
                    new Input("not-a-map-then", List.of(NOT_A_MAP, P, T, TRUE), false),
                    new Input("not-a-map-else", List.of(NOT_A_MAP, P, T, FALSE), false),
                    new Input("zero-quantity-else", List.of(ZERO_QUANTITY, P, T, FALSE), false))),
            new Fixture("BOTH_BRANCHES", BOTH_BRANCHES, "bothBranches", false, 2, 2, List.of(
                    new Input("valid-then", List.of(VALID, P, T, T2, TRUE), true),
                    new Input("valid-else", List.of(VALID, P, T, T2, FALSE), true),
                    new Input("not-a-map-then", List.of(NOT_A_MAP, P, T, T2, TRUE), false),
                    new Input("zero-quantity-else", List.of(ZERO_QUANTITY, P, T, T2, FALSE), false))),
            new Fixture("TWO_VARS", TWO_VARS, "sameValue", true, 4, 2, List.of(
                    new Input("same", List.of(VALID, VALID), true),
                    new Input("different", List.of(VALID, DIFFERENT), true),
                    new Input("a-not-a-map", List.of(NOT_A_MAP, VALID), false),
                    new Input("b-zero-quantity", List.of(VALID, ZERO_QUANTITY), false),
                    new Input("a-not-a-map-b-zero-quantity", List.of(NOT_A_MAP, ZERO_QUANTITY), false),
                    new Input("a-zero-quantity-b-not-a-map", List.of(ZERO_QUANTITY, NOT_A_MAP), false))),
            new Fixture("SEQUENTIAL", SEQUENTIAL, "sequential", true, 4, 2, TWO_DATA_POLICY_TOKEN),
            new Fixture("INTERLEAVED", INTERLEAVED, "interleaved", true, 4, 3, TWO_DATA_POLICY_TOKEN),
            new Fixture("SHADOW", SHADOW, "shadow", true, 3, 2, List.of(
                    new Input("valid", List.of(VALID, DIFFERENT, P, T), true),
                    new Input("data-not-a-map", List.of(NOT_A_MAP, VALID, P, T), false),
                    new Input("other-zero-quantity", List.of(VALID, ZERO_QUANTITY, P, T), false),
                    new Input("both-bad", List.of(ZERO_QUANTITY, NOT_A_MAP, P, T), false))),
            new Fixture("LOOP_HOIST", LOOP_HOIST, "loopHoist", true, 2, 1, List.of(
                    new Input("three-tokens", List.of(VALID, TOKENS, P, T), true),
                    new Input("no-tokens", List.of(VALID, NO_TOKENS, P, T), true),
                    new Input("not-a-map", List.of(NOT_A_MAP, TOKENS, P, T), false),
                    new Input("bad-token-element", List.of(VALID, BAD_TOKENS, P, T), false))),
            new Fixture("LOOP_BODY", LOOP_BODY, "loopBody", true, 2, 1, List.of(
                    new Input("three-tokens", List.of(VALID, TOKENS, P), true),
                    new Input("no-tokens", List.of(VALID, NO_TOKENS, P), true),
                    new Input("not-a-map", List.of(NOT_A_MAP, TOKENS, P), false),
                    new Input("zero-quantity-no-tokens", List.of(ZERO_QUANTITY, NO_TOKENS, P), true),
                    new Input("bad-token-element", List.of(VALID, BAD_TOKENS, P), false))),
            new Fixture("SINGLE", SINGLE, "single", false, 1, 1, DATA_POLICY_TOKEN));

    static PlutusData value(byte[] policy, byte[] token, long quantity) {
        return PlutusData.map(new PlutusData.Pair(PlutusData.bytes(policy),
                PlutusData.map(new PlutusData.Pair(PlutusData.bytes(token), PlutusData.integer(quantity)))));
    }

    private static byte[] policy(int fill) {
        var bytes = new byte[28];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    private O8ValueSharingFixtures() {}
}
