package org.julclang.compiler;

import org.julclang.core.PlutusData;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * ADR-044 (O15) fixtures: {@code compileMethod} sources whose record field projections are
 * repeated in shapes the strict-prefix sharing rule must share (as one field chain, as the
 * fields prefix, or both), must leave alone, or must share only in part. Every fixture is
 * evaluated on Java, Truffle and Scalus by {@link O15ProjectionSharingTest}; {@code shares} is
 * the expected O15 provenance at the safe profile, {@code fieldBindings}/{@code prefixBindings}
 * the expected number of {@code #field-N}/{@code #fields-N} lets, and
 * {@code chainsBefore/After} the number of field-chain units in the emitted PIR (a chain rooted
 * at a variable's fields list or at a shared {@code #fields-N} list; a chain on a chain only
 * becomes a unit once its inner projection has been bound to a variable).
 */
final class O15ProjectionSharingFixtures {

    /**
     * One evaluation. {@code index}/{@code size} are non-null exactly when the input fails at a
     * {@code get} site that O9 promotes at the costed profile once O15 has bound the list
     * ({@link #FIELD_THEN_INDEX}); the array failure text must then name that index and size.
     */
    record Input(String name, List<PlutusData> args, boolean success, Long index, Integer size) {
        static Input ok(String name, PlutusData... args) {
            return new Input(name, List.of(args), true, null, null);
        }
        static Input fails(String name, PlutusData... args) {
            return new Input(name, List.of(args), false, null, null);
        }
        static Input outOfRange(String name, long index, int size, PlutusData... args) {
            return new Input(name, List.of(args), false, index, size);
        }
        @Override
        public String toString() { return name; }
    }

    record Fixture(String name, String source, String method, boolean shares, int fieldBindings,
                   int prefixBindings, int chainsBefore, int chainsAfter, List<Input> inputs) {}

    static final String IMPORTS = """
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.ContextsLib;
            import org.julclang.ledger.TxInfo;
            import java.math.BigInteger;
            """;

    /** A record with one field per decode arm, plus a nested record for chain-on-chain shapes. */
    static final String BOX = """
                record Inner(BigInteger x, BigInteger y) {}
                record Box(BigInteger amount, JulcList<BigInteger> items, byte[] owner, boolean open, String label, Inner inner) {}
            """;

    static final byte[] OWNER = owner(0x11);

    static final PlutusData TRUE = PlutusData.constr(1);
    static final PlutusData FALSE = PlutusData.constr(0);
    static final PlutusData TEN = PlutusData.integer(10);
    static final PlutusData FIVE = PlutusData.integer(5);
    static final PlutusData OK = utf8("ok");
    static final PlutusData OTHER = utf8("other");
    static final PlutusData NONE = utf8("none");
    static final PlutusData THREE_INTS = ints(1, 2, 3);
    static final PlutusData NO_INTS = PlutusData.list();

    /** A well-formed box: amount 7, three items, owner, open, label "ok", inner (3, 4). */
    static final PlutusData BOX_OPEN = box(7, THREE_INTS, OWNER, true, "ok", 3, 4);
    /** A well-formed closed box with one item and label "other". */
    static final PlutusData BOX_CLOSED = box(20, ints(9), OWNER, false, "other", 1, 2);
    /** Not a constructor at all: every projection fails at {@code unConstrData}. */
    static final PlutusData NOT_A_RECORD = PlutusData.integer(1);
    /** A constructor with no fields: the first projection fails at {@code headList}. */
    static final PlutusData EMPTY_RECORD = PlutusData.constr(0);
    /** {@code amount} is a byte string: the decode fails at {@code unIData}. */
    static final PlutusData BAD_AMOUNT = box(PlutusData.bytes(OWNER), THREE_INTS, OWNER, TRUE, OK, 3, 4);
    /** {@code inner} is an integer: the nested projection fails at {@code unConstrData}. */
    static final PlutusData BAD_INNER = PlutusData.constr(0, PlutusData.integer(7), THREE_INTS,
            PlutusData.bytes(OWNER), TRUE, OK, PlutusData.integer(1));

    /**
     * 0: the corpus shape (sequential same field): the leading chain is shared with both later
     * sites. {@code compareTo} lowers to a three-way comparison that evaluates its receiver
     * twice, so the source's three projections are four chains in PIR; all four become one.
     */
    static final String REPEATED = IMPORTS + """
            class Repeated {
            """ + BOX + """
                static BigInteger repeated(Box b, BigInteger limit) {
                    if (b.amount().compareTo(limit) > 0) {
                        return b.amount().subtract(limit);
                    }
                    return b.amount().add(limit);
                }
            }
            """;

    /** 1: the hand-written binding; the safe-profile bytes of 0 must equal these. */
    static final String MANUAL = IMPORTS + """
            class Manual {
            """ + BOX + """
                static BigInteger manual(Box b, BigInteger limit) {
                    BigInteger amount = b.amount();
                    if (amount.compareTo(limit) > 0) {
                        return amount.subtract(limit);
                    }
                    return amount.add(limit);
                }
            }
            """;

    /** 2: three distinct fields, each once: only the fields prefix is shared. */
    static final String TWO_FIELDS = IMPORTS + """
            class TwoFields {
            """ + BOX + """
                static BigInteger twoFields(Box b) {
                    return b.amount().add(BigInteger.valueOf(b.items().size())).add(BigInteger.valueOf(b.owner().length));
                }
            }
            """;

    /** 3: one repeated field and one other: the field chain first, then the prefix under it. */
    static final String MIXED = IMPORTS + """
            class Mixed {
            """ + BOX + """
                static BigInteger mixed(Box b, BigInteger limit) {
                    if (b.amount().compareTo(limit) > 0) {
                        return b.amount().subtract(BigInteger.valueOf(b.owner().length));
                    }
                    return b.amount();
                }
            }
            """;

    /** 4: the same field in both arms of a trivial condition: exclusive, left as written. */
    static final String BOTH_BRANCHES = IMPORTS + """
            class BothBranches {
            """ + BOX + """
                static BigInteger bothBranches(Box b, boolean flag) {
                    if (flag) {
                        return b.amount().add(BigInteger.ONE);
                    }
                    return b.amount().add(BigInteger.TWO);
                }
            }
            """;

    /** 5: a trace precedes the projections; sharing happens after the trace, never before. */
    static final String TRACE_FIRST = IMPORTS + """
            class TraceFirst {
            """ + BOX + """
                static BigInteger traceFirst(Box b) {
                    ContextsLib.trace("before");
                    return b.amount().add(b.amount());
                }
            }
            """;

    /** 6: a saturated call runs first; the field is shared one scope down, at the binding that leads with it. */
    static final String CALL_FIRST = IMPORTS + """
            class CallFirst {
            """ + BOX + """
                static BigInteger callFirst(Box b, BigInteger x) {
                    BigInteger square = x.multiply(x);
                    BigInteger amount = b.amount();
                    return amount.add(b.amount()).add(square);
                }
            }
            """;

    /** 7: an alias is a different root; nothing is shared across it (documented limitation). */
    static final String ALIAS = IMPORTS + """
            class Alias {
            """ + BOX + """
                static BigInteger alias(Box b) {
                    Box same = b;
                    return b.amount().add(same.amount());
                }
            }
            """;

    /** 8: a helper rebinds the name {@code b}; each scope shares its own projections. */
    static final String SHADOW = IMPORTS + """
            class Shadow {
            """ + BOX + """
                static BigInteger helper(Box b) {
                    return b.amount().add(b.amount());
                }
                static BigInteger shadow(Box b, Box other) {
                    return b.amount().add(helper(other)).add(b.amount());
                }
            }
            """;

    /** 9: one leading projection before a loop and one per iteration inside it: hoisted out. */
    static final String LOOP_HOIST = IMPORTS + """
            class LoopHoist {
            """ + BOX + """
                static BigInteger loopHoist(Box b, JulcList<BigInteger> xs) {
                    BigInteger total = b.amount();
                    for (BigInteger x : xs) {
                        total = total.add(x).add(b.amount());
                    }
                    return total;
                }
            }
            """;

    /** 10: two projections of the loop item per iteration and none outside: shared once per iteration. */
    static final String LOOP_BODY = IMPORTS + """
            class LoopBody {
            """ + BOX + """
                static BigInteger loopBody(JulcList<Box> boxes) {
                    BigInteger total = BigInteger.ZERO;
                    for (Box box : boxes) {
                        total = total.add(box.amount()).add(box.amount());
                    }
                    return total;
                }
            }
            """;

    /** 11: chain on chain: the repeated inner projection is shared, then the prefix of the shared inner. */
    static final String NESTED = IMPORTS + """
            class Nested {
            """ + BOX + """
                static BigInteger nested(Box b) {
                    BigInteger x = b.inner().x();
                    BigInteger y = b.inner().y();
                    return x.add(y);
                }
            }
            """;

    /** 12: a nested record bound to a local; its repeated field is shared below that binding. */
    static final String NESTED_LET = IMPORTS + """
            class NestedLet {
            """ + BOX + """
                static BigInteger nestedLet(Box b) {
                    Inner inner = b.inner();
                    return inner.x().add(inner.x());
                }
            }
            """;

    /** 13: a cast root: shared exactly like a typed parameter, with identical failure text on malformed input. */
    static final String CAST = IMPORTS + """
            class Cast {
            """ + BOX + """
                static BigInteger cast(PlutusData d, BigInteger limit) {
                    Box b = (Box) d;
                    if (b.amount().compareTo(limit) > 0) {
                        return b.amount().subtract(limit);
                    }
                    return b.amount().add(limit);
                }
            }
            """;

    /**
     * 14: the Bool and String decode arms are units. {@code open} leads the method and is
     * shared there; {@code label} leads the then-branch and is shared there; the prefix is
     * shared above both.
     */
    static final String BOOL_STRING = IMPORTS + """
            class BoolString {
            """ + BOX + """
                static BigInteger boolString(Box b, String expected) {
                    if (b.open()) {
                        if (b.label().equals(expected)) {
                            return BigInteger.ONE;
                        }
                        if (b.label().equals("other")) {
                            return BigInteger.TWO;
                        }
                        if (b.open()) {
                            return BigInteger.valueOf(3);
                        }
                        return BigInteger.valueOf(4);
                    }
                    return BigInteger.valueOf(5);
                }
            }
            """;

    /**
     * 15: the O15-to-O9 handoff. At the safe profile the list projection is bound once; at the
     * costed profile that binding is a proven list, so ADR-043 promotes both {@code get} sites.
     */
    static final String FIELD_THEN_INDEX = IMPORTS + """
            class FieldThenIndex {
            """ + BOX + """
                static BigInteger fieldThenIndex(Box b) {
                    return b.items().get(0).add(b.items().get(1));
                }
            }
            """;

    /** 16: control; one projection is never touched. */
    static final String SINGLE = IMPORTS + """
            class Single {
            """ + BOX + """
                static BigInteger single(Box b) {
                    return b.amount();
                }
            }
            """;

    /** 17: the ledger shape from the example corpus: {@code outputs} twice and {@code fee} in each branch. */
    static final String LEDGER = IMPORTS + """
            class Ledger {
                static BigInteger ledger(TxInfo txInfo) {
                    if (txInfo.outputs().isEmpty()) {
                        return txInfo.fee();
                    }
                    BigInteger count = BigInteger.valueOf(txInfo.outputs().size());
                    return count.add(txInfo.fee());
                }
            }
            """;

    private static final List<Input> BOX_LIMIT = List.of(
            Input.ok("above", BOX_OPEN, FIVE),
            Input.ok("below", BOX_OPEN, TEN),
            Input.fails("not-a-record", NOT_A_RECORD, FIVE),
            Input.fails("empty-record", EMPTY_RECORD, FIVE),
            Input.fails("bad-amount", BAD_AMOUNT, FIVE));

    private static final List<Input> BOX_ONLY = List.of(
            Input.ok("open", BOX_OPEN),
            Input.ok("closed", BOX_CLOSED),
            Input.fails("not-a-record", NOT_A_RECORD),
            Input.fails("empty-record", EMPTY_RECORD),
            Input.fails("bad-amount", BAD_AMOUNT));

    static final List<Fixture> FIXTURES = List.of(
            new Fixture("REPEATED", REPEATED, "repeated", true, 1, 0, 4, 1, BOX_LIMIT),
            new Fixture("MANUAL", MANUAL, "manual", false, 0, 0, 1, 1, BOX_LIMIT),
            new Fixture("TWO_FIELDS", TWO_FIELDS, "twoFields", true, 0, 1, 3, 3, BOX_ONLY),
            new Fixture("MIXED", MIXED, "mixed", true, 1, 1, 5, 2, BOX_LIMIT),
            new Fixture("BOTH_BRANCHES", BOTH_BRANCHES, "bothBranches", false, 0, 0, 2, 2, List.of(
                    Input.ok("then", BOX_OPEN, TRUE),
                    Input.ok("else", BOX_OPEN, FALSE),
                    Input.fails("not-a-record-then", NOT_A_RECORD, TRUE),
                    Input.fails("bad-amount-else", BAD_AMOUNT, FALSE))),
            new Fixture("TRACE_FIRST", TRACE_FIRST, "traceFirst", true, 1, 0, 2, 1, BOX_ONLY),
            new Fixture("CALL_FIRST", CALL_FIRST, "callFirst", true, 1, 0, 2, 1, BOX_LIMIT),
            new Fixture("ALIAS", ALIAS, "alias", false, 0, 0, 2, 2, BOX_ONLY),
            new Fixture("SHADOW", SHADOW, "shadow", true, 2, 0, 4, 2, List.of(
                    Input.ok("both", BOX_OPEN, BOX_CLOSED),
                    Input.fails("b-not-a-record", NOT_A_RECORD, BOX_CLOSED),
                    Input.fails("other-bad-amount", BOX_OPEN, BAD_AMOUNT),
                    Input.fails("both-bad", BAD_AMOUNT, NOT_A_RECORD))),
            new Fixture("LOOP_HOIST", LOOP_HOIST, "loopHoist", true, 1, 0, 2, 1, List.of(
                    Input.ok("three", BOX_OPEN, THREE_INTS),
                    Input.ok("none", BOX_OPEN, NO_INTS),
                    Input.fails("not-a-record", NOT_A_RECORD, THREE_INTS),
                    Input.fails("bad-element", BOX_OPEN, PlutusData.list(PlutusData.bytes(OWNER))))),
            new Fixture("LOOP_BODY", LOOP_BODY, "loopBody", true, 1, 0, 2, 1, List.of(
                    Input.ok("three", PlutusData.list(BOX_OPEN, BOX_CLOSED, BOX_OPEN)),
                    Input.ok("none", PlutusData.list()),
                    Input.fails("second-not-a-record", PlutusData.list(BOX_OPEN, NOT_A_RECORD)),
                    Input.fails("bad-amount", PlutusData.list(BAD_AMOUNT)))),
            // Before sharing only the two inner projections root at a variable; afterwards the
            // inner binding and both outer chains (rooted at the shared fields list) are units.
            new Fixture("NESTED", NESTED, "nested", true, 1, 1, 2, 3, List.of(
                    Input.ok("open", BOX_OPEN),
                    Input.ok("closed", BOX_CLOSED),
                    Input.fails("not-a-record", NOT_A_RECORD),
                    Input.fails("empty-record", EMPTY_RECORD),
                    Input.fails("bad-inner", BAD_INNER))),
            new Fixture("NESTED_LET", NESTED_LET, "nestedLet", true, 1, 0, 3, 2, List.of(
                    Input.ok("open", BOX_OPEN),
                    Input.fails("not-a-record", NOT_A_RECORD),
                    Input.fails("bad-inner", BAD_INNER))),
            new Fixture("CAST", CAST, "cast", true, 1, 0, 4, 1, BOX_LIMIT),
            new Fixture("BOOL_STRING", BOOL_STRING, "boolString", true, 2, 1, 4, 2, List.of(
                    Input.ok("open-expected", BOX_OPEN, OK),
                    Input.ok("open-other", BOX_OPEN, NONE),
                    Input.ok("closed", BOX_CLOSED, OK),
                    Input.fails("not-a-record", NOT_A_RECORD, OK),
                    Input.fails("empty-record", EMPTY_RECORD, OK),
                    Input.fails("bad-open", box(PlutusData.integer(7), THREE_INTS, OWNER, PlutusData.integer(1), OK, 3, 4), OK),
                    Input.fails("bad-label", box(PlutusData.integer(7), THREE_INTS, OWNER, TRUE, PlutusData.integer(1), 3, 4), OK))),
            new Fixture("FIELD_THEN_INDEX", FIELD_THEN_INDEX, "fieldThenIndex", true, 1, 0, 2, 1, List.of(
                    Input.ok("three", BOX_OPEN),
                    Input.outOfRange("one", 1, 1, BOX_CLOSED),
                    Input.outOfRange("none", 0, 0, box(7, NO_INTS, OWNER, true, "ok", 3, 4)),
                    Input.fails("not-a-record", NOT_A_RECORD),
                    Input.fails("bad-items", box(7, PlutusData.integer(1), OWNER, true, "ok", 3, 4)))),
            new Fixture("SINGLE", SINGLE, "single", false, 0, 0, 1, 1, BOX_ONLY),
            new Fixture("LEDGER", LEDGER, "ledger", true, 1, 1, 4, 3, List.of(
                    Input.ok("two-outputs", txInfo(PlutusData.constr(0), PlutusData.constr(0))),
                    Input.ok("no-outputs", txInfo()),
                    Input.fails("not-a-record", NOT_A_RECORD),
                    Input.fails("empty-record", EMPTY_RECORD))));

    static PlutusData box(long amount, PlutusData items, byte[] owner, boolean open, String label, long x, long y) {
        return box(PlutusData.integer(amount), items, owner, open ? TRUE : FALSE, utf8(label), x, y);
    }

    static PlutusData box(PlutusData amount, PlutusData items, byte[] owner, PlutusData open, PlutusData label,
                          long x, long y) {
        return PlutusData.constr(0, amount, items, PlutusData.bytes(owner), open, label,
                PlutusData.constr(0, PlutusData.integer(x), PlutusData.integer(y)));
    }

    static PlutusData ints(long... values) {
        return PlutusData.list(Arrays.stream(values).mapToObj(PlutusData::integer).toArray(PlutusData[]::new));
    }

    static PlutusData utf8(String text) {
        return PlutusData.bytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A V3 {@code TxInfo} with the given outputs, fee 2,000,000 and empty everything else. */
    static PlutusData txInfo(PlutusData... outputs) {
        var trueValue = PlutusData.constr(1);
        var lower = PlutusData.constr(0, PlutusData.constr(0), trueValue);
        var upper = PlutusData.constr(0, PlutusData.constr(2), trueValue);
        return PlutusData.constr(0,
                PlutusData.list(),                          // 0: inputs
                PlutusData.list(),                          // 1: referenceInputs
                PlutusData.list(outputs),                   // 2: outputs
                PlutusData.integer(2_000_000),              // 3: fee
                PlutusData.map(),                           // 4: mint
                PlutusData.list(),                          // 5: certificates
                PlutusData.map(),                           // 6: withdrawals
                PlutusData.constr(0, lower, upper),         // 7: validRange
                PlutusData.list(),                          // 8: signatories
                PlutusData.map(),                           // 9: redeemers
                PlutusData.map(),                           // 10: datums
                PlutusData.bytes(new byte[32]),             // 11: id
                PlutusData.map(),                           // 12: votes
                PlutusData.list(),                          // 13: proposalProcedures
                PlutusData.constr(1),                       // 14: currentTreasuryAmount (None)
                PlutusData.constr(1));                      // 15: treasuryDonation (None)
    }

    private static byte[] owner(int fill) {
        var bytes = new byte[28];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    private O15ProjectionSharingFixtures() {}
}
