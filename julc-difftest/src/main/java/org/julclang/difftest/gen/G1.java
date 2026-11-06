package org.julclang.difftest.gen;

import org.julclang.core.PlutusData;
import org.julclang.difftest.HarnessException;
import org.julclang.difftest.TagTable;
import org.julclang.difftest.gen.G1Ast.Component;
import org.julclang.difftest.gen.G1Ast.Expr;
import org.julclang.difftest.gen.G1Ast.Kind;
import org.julclang.difftest.gen.G1Ast.Stmt;
import org.julclang.difftest.gen.G1Ast.Type;
import org.julclang.difftest.gen.G1Ast.Unit;
import org.julclang.difftest.gen.G1Ast.Var;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.julclang.difftest.gen.G1Ast.BIG;
import static org.julclang.difftest.gen.G1Ast.BOOL;
import static org.julclang.difftest.gen.G1Ast.UNREADABLE;

/**
 * The second program grammar (ADR-066 D2, G1 r12): user records and sealed interfaces with record variants,
 * {@code static final} fields, helpers, method-level {@code if} and {@code instanceof} patterns, G0's loops
 * with accumulators of any generated type, record and sealed arguments and results, traces and failures, and
 * {@code switch} expressions with type patterns, {@code default}, {@code yield} blocks, loops in arms and nesting
 * (M3 part 1; guards and record patterns wait for ADR-067). The productions build a typed AST ({@link G1Ast}), which
 * {@link G1Printer} prints.
 * <p>
 * A plan decodes left to right through {@link Choices}, as G0's does, and does not share G0's decoding (D1): the
 * declarations (types, fields, helpers) read its even positions and the entry method its odd ones, so that a plan
 * that the declarations use up still leaves the method its choices. A
 * program is a function of its plan, {@link #VERSION} and its {@link Profile} (G1-I5); its arguments are a function
 * of the program and a second choice sequence, the inputs ({@link Program#arguments}). Constructs whose defect is
 * open are generated only under a profile that turns their {@link Gate} on (D6, G1-I6).
 * <p>
 * The grammar stays inside the subset JuLC accepts (G1-I1) and terminates by construction (G1-I2):
 * <ul>
 *   <li>Loops are G0's: a {@code while} counts up to a bound of at most 3, a for-each visits {@code xs}; at most two
 *       deep; a body that breaks contains no loop, and a for-each that breaks assigns an accumulator.</li>
 *   <li>Variables are assigned only inside loops, and only method-level accumulators declared before the loop
 *       (ADR-048, ADR-062 T20). Helpers are non-recursive and call only earlier helpers; record types are
 *       acyclic, nested at most two deep.</li>
 *   <li>An accessor is called only on a variable or on an accessor chain from one, never on a constructor or a
 *       call (JULC0055). A pattern variable never has a field's name (JULC0059). Field initializers call no method
 *       of the class (JULC0057).</li>
 *   <li>Excluded by construction: {@code ==} on references (T4), {@code byte[]} and {@code String} components (T5,
 *       T17), {@code int} and {@code long} arithmetic other than counters (T6), division and {@code mod} (T11),
 *       {@code Optional}, {@code JulcMap}, {@code @Param} fields and record members (#282).</li>
 * </ul>
 */
public final class G1 {

    /**
     * The decoding's version, saved with every case (ADR-066 G1-I5). Change it whenever a plan, an input sequence or
     * a profile would decode to something different.
     */
    public static final String VERSION = "G1 r12";

    /**
     * The switches of a generation profile (ADR-066 D6), each gating a construct whose defect is open; once the fix is
     * below, the gate is on in {@link Profile#DEFAULT} (D6, "After a fix").
     */
    public enum Gate {
        /** {@code when} guards (#275); M3. */
        GUARDS,
        /** Record patterns (#276); M3. */
        RECORD_PATTERNS,
        /** {@code equals} between distinct record types (#277); M3. */
        CROSS_TYPE_EQUALS,
        /** A record implementing two sealed interfaces at different positions (#279); M3. */
        SHARED_VARIANT,
        /**
         * Reassigning a {@code switch} case-pattern binding inside a loop in its arm (#162); historical: the fix
         * rejects it, so a run under this gate on the fixed compiler ends in that rejection.
         */
        ASSIGN_PATTERN_BINDING,
        /** Zero-parameter helpers (#280). */
        ZERO_ARG_HELPERS,
        /**
         * A local in an {@code if} branch inside a loop body shadowing a field of another type (#209). Fixed by the
         * loop-break continuation fix, so on in the default profile since G1 r9.
         */
        SHADOW_IN_LOOP_BRANCH,
        /**
         * Loop bodies with {@code break} and conditional updates (#254, #255). Both fixed by the loop-break continuation
         * fix, so on in the default profile since G1 r9.
         */
        LOOP_BREAK_SHAPES,
        /**
         * A trace in an operand of {@code compareTo}, directly or through a helper (#146). {@code signum} lowers its
         * receiver the same way; G1 emits none, and probes found no other emitted operation that does.
         */
        TRACE_IN_COMPARISON,
        /**
         * A {@code default} arm block's missing scope (#291), whether the arm runs or not. Its two symptoms: a local
         * declared directly in the block with a field's name keeps its type after the switch; and, in a switch inside
         * an argument of a method call, a local declared in the block after a loop (directly, or by a switch in the
         * rest of the block) and read later makes JuLC reject the program. Loop-body locals in the block are scoped
         * and stay ungated. Fixed by ADR-067 M1 round 3 (the block is scoped), so on in the default profile since G1 r8.
         */
        DEFAULT_BLOCK_SCOPE,
        /**
         * ADR-069 M1 (#204), since G1 r10: a bare type test {@code x instanceof V} wherever a condition is generated,
         * and in the body of a loop with accumulators {@code if (x instanceof V v) { … }}, its whole condition the
         * test (sometimes in extra parentheses), with reads of {@code v}'s components, an {@code else}, an
         * {@code else if} chain of pattern tests, a nested pattern test or a {@code break}; {@code x} a variable or
         * component of a sealed or variant type, or a call returning one. The variable is never assigned and never
         * read under {@code &&}, {@code ||}, {@code !} or {@code ?:} (M2) or after an early exit (M3). Off in the
         * default profile until ADR-069 M3; on there since G1 r12.
         */
        PATTERN_LOOPS,
        /**
         * ADR-069 M2 (#204), since G1 r11: conditions over {@code &&}, {@code ||}, {@code !}, parentheses, boolean
         * {@code ?:} and {@code xs.any}/{@code xs.all} lambdas that mix pattern tests, bare tests and comparisons, with
         * reads of each pattern variable exactly where JLS 6.3.1 puts it in scope: in the condition, in the branches of
         * an {@code if} (method level and loop bodies), in the arms of a value {@code ?:}, and in a {@code while}
         * condition (read there only). Wherever a condition is generated (boolean locals, returns, arguments, tests),
         * and never a variable read after its statement (M3). On in the default profile since G1 r12 (ADR-069 M3).
         */
        PATTERN_CONDITIONS,
        /**
         * ADR-069 M3 (#204), since G1 r12: guards that introduce a pattern variable into the rest of their block (JLS
         * 6.3.2.2), {@code if (!(x instanceof V v)) { exit }}, with {@code &&}/{@code ||} and the mirrored
         * {@code if (x instanceof V v && c) { … } else { exit }}, whose exiting branch ends every path in {@code return}
         * (method bodies, lambda blocks), {@code yield} (switch-arm blocks) or {@code break} (loop bodies), sometimes
         * through an inner {@code if}, followed by reads of the variable; two in a row under the steer. On in the
         * default profile since G1 r12.
         */
        PATTERN_EXIT,
        /**
         * ADR-069 verification 6's javac-filtered mode: reads of pattern variables outside G1's model of JLS 6.3 (after
         * their statement, after a loop, in the {@code else} of {@code !(x instanceof V v) || true}); javac decides
         * which compile, and those must agree or be rejected with a code ({@code Adr069JavacFilteredPropertyTest}).
         * Never in the default profile: many of its programs are not Java.
         */
        PATTERN_SCOPE_PROBES;

        /** The switches G1 defines but does not implement yet: they wait for ADR-067 (M3, part 2). */
        public static final Set<Gate> INERT =
                Collections.unmodifiableSet(EnumSet.of(GUARDS, RECORD_PATTERNS, CROSS_TYPE_EQUALS, SHARED_VARIANT));
    }

    /**
     * Rediscovery emphasis (ADR-066 D10.1; review of M2, F65): a historical profile turns its gate on and also steers
     * generation towards the defect's shape, so that the PR budget finds it. A gate alone only allows its construct
     * and keeps the default distribution, so that turning a gate on in the default profile after a fix (D6) does not
     * narrow the default campaign. Each steer requires its gate.
     */
    public enum Steer {
        /** #209's own shape: two records with the same components in other orders, more loops, no break. */
        ISSUE_209(Gate.SHADOW_IN_LOOP_BRANCH),
        /** Half of the helpers without parameters, most of their bodies traced. */
        ISSUE_280(Gate.ZERO_ARG_HELPERS),
        /** A third of the comparisons trace their left operand. */
        ISSUE_146(Gate.TRACE_IN_COMPARISON),
        /** Every arm loop that can assigns the case-pattern binding. */
        ISSUE_162(Gate.ASSIGN_PATTERN_BINDING),
        /**
         * #291's shape: #209's mirrored records and fields, a family, a {@code default} arm in every switch over more
         * than one variant, whose block first declares a local named like a field, and a result read from that field.
         */
        ISSUE_291(Gate.DEFAULT_BLOCK_SCOPE),
        /**
         * #291's rejection after a loop: a family, a {@code default} arm in every switch over more than one variant,
         * half of the method-call arguments a switch, and a default block there of a loop followed by a declaration or
         * by a {@code while} loop, whose counter is declared after the first loop.
         */
        ISSUE_291_LOOPS(Gate.DEFAULT_BLOCK_SCOPE),
        /**
         * ADR-069's shapes (#204): a family always, more loops, half of the loop bodies with {@code break}, two in
         * three loop-body statements a pattern {@code if}, a quarter of the conditions a bare type test, tested values
         * mostly of a sealed interface's type (both outcomes) or a pattern variable's component, a third of them calls
         * when one is possible, and a variant compared with its own type only (no JULC0078 by design); with
         * PATTERN_CONDITIONS on, a third of the conditions, ternaries and while loops and half of the conditional updates
         * are pattern conditions, three levels deep.
         */
        ISSUE_204(Gate.PATTERN_LOOPS);

        final Gate gate;

        Steer(Gate gate) {
            this.gate = gate;
        }

        String label() {
            return name().substring("ISSUE_".length());
        }
    }

    /**
     * A generation profile: {@link #VERSION}, the value of every {@link Gate} (ADR-066 D6) and its {@link Steer}s. Its
     * text, such as {@code G1 r9 -GUARDS … +ZERO_ARG_HELPERS … steer=280}, is printed in every record and replayed.
     */
    public record Profile(Set<Gate> on, Set<Steer> steering) {

        /**
         * The campaigns' profile: every gate off whose defect is open, and on for each defect fixed below (ADR-066 D6,
         * "After a fix"): {@link Gate#DEFAULT_BLOCK_SCOPE} since #291's fix (G1 r8), and
         * {@link Gate#SHADOW_IN_LOOP_BRANCH} and {@link Gate#LOOP_BREAK_SHAPES} since the loop-break continuation fix
         * (#209, #254, #255; G1 r9); and ADR-069's {@link Gate#PATTERN_LOOPS}, {@link Gate#PATTERN_CONDITIONS} and
         * {@link Gate#PATTERN_EXIT} since its M3 (G1 r12).
         */
        public static final Profile DEFAULT = new Profile(EnumSet.of(Gate.SHADOW_IN_LOOP_BRANCH, Gate.LOOP_BREAK_SHAPES,
                Gate.DEFAULT_BLOCK_SCOPE, Gate.PATTERN_LOOPS, Gate.PATTERN_CONDITIONS, Gate.PATTERN_EXIT));
        /** The historical profile that rediscovers #209 (ADR-066 D10.1). */
        public static final Profile REDISCOVER_209 = DEFAULT.with(Gate.SHADOW_IN_LOOP_BRANCH).steered(Steer.ISSUE_209);
        /** The historical profile that rediscovers #280 (ADR-066 D10.1). */
        public static final Profile REDISCOVER_280 = DEFAULT.with(Gate.ZERO_ARG_HELPERS).steered(Steer.ISSUE_280);
        /** The historical profile that rediscovers #146's double evaluation of a traced operand (ADR-066 D10.1). */
        public static final Profile REDISCOVER_146 = DEFAULT.with(Gate.TRACE_IN_COMPARISON).steered(Steer.ISSUE_146);
        /**
         * The historical profile of #162 (ADR-066 D6): it generates the case-pattern reassignment that the fix rejects,
         * so on the fixed compiler its cases end in that rejection ({@code G1MustRejectTest}).
         */
        public static final Profile REDISCOVER_162 = DEFAULT.with(Gate.ASSIGN_PATTERN_BINDING).steered(Steer.ISSUE_162);
        /** The historical profile that rediscovers #291's misread after a {@code default} arm block (ADR-066 D10.1). */
        public static final Profile REDISCOVER_291 = DEFAULT.with(Gate.DEFAULT_BLOCK_SCOPE).steered(Steer.ISSUE_291);
        /** The historical profile that rediscovers #291's rejection of loops in a default arm block (ADR-066 D10.1). */
        public static final Profile REDISCOVER_291_LOOPS = DEFAULT.with(Gate.DEFAULT_BLOCK_SCOPE).steered(Steer.ISSUE_291_LOOPS);
        /**
         * ADR-069's campaign (#204): the default profile, whose {@link Gate#PATTERN_LOOPS} (M1),
         * {@link Gate#PATTERN_CONDITIONS} (M2) and {@link Gate#PATTERN_EXIT} (M3) are on, steered to their shapes; every program must agree with the JVM
         * ({@code Adr069PatternFlowPropertyTest}, {@code Adr069PatternFlowFuzzTest}).
         */
        public static final Profile PATTERN_FLOW_204 = DEFAULT.steered(Steer.ISSUE_204);
        /**
         * ADR-069 verification 6's javac-filtered campaign: {@link #PATTERN_FLOW_204} with
         * {@link Gate#PATTERN_SCOPE_PROBES} ({@code Adr069JavacFilteredPropertyTest}).
         */
        public static final Profile JAVAC_FILTERED_204 = PATTERN_FLOW_204.with(Gate.PATTERN_SCOPE_PROBES);

        public Profile {
            var gates = EnumSet.noneOf(Gate.class);
            gates.addAll(on);
            on = Collections.unmodifiableSet(gates);
            var steers = EnumSet.noneOf(Steer.class);
            steers.addAll(steering);
            for (Steer steer : steers) {
                if (!gates.contains(steer.gate)) throw new HarnessException("steer=" + steer.label() + " needs +" + steer.gate);
            }
            steering = Collections.unmodifiableSet(steers);
        }

        /** A profile without steering. */
        public Profile(Set<Gate> on) {
            this(on, EnumSet.noneOf(Steer.class));
        }

        public boolean has(Gate gate) {
            return on.contains(gate);
        }

        public boolean steers(Steer steer) {
            return steering.contains(steer);
        }

        public Profile with(Gate gate) {
            var gates = EnumSet.noneOf(Gate.class);
            gates.addAll(on);
            gates.add(gate);
            return new Profile(gates, steering);
        }

        public Profile steered(Steer steer) {
            var steers = EnumSet.noneOf(Steer.class);
            steers.addAll(steering);
            steers.add(steer);
            return new Profile(on, steers);
        }

        /**
         * {@link #VERSION}, then {@code +NAME} or {@code -NAME} for every gate in declaration order, then
         * {@code steer=N} for every steer.
         */
        public String text() {
            var text = new StringBuilder(VERSION);
            for (Gate gate : Gate.values()) text.append(' ').append(has(gate) ? '+' : '-').append(gate.name());
            for (Steer steer : steering) text.append(" steer=").append(steer.label());
            return text.toString();
        }

        /** The profile of a record's {@code profile:} line; it must name this version and every gate once. */
        public static Profile parse(String text) {
            if (!text.startsWith(VERSION + " ")) throw new HarnessException("Not a " + VERSION + " profile: " + text);
            var on = EnumSet.noneOf(Gate.class);
            var seen = EnumSet.noneOf(Gate.class);
            var steers = EnumSet.noneOf(Steer.class);
            for (String token : text.substring(VERSION.length()).trim().split("\\s+")) {
                if (token.startsWith("steer=")) {
                    try {
                        if (!steers.add(Steer.valueOf("ISSUE_" + token.substring("steer=".length())))) {
                            throw new HarnessException("Steer " + token + " twice in " + text);
                        }
                    } catch (IllegalArgumentException e) {
                        throw new HarnessException("Unknown steer " + token + " in " + text);
                    }
                    continue;
                }
                if (token.length() < 2 || token.charAt(0) != '+' && token.charAt(0) != '-') {
                    throw new HarnessException("Not a gate: " + token + " in " + text);
                }
                Gate gate;
                try {
                    gate = Gate.valueOf(token.substring(1));
                } catch (IllegalArgumentException e) {
                    throw new HarnessException("Unknown gate " + token + " in " + text);
                }
                if (!seen.add(gate)) throw new HarnessException("Gate " + gate + " twice in " + text);
                if (token.charAt(0) == '+') on.add(gate);
            }
            if (!seen.equals(EnumSet.allOf(Gate.class))) throw new HarnessException("A profile names every gate: " + text);
            return new Profile(on, steers);
        }

        @Override
        public String toString() {
            return text();
        }
    }

    /** Interactions a program contains, for coverage (ADR-066 D8); syntactic, as G0's. */
    public enum Feature {
        RECORD, SEALED, TWO_FAMILIES, ZERO_COMPONENTS, NESTED_RECORD, SEALED_COMPONENT, RECORD_ARG, SEALED_ARG,
        RECORD_RESULT, SEALED_RESULT, COMPONENT_ACCESS, CHAINED_ACCESS, STATIC_FIELD, FIELD_FROM_FIELD,
        FIELD_SHADOWED, RECORD_EQUALS, RECORD_ACCUMULATOR, INSTANCEOF_PATTERN, METHOD_LEVEL_IF, HELPER_CALL, TRACE,
        FAILURE,
        /** A parameter, or the result, of a variant type with a nonzero tag: the variant typed as itself. */
        VARIANT_ARG, VARIANT_RESULT,
        /** An accumulator of a variant type, assigned in a loop. */
        VARIANT_ACCUMULATOR,
        /** A record component typed as a variant with a nonzero tag. */
        VARIANT_COMPONENT,
        /** A call of a helper that returns {@code boolean}. */
        BOOLEAN_HELPER_CALL,
        /** A {@code switch} expression over a sealed value (ADR-066 D8). */
        SWITCH,
        /** A {@code switch} in an arm of another. */
        NESTED_SWITCH,
        /** An arm that is a block ending in {@code yield}. */
        YIELD_BLOCK,
        /** A loop in an arm, over an accumulator the arm owns. */
        LOOP_IN_ARM,
        /** A {@code switch} in a loop body. */
        SWITCH_IN_LOOP,
        /** A type-pattern arm, {@code case V q ->}. */
        TYPE_PATTERN,
        /** A {@code default} arm. */
        DEFAULT_ARM,
        /** An arm accumulator initialized from the case-pattern binding and assigned in the arm: #162's positive form. */
        BINDING_ACCUMULATOR,
        /** A family whose {@code permits} order differs from its variants' declaration order and name order. */
        PERMITS_PERMUTED,
        /** {@code equals} against a value of a sealed family's type. */
        FAMILY_EQUALS,
        /** A local declared in a loop body with a field's name; too rare for a PR quota, so {@code G1GatesTest} checks it. */
        LOOP_LOCAL_SHADOWS_FIELD,
        /** Statistics only. */
        LOOP, BREAK,
        /** Gated constructs (ADR-066 D6): a program under the default profile never contains one. */
        SHADOW_IN_LOOP_BRANCH, ZERO_ARG_HELPER, LOOP_BREAK_SHAPE, TRACE_IN_COMPARISON, ASSIGN_PATTERN_BINDING,
        DEFAULT_BLOCK_SHADOW, DEFAULT_BLOCK_AFTER_LOOP,
        /** A bare type test {@code x instanceof V} (PATTERN_LOOPS, ADR-069 D1). */
        BARE_TYPE_TEST,
        /** {@code if (x instanceof V v)} in the body of a loop with accumulators (PATTERN_LOOPS, ADR-069 M1). */
        LOOP_PATTERN_IF,
        /** An {@code else if} chain of pattern tests in a loop body (PATTERN_LOOPS). */
        LOOP_PATTERN_ELSE_IF,
        /** A pattern test that is not a whole {@code if} condition (PATTERN_CONDITIONS, ADR-069 D3). */
        PATTERN_CONDITION,
        /** A pattern test in a {@code while} condition (PATTERN_CONDITIONS). */
        PATTERN_IN_WHILE_CONDITION,
        /** A pattern test in a lambda body (PATTERN_CONDITIONS). */
        PATTERN_IN_LAMBDA,
        /** A guard whose pattern variable is read after it (PATTERN_EXIT, ADR-069 D4). */
        EXIT_GUARD,
        /** A guard in a loop body, whose exiting branch breaks (PATTERN_EXIT). */
        EXIT_BREAK,
        /** A guard in a switch-arm block, whose exiting branch yields (PATTERN_EXIT). */
        EXIT_YIELD,
        /** A guard in a lambda block, whose exiting branch returns (PATTERN_EXIT). */
        EXIT_LAMBDA,
        /** A read of a pattern variable declared earlier, wherever it is (PATTERN_SCOPE_PROBES). */
        PROBE_STALE_READ,
        /** {@code if (!(x instanceof V v) || true) { return …; } else { … v … }} (PATTERN_SCOPE_PROBES). */
        PROBE_CONSTANT_ELSE;

        /**
         * The features with a quota: D8's list without the features of the gated constructs (guards, record patterns,
         * cross-type {@code equals}), and the sub-features that keep a shape from hiding behind a sibling's quota
         * (review of M2, F63, F70, F71).
         */
        public static final Set<Feature> QUOTA = Collections.unmodifiableSet(EnumSet.range(RECORD, FAMILY_EQUALS));
        /** The marker of each implemented gate's construct; a gate on in the default profile allows its marker there. */
        public static final Set<Feature> GATED =
                Collections.unmodifiableSet(EnumSet.of(SHADOW_IN_LOOP_BRANCH, ZERO_ARG_HELPER, LOOP_BREAK_SHAPE,
                        TRACE_IN_COMPARISON, ASSIGN_PATTERN_BINDING, DEFAULT_BLOCK_SHADOW,
                        DEFAULT_BLOCK_AFTER_LOOP, BARE_TYPE_TEST, LOOP_PATTERN_IF, LOOP_PATTERN_ELSE_IF, PATTERN_CONDITION,
                        PATTERN_IN_WHILE_CONDITION, PATTERN_IN_LAMBDA, EXIT_GUARD, EXIT_BREAK, EXIT_YIELD, EXIT_LAMBDA,
                        PROBE_STALE_READ, PROBE_CONSTANT_ELSE));
    }

    /** A decoded program: its source, its tag table, its features, its profile and its entry parameters. */
    public static final class Program {
        private final String source;
        private final TagTable tags;
        private final Set<Feature> features;
        private final Profile profile;
        private final List<Type> parameters;

        private Program(String source, TagTable tags, Set<Feature> features, Profile profile, List<Type> parameters) {
            this.source = source;
            this.tags = tags;
            this.features = Collections.unmodifiableSet(EnumSet.copyOf(features.isEmpty()
                    ? EnumSet.noneOf(Feature.class) : features));
            this.profile = profile;
            this.parameters = List.copyOf(parameters);
        }

        public String source() {
            return source;
        }

        public TagTable tags() {
            return tags;
        }

        public Set<Feature> features() {
            return features;
        }

        public Profile profile() {
            return profile;
        }

        /**
         * The arguments of {@code m(a, b, xs, …)} decoded from an input sequence (ADR-066 D4): {@code a}, {@code b}
         * and the elements of {@code xs} from −6..6 as in G0, up to four elements, then one value of each record or
         * sealed parameter, its variant drawn uniformly. Each value is Data by D3's rule with this program's tags.
         */
        public List<PlutusData> arguments(List<Integer> inputs) {
            var choices = new Choices(inputs);
            var args = new ArrayList<PlutusData>();
            args.add(integer(choices));
            args.add(integer(choices));
            var items = new ArrayList<PlutusData>();
            for (int n = choices.below(5); n > 0; n--) items.add(integer(choices));
            args.add(new PlutusData.ListData(items));
            for (Type type : parameters) args.add(value(type, choices));
            return args;
        }

        private static PlutusData integer(Choices choices) {
            return new PlutusData.IntData(BigInteger.valueOf(choices.below(13) - 6));
        }

        private static PlutusData value(Type type, Choices choices) {
            return switch (type.kind) {
                case BIG -> integer(choices);
                case BOOL -> new PlutusData.ConstrData(choices.below(2), List.of());
                case SEALED -> value(type.variants.get(choices.below(type.variants.size())), choices);
                case RECORD -> {
                    var fields = new ArrayList<PlutusData>();
                    for (Component component : type.components) fields.add(value(component.type(), choices));
                    yield new PlutusData.ConstrData(type.tag(), fields);
                }
            };
        }

        @Override
        public String toString() {
            return source;
        }
    }

    /** The program of a plan under a profile, in class {@code Probe}. */
    public static Program decode(List<Integer> plan, Profile profile) {
        return decode(plan, profile, "Probe");
    }

    /** The program of a plan under a profile, in the given class; the tags name that class's member types. */
    public static Program decode(List<Integer> plan, Profile profile, String className) {
        var generator = new G1(plan, profile, className);
        Unit unit = generator.program();
        return new Program(G1Printer.print(unit), generator.tags(), generator.features, profile, generator.entryParameters);
    }

    // ---------------------------------------------------------------- bindings

    /** A generated helper: its name, parameters and return type, and whether its body traces, directly or not. */
    private record Helper(String name, List<Var> parameters, Type result, boolean traces) {
    }

    /**
     * Where an expression is generated: its scope, the helpers it may call, whether it is evaluated only
     * sometimes (a ternary branch, the right operand of {@code &&} or {@code ||}), whether it is a field
     * initializer (no calls of the class's methods), a field whose reads it prefers (the #209 composition), and
     * whether it may trace (not in a {@code compareTo} operand unless TRACE_IN_COMPARISON is on).
     */
    private record Ctx(Scope scope, List<Helper> helpers, boolean conditional, boolean constant, String preferred,
                       boolean traceFree) {
        Ctx conditionally() {
            return new Ctx(scope, helpers, true, constant, preferred, traceFree);
        }

        Ctx preferring(String field) {
            return new Ctx(scope, helpers, conditional, constant, field, traceFree);
        }

        Ctx withoutTraces() {
            return new Ctx(scope, helpers, conditional, constant, preferred, true);
        }

        List<Helper> callable(Type type) {
            return helpers.stream().filter(h -> h.result().fits(type) && !(traceFree && h.traces())).toList();
        }
    }

    /** What code at one point may read and assign. Locals hide fields of the same name. */
    private record Scope(List<Var> locals, int loopDepth) {
        Scope with(Var var) {
            var vars = new ArrayList<>(locals);
            vars.add(var);
            return new Scope(vars, loopDepth);
        }

        Scope inLoop() {
            return new Scope(locals, loopDepth + 1);
        }

        List<Var> assignable() {
            return locals.stream().filter(Var::assignable).toList();
        }
    }

    // ---------------------------------------------------------------- the generator

    private static final List<String> POOL = List.of("v", "w", "x", "f", "p");
    private static final int MAX_LOOP_DEPTH = 2;
    private static final int EXPRESSION_DEPTH = 2;
    private static final String[] COMPARISONS = {"<", "<=", ">", ">=", "==", "!="};

    /** The declarations read the plan's even positions and the entry method its odd ones, so both get choices. */
    private final Choices declarationChoices;
    private final Choices methodChoices;
    private Choices choices;
    private final Profile profile;
    private final String className;
    private final Set<Feature> features = EnumSet.noneOf(Feature.class);
    private final List<Type> types = new ArrayList<>();
    private final List<Var> fields = new ArrayList<>();
    private final List<G1Ast.Field> fieldDeclarations = new ArrayList<>();
    private final List<Helper> helpers = new ArrayList<>();
    private final List<G1Ast.Helper> helperDeclarations = new ArrayList<>();
    private final List<Type> entryParameters = new ArrayList<>();
    /** Every variable name declared in the method being generated, so that no local redeclares another. */
    private final Set<String> methodNames = new HashSet<>();
    private int labels;
    private int counters;

    private G1(List<Integer> plan, Profile profile, String className) {
        var even = new ArrayList<Integer>();
        var odd = new ArrayList<Integer>();
        for (int i = 0; i < plan.size(); i++) (i % 2 == 0 ? even : odd).add(plan.get(i));
        this.declarationChoices = new Choices(even);
        this.methodChoices = new Choices(odd);
        this.choices = declarationChoices;
        this.profile = profile;
        this.className = className;
    }

    private Unit program() {
        declareTypes();
        declareFields();
        declareHelpers();
        var declarations = declarationOrder();
        choices = methodChoices;
        return new Unit(className, declarations, fieldDeclarations, helperDeclarations, method());
    }

    private TagTable tags() {
        var entries = new ArrayList<TagTable.Entry>();
        for (Type type : types) {
            if (type.kind != Kind.SEALED) continue;
            for (Type variant : type.variants) {
                entries.add(new TagTable.Entry(className + "$" + variant.name, className + "$" + type.name, variant.tag()));
            }
        }
        return new TagTable(entries);
    }

    /** Zero to two standalone records and zero to two sealed families of one to three variants, acyclic. */
    private void declareTypes() {
        int records = 0, families = 0;
        if (profile.steers(Steer.ISSUE_209) || profile.steers(Steer.ISSUE_291)) {
            // #209's own shape: two records with the same components in different orders (see mirror).
            Type first = new Type(Kind.RECORD, "R0");
            var names = new ArrayList<>(POOL);
            // A third of the time integers only, so that a read through the wrong layout returns another integer: the
            // silent form; else sometimes a boolean, so that it fails.
            boolean integers = choices.oneIn(3);
            for (int i = 2 + choices.below(2); i > 0; i--) {
                first.components.add(new Component(names.remove(choices.below(names.size())),
                        !integers && choices.oneIn(3) ? BOOL : BIG));
            }
            first.depth = 1;
            Type second = new Type(Kind.RECORD, "R1");
            mirror(second, first);
            types.addAll(List.of(first, second));
            records = 2;
            features.add(Feature.RECORD);
        }
        for (int n = choices.below(5); n > 0; n--) {
            boolean family = choices.below(5) < 2;
            if (family && families < 2) {
                declareFamily(families++, 1 + choices.below(3));
            } else if (records < 2) {
                Type record = new Type(Kind.RECORD, "R" + records);
                components(record);
                types.add(record);
                records++;
                features.add(Feature.RECORD);
            }
        }
        // Steered to #291, a family of two or three variants to switch on with a default arm; steered to #204, to test.
        if ((profile.steers(Steer.ISSUE_291) || profile.steers(Steer.ISSUE_291_LOOPS) || profile.steers(Steer.ISSUE_204))
                && families == 0) {
            declareFamily(families++, 2 + choices.below(2));
        }
        if (families == 2) features.add(Feature.TWO_FAMILIES);
    }

    /** A sealed family {@code S<index>} of {@code variants} records, its {@code permits} order permuted. */
    private void declareFamily(int index, int variants) {
        Type sealed = new Type(Kind.SEALED, "S" + index);
        var created = new ArrayList<Type>();
        for (int i = 0; i < variants; i++) {
            Type variant = new Type(Kind.RECORD, (char) ('A' + i) + String.valueOf(index));
            variant.family = sealed;
            components(variant);
            created.add(variant);
        }
        // The permits order, and so the tags, is a permutation of the creation (and name) order, never that
        // order itself, so that tags follow neither the names nor, mostly, the declarations (ADR-066 D3).
        var permits = permuted(created);
        if (permits.size() > 1 && permits.equals(created)) permits.add(permits.removeFirst());
        sealed.variants.addAll(permits);
        sealed.depth = created.stream().mapToInt(t -> t.depth).max().orElse(1);
        types.add(sealed);
        features.add(Feature.SEALED);
    }

    /**
     * Zero to three components of {@code BigInteger}, {@code boolean} or an earlier type, names from the pool. Earlier
     * types include the variants of families already declared, so types stay acyclic; a quarter of the components
     * are a nonzero-tag variant when one fits the depth limit (VARIANT_COMPONENT, review of M2, F80).
     */
    private void components(Type record) {
        int count = Math.min(3, choices.below(6));
        if (count == 0) features.add(Feature.ZERO_COMPONENTS);
        var names = new ArrayList<>(POOL);
        int depth = 1;
        // A standalone record often holds an earlier standalone record with components: chained reads need one.
        var inner = types.stream().filter(t -> t.kind == Kind.RECORD && t.depth < 2 && !t.components.isEmpty()).toList();
        boolean nest = record.family == null && !inner.isEmpty() && count > 0 && choices.oneIn(2);
        var tagged = valueTypes().stream().filter(t -> t.family != null && t.tag() != 0 && t.depth < 2).toList();
        for (int i = 0; i < count; i++) {
            var candidates = new ArrayList<>(List.of(BIG, BIG, BOOL));
            if (i == 0 && nest) candidates = new ArrayList<>(inner);
            for (Type type : i == 0 && nest ? List.<Type>of() : types) {
                // Each variant by its own depth: a shallow variant of a family with a deep sibling fits (F85).
                if (type.kind == Kind.SEALED) {
                    for (Type variant : type.variants) if (variant.depth < 2) candidates.add(variant);
                }
                if (type.depth >= 2) continue;
                candidates.addAll(List.of(type, type));
                // A standalone record inside a record gives chained reads (CHAINED_ACCESS).
                if (type.kind == Kind.RECORD) candidates.addAll(List.of(type, type));
            }
            Type type = candidates.get(choices.below(candidates.size()));
            if (!(i == 0 && nest) && !tagged.isEmpty() && choices.oneIn(4)) type = tagged.get(choices.below(tagged.size()));
            if (type.family != null && type.tag() != 0) features.add(Feature.VARIANT_COMPONENT);
            if (type.kind == Kind.RECORD) features.add(Feature.NESTED_RECORD);
            if (type.kind == Kind.SEALED) features.add(Feature.SEALED_COMPONENT);
            if (type.isValue()) depth = Math.max(depth, type.depth + 1);
            String name = names.remove(choices.below(names.size()));
            record.components.add(new Component(name, type));
        }
        record.depth = depth;
    }

    /**
     * Steered to #209 (ISSUE_209), the issue's own shape: a second record with the first one's components in another
     * order, so that a component read through the wrong type reads a component of the same name at another index,
     * and with components of the same type, the wrong value silently.
     */
    private void mirror(Type record, Type first) {
        var order = permuted(first.components);
        if (order.equals(first.components)) order = new ArrayList<>(order.subList(1, order.size()));
        if (order.size() < first.components.size()) order.add(first.components.getFirst());
        record.components.addAll(order);
        record.depth = first.depth;
    }

    private <T> List<T> permuted(List<T> items) {
        var result = new ArrayList<>(items);
        for (int i = result.size() - 1; i > 0; i--) Collections.swap(result, i, choices.below(i + 1));
        return result;
    }

    /**
     * The order the types are declared in, chosen independently of the {@code permits} orders: a family's interface
     * and its variants, and the standalone records, permuted together.
     */
    private List<Type> declarationOrder() {
        var declarations = permuted(valueTypes());
        for (Type type : types) {
            var byName = type.variants.stream().sorted(Comparator.comparing(t -> t.name)).toList();
            if (type.kind == Kind.SEALED && !type.variants.equals(byName)
                    && !declarations.stream().filter(type.variants::contains).toList().equals(type.variants)) {
                features.add(Feature.PERMITS_PERMUTED);
            }
        }
        return declarations;
    }

    /** The declared types a declaration may have: standalone records, sealed families and their variants. */
    private List<Type> valueTypes() {
        var values = new ArrayList<Type>();
        for (Type type : types) {
            values.add(type);
            if (type.kind == Kind.SEALED) values.addAll(type.variants);
        }
        return values;
    }

    /** A type for a declaration, a parameter or a result: {@code BigInteger}, {@code boolean} or a declared type. */
    private Type anyType() {
        if (!types.isEmpty() && choices.oneIn(2)) return declaredType();
        return choices.oneIn(3) ? BOOL : BIG;
    }

    /**
     * A declared type: first a kind (standalone record, sealed family, variant) among those declared, then a type of
     * that kind, so that variants, more numerous than families, do not crowd the families out.
     */
    private Type declaredType() {
        var kinds = new ArrayList<List<Type>>();
        for (var kind : List.of(
                valueTypes().stream().filter(t -> t.kind == Kind.RECORD && t.family == null).toList(),
                valueTypes().stream().filter(t -> t.kind == Kind.SEALED).toList(),
                valueTypes().stream().filter(t -> t.family != null).toList())) {
            if (!kind.isEmpty()) kinds.add(kind);
        }
        var kind = kinds.get(choices.below(kinds.size()));
        return kind.get(choices.below(kind.size()));
    }

    /** Zero to two {@code static final} fields, named from the pool, each initialized without calls of the class. */
    private void declareFields() {
        var names = new ArrayList<>(POOL);
        // Steered to #209 or #291, a field of one of the two records to shadow.
        boolean shadowing = profile.steers(Steer.ISSUE_209) || profile.steers(Steer.ISSUE_291);
        for (int n = shadowing ? 1 + choices.below(2) : choices.below(3); n > 0; n--) {
            Type type = shadowing ? types.get(choices.below(2)) : !types.isEmpty() && choices.oneIn(2) ? declaredType() : BIG;
            String name = names.remove(choices.below(names.size()));
            var ctx = new Ctx(new Scope(List.of(), 0), List.of(), false, true, null, false);
            int before = fieldReads;
            Expr init = expression(type, ctx, EXPRESSION_DEPTH);
            if (fieldReads > before) features.add(Feature.FIELD_FROM_FIELD);
            fieldDeclarations.add(new G1Ast.Field(type, name, init));
            fields.add(new Var(name, type, false, false, true, true));
            features.add(Feature.STATIC_FIELD);
        }
    }

    /** The accumulator #209's composition last assigned from a field read, which a result steered to #209 prefers. */
    private String shadowRead;

    /** The field a default arm block last shadowed, which a result steered to #291 reads. */
    private String defaultShadowed;

    /** How many field reads expressions have generated, to tell a field initialized from another. */
    private int fieldReads;

    /**
     * Zero to two helpers of one to three parameters, each calling only earlier ones. Under ZERO_ARG_HELPERS a quarter
     * of them have none, and half of them steered to #280.
     */
    private void declareHelpers() {
        var names = new ArrayList<>(POOL);
        for (int n = choices.below(3); n > 0; n--) {
            String name = names.remove(choices.below(names.size()));
            int arity = profile.steers(Steer.ISSUE_280) ? (choices.oneIn(2) ? 0 : 1 + choices.below(3))
                    : profile.has(Gate.ZERO_ARG_HELPERS) ? choices.below(4) : 1 + choices.below(3);
            var parameters = new ArrayList<Var>();
            var parameterNames = new ArrayList<>(POOL);
            for (int i = 0; i < arity; i++) {
                parameters.add(new Var(parameterNames.remove(choices.below(parameterNames.size())), anyType(), false, false,
                        true, false));
            }
            // Names declared in this helper's body (switch bindings, arm locals) avoid its parameters; xs is not in scope.
            methodNames.clear();
            patternVariables.clear();
            parameters.forEach(parameter -> methodNames.add(parameter.name()));
            inHelper = true;
            // Boolean helpers are called from conditions (BOOLEAN_HELPER_CALL).
            Type result = !choices.oneIn(3) ? BOOL : anyType();
            var scope = new Scope(parameters, 0);
            var ctx = new Ctx(scope, List.copyOf(helpers), false, false, null, false);
            int traced = traceSites;
            Expr body = expression(result, ctx, EXPRESSION_DEPTH);
            if (arity == 0) {
                features.add(Feature.ZERO_ARG_HELPER);
                // Steered to #280, whose defect shows only when the body does something observable at script start.
                if (profile.steers(Steer.ISSUE_280) && result == BIG && !choices.oneIn(4)) body = trace(body);
            }
            inHelper = false;
            helperDeclarations.add(new G1Ast.Helper(name, parameters, result, body));
            helpers.add(new Helper(name, parameters, result, traceSites > traced));
        }
    }

    // ---------------------------------------------------------------- the entry method

    private G1Ast.Method method() {
        methodNames.clear();
        patternVariables.clear();
        methodNames.addAll(List.of("a", "b", "xs"));
        defaultShadowed = null;
        Type result = !types.isEmpty() && !choices.oneIn(3) ? declaredType() : anyType();
        // A variant typed as itself, with a nonzero tag, as the result a quarter of the time (VARIANT_RESULT).
        var tagged = valueTypes().stream().filter(t -> t.family != null && t.tag() != 0).toList();
        if (!tagged.isEmpty() && choices.oneIn(4)) result = tagged.get(choices.below(tagged.size()));
        if (result.kind == Kind.RECORD && result.family == null) features.add(Feature.RECORD_RESULT);
        if (result.kind == Kind.SEALED) features.add(Feature.SEALED_RESULT);
        if (result.family != null && result.tag() != 0) features.add(Feature.VARIANT_RESULT);
        // Steered to #291, an integer result half of the time: a component read through the wrong layout.
        if (profile.steers(Steer.ISSUE_291) && choices.oneIn(2)) result = BIG;
        var scope = new Scope(new ArrayList<>(List.of(new Var("a", BIG, false, false, true, false),
                new Var("b", BIG, false, false, true, false))), 0);
        var parameters = new ArrayList<Var>();
        var values = new ArrayList<>(valueTypes());
        // Nested types twice as often: their arguments have components to read through.
        for (Type type : valueTypes()) if (type.depth > 1) values.add(type);
        if (!values.isEmpty()) {
            for (int n = choices.below(3); n > 0; n--) {
                Type type = values.get(choices.below(values.size()));
                // A variant typed as itself, with a nonzero tag, a quarter of the time (VARIANT_ARG).
                if (!tagged.isEmpty() && choices.oneIn(4)) type = tagged.get(choices.below(tagged.size()));
                String name = freshName(false);
                if (type.kind == Kind.SEALED) features.add(Feature.SEALED_ARG);
                else if (type.family == null) features.add(Feature.RECORD_ARG);
                else if (type.tag() != 0) features.add(Feature.VARIANT_ARG);
                var parameter = new Var(name, type, false, false, true, false);
                parameters.add(parameter);
                scope = scope.with(parameter);
                entryParameters.add(type);
            }
        }
        var body = new ArrayList<Stmt>();
        var updated = new ArrayList<String>();
        boolean shadowing = profile.steers(Steer.ISSUE_209);
        boolean defaultShadowing = profile.steers(Steer.ISSUE_291);
        for (int n = choices.below(5) + (shadowing || defaultShadowing ? 1 : 0); n > 0; n--) {
            int kind = choices.below(10);
            // Steered to #209, more loops: #209 lives in a loop body.
            if (shadowing && kind != 1 && choices.oneIn(2)) kind = 1;
            // Steered to #291, more switch-valued locals: the read after the switch needs one before it.
            if (defaultShadowing && kind != 8 && choices.oneIn(2)) kind = 8;
            // Steered to #204, more loops: M1's pattern tests live in loop bodies.
            if (profile.steers(Steer.ISSUE_204) && kind > 3 && choices.oneIn(2)) kind = 1;
            // PATTERN_SCOPE_PROBES: a read wherever, or a constant-condition else (javac-filtered).
            if (probes() && choices.oneIn(4)) staleRead(scope, body);
            if (probes() && choices.oneIn(4)) {
                scope = constantElse(scope, body, result);
                continue;
            }
            // Under PATTERN_EXIT an eighth of the statements follow guards whose variables the rest reads; steered,
            // half. A guard adds to the statement, so that the distribution of the others stays (G1GatesTest).
            if (exits(2, 8)) scope = methodGuards(scope, body, result);
            switch (kind) {
                case 0 -> scope = declaration(scope, body, true, null);
                case 1, 2, 3 -> {
                    if (scope.assignable().isEmpty()) scope = accumulator(scope, body);
                    if (scope.assignable().size() < 2 && !choices.oneIn(3)) scope = accumulator(scope, body);
                    loop(scope, body, updated, choices.oneIn(2));
                }
                case 4 -> earlyReturn(scope, body, result);
                case 5, 6 -> scope = instanceOf(scope, body, result);
                case 8, 9 -> scope = switchDeclaration(scope, body);
                default -> scope = declaration(scope, body, false, null);
            }
        }
        // PATTERN_SCOPE_PROBES: before the method's last statement, when the most pattern variables exist.
        if (probes() && !choices.oneIn(3)) staleRead(scope, body);
        if (choices.oneIn(3)) {
            features.add(Feature.METHOD_LEVEL_IF);
            if (conditions(2, 4)) {
                body.add(flowIf(scope, result, true));
            } else {
                Expr test = test(ctx(scope), EXPRESSION_DEPTH);
                var then = branch(scope, result);
                body.add(new G1Ast.If(test, then, branch(scope, result)));
            }
        } else {
            String preferred = profile.steers(Steer.ISSUE_209) ? shadowRead
                    : profile.steers(Steer.ISSUE_291) ? defaultShadowed : null;
            // A switch as the result a third of the time a family is declared.
            Expr value = null;
            // Steered to #291, the result reads a component of the field that a default arm block shadowed.
            if (defaultShadowing && preferred != null) {
                var reading = ctx(scope).preferring(preferred);
                if (paths(result, reading).stream().anyMatch(p -> p.var().name().equals(preferred))) value = access(result, reading);
            }
            if (preferred == null && types.stream().anyMatch(t -> t.kind == Kind.SEALED) && choices.oneIn(3)) {
                scope = selectable(scope, body);
                value = switchExpression(result, ctx(scope), EXPRESSION_DEPTH);
            }
            if (value == null) value = expression(result, ctx(scope).preferring(preferred), EXPRESSION_DEPTH);
            body.add(new G1Ast.Return(value));
        }
        return new G1Ast.Method(result, parameters, body);
    }

    private Ctx ctx(Scope scope) {
        return new Ctx(scope, helpers, false, false, null, false);
    }

    /** {@code if (c) { locals; return e; }} at method level. */
    private void earlyReturn(Scope scope, List<Stmt> into, Type result) {
        features.add(Feature.METHOD_LEVEL_IF);
        if (conditions(2, 4)) {
            into.add(flowIf(scope, result, false));
            return;
        }
        Expr test = test(ctx(scope), EXPRESSION_DEPTH);
        into.add(new G1Ast.If(test, branch(scope, result), null));
    }

    /**
     * {@code if (s instanceof V y) { locals; return e; }}: the pattern is the whole condition, outside loops. Without
     * a sealed variable in scope, one is declared first; the scope after the statement is returned.
     */
    private Scope instanceOf(Scope scope, List<Stmt> into, Type result) {
        var sealed = readable(scope).stream().filter(v -> v.type().kind == Kind.SEALED).toList();
        if (sealed.isEmpty()) {
            var families = types.stream().filter(t -> t.kind == Kind.SEALED).toList();
            if (families.isEmpty()) {
                earlyReturn(scope, into, result);
                return scope;
            }
            Type family = families.get(choices.below(families.size()));
            String name = freshName(false);
            into.add(new G1Ast.Declare(family, name, expression(family, ctx(declaring(scope, name)), EXPRESSION_DEPTH)));
            if (isField(name)) features.add(Feature.FIELD_SHADOWED);
            scope = scope.with(new Var(name, family, false, false, true, false));
            sealed = List.of(scope.locals().getLast());
        }
        Var subject = sealed.get(choices.below(sealed.size()));
        Type variant = subject.type().variants.get(choices.below(subject.type().variants.size()));
        String binding = freshName(true);
        features.add(Feature.INSTANCEOF_PATTERN);
        var body = branch(scope.with(new Var(binding, variant, false, false, true, false)), result);
        into.add(new G1Ast.IfInstance(subject, variant, binding, body));
        return scope;
    }

    /**
     * {@code T x = switch (s) { … };} at method level: a local whose value is a switch, after declaring a value of a
     * family to select on if none is in scope.
     */
    private Scope switchDeclaration(Scope scope, List<Stmt> into) {
        if (types.stream().noneMatch(t -> t.kind == Kind.SEALED)) return declaration(scope, into, false, null);
        scope = selectable(scope, into);
        Type type = anyType();
        String name = freshName(false);
        Expr value = switchExpression(type, ctx(declaring(scope, name)), EXPRESSION_DEPTH);
        if (value == null) value = expression(type, ctx(declaring(scope, name)), EXPRESSION_DEPTH);
        into.add(new G1Ast.Declare(type, name, value));
        if (isField(name)) features.add(Feature.FIELD_SHADOWED);
        return scope.with(new Var(name, type, false, false, true, false));
    }

    /** The scope with a value to switch on: unchanged if it has one, else after declaring a local of a family. */
    private Scope selectable(Scope scope, List<Stmt> into) {
        if (!subjects(ctx(scope)).isEmpty()) return scope;
        var families = types.stream().filter(t -> t.kind == Kind.SEALED).toList();
        Type family = families.get(choices.below(families.size()));
        String name = freshName(false);
        into.add(new G1Ast.Declare(family, name, expression(family, ctx(declaring(scope, name)), EXPRESSION_DEPTH)));
        if (isField(name)) features.add(Feature.FIELD_SHADOWED);
        return scope.with(new Var(name, family, false, false, true, false));
    }

    /** Zero to two locals, then a return. */
    private List<Stmt> branch(Scope scope, Type result) {
        var body = new ArrayList<Stmt>();
        for (int n = choices.below(3); n > 0; n--) scope = declaration(scope, body, false, null);
        body.add(new G1Ast.Return(expression(result, ctx(scope), EXPRESSION_DEPTH)));
        return body;
    }

    /**
     * {@code T name = e;}. A method-level accumulator is assignable by later loops; any other local is final.
     * {@code shadow}, when not null, decides whether the name may be a field's: null lets the plan decide.
     */
    private Scope declaration(Scope scope, List<Stmt> into, boolean accumulator, Boolean shadow) {
        Type type = anyType();
        boolean mayShadow = shadow == null || shadow;
        String name = freshName(!mayShadow);
        into.add(new G1Ast.Declare(type, name, expression(type, ctx(declaring(scope, name)), EXPRESSION_DEPTH)));
        if (isField(name)) features.add(Feature.FIELD_SHADOWED);
        return scope.with(new Var(name, type, false, accumulator, !accumulator, false));
    }

    /**
     * A local in a loop body: two times in three with the name of a field that no local hides and that the method
     * has not used, which agrees on the base (D2, "a local shadowing a field directly in a loop body").
     */
    private Scope loopLocal(Scope scope, List<Stmt> into) {
        var free = fields.stream().filter(f -> !methodNames.contains(f.name()) && readable(scope).contains(f)).toList();
        if (free.isEmpty() || choices.oneIn(3)) return declaration(scope, into, false, null);
        Type type = anyType();
        String name = free.get(choices.below(free.size())).name();
        methodNames.add(name);
        into.add(new G1Ast.Declare(type, name, expression(type, ctx(declaring(scope, name)), EXPRESSION_DEPTH)));
        features.add(Feature.FIELD_SHADOWED);
        return scope.with(new Var(name, type, false, false, true, false));
    }

    /** The scope of a declaration's initializer: the declared name hides a field of that name, unreadable. */
    private static Scope declaring(Scope scope, String name) {
        return scope.with(new Var(name, UNREADABLE, false, false, false, false));
    }

    /** A method-level accumulator for a loop, of a declared type more often than not. */
    private Scope accumulator(Scope scope, List<Stmt> into) {
        Type type = !types.isEmpty() && !choices.oneIn(3) ? declaredType() : anyType();
        // A variant accumulator half of the time (VARIANT_ACCUMULATOR).
        var variants = valueTypes().stream().filter(t -> t.family != null).toList();
        if (!variants.isEmpty() && choices.oneIn(2)) type = variants.get(choices.below(variants.size()));
        String name = freshName(false);
        into.add(new G1Ast.Declare(type, name, expression(type, ctx(declaring(scope, name)), EXPRESSION_DEPTH)));
        if (isField(name)) features.add(Feature.FIELD_SHADOWED);
        return scope.with(new Var(name, type, false, true, false, false));
    }

    /** A name from the pool, or the pool name with a number when it is taken; never a field's if {@code notField}. */
    private String freshName(boolean notField) {
        String base = POOL.get(choices.below(POOL.size()));
        String name = base;
        for (int i = 1; methodNames.contains(name) || notField && isField(name); i++) name = base + i;
        methodNames.add(name);
        return name;
    }

    private boolean isField(String name) {
        return fields.stream().anyMatch(f -> f.name().equals(name));
    }

    // ---------------------------------------------------------------- loops (G0's sub-grammar, D2)

    /** Whether a helper's body is being generated: {@code xs} is m's parameter, so loops there are while loops. */
    private boolean inHelper;

    private void loop(Scope scope, List<Stmt> into, List<String> updated, boolean forEach) {
        features.add(Feature.LOOP);
        forEach = forEach && !inHelper;
        Scope body = scope.inLoop();
        var statements = new ArrayList<Stmt>();
        if (forEach) {
            String element = freshName(false);
            if (isField(element)) features.add(Feature.FIELD_SHADOWED);
            body(body.with(new Var(element, BIG, false, false, true, false)), statements, updated, false);
            into.add(new G1Ast.ForEach(element, statements));
        } else {
            String counter = "i" + counters++;
            methodNames.add(counter);
            int bound = choices.below(4);
            Scope counted = body.with(new Var(counter, BIG, true, false, false, false));
            // Under PATTERN_CONDITIONS a condition after the bound, whose variables are read in it only (M2).
            Expr guard = null;
            if (conditions(3, 6)) {
                whileGuard = true;
                try {
                    guard = flowCondition(ctx(counted), 2).expr();
                } finally {
                    whileGuard = false;
                }
            }
            body(counted, statements, updated, true);
            into.add(new G1Ast.While(counter, bound, guard, statements));
        }
    }

    private void body(Scope scope, List<Stmt> into, List<String> updated, boolean counted) {
        // A body with break contains no loop (reject-nested-loop-in-break-loop). Loop bodies with break and
        // conditional updates are gated (LOOP_BREAK_SHAPES, #254, #255).
        // Steered to #204, half of the bodies, so that pattern ifs meet break often.
        boolean breakAware = (profile.steers(Steer.ISSUE_204) ? choices.oneIn(2) : choices.oneIn(3))
                && !profile.steers(Steer.ISSUE_209);
        boolean conditionalUpdates = !breakAware || profile.has(Gate.LOOP_BREAK_SHAPES);
        // A switch arm in a body with break contains no loop either (reject-nested-loop-in-break-loop).
        if (breakAware) breakAwareBodies++;
        try {
            statements(scope, into, updated, counted, breakAware, conditionalUpdates);
        } finally {
            if (breakAware) breakAwareBodies--;
        }
    }

    private void statements(Scope scope, List<Stmt> into, List<String> updated, boolean counted, boolean breakAware,
                            boolean conditionalUpdates) {
        var assigned = new ArrayList<String>();
        boolean hasBreak = false;
        boolean hasIfUpdate = false;
        for (int n = 1 + choices.below(3); n > 0; n--) {
            int kind = choices.below(7);
            if (kind <= 1 && profile.steers(Steer.ISSUE_209) && choices.oneIn(2)) kind = 2;
            if (kind == 3 && !breakAware) kind = 0;
            if (kind == 2 && !conditionalUpdates) kind = 1;
            if (kind == 5 && (breakAware || scope.loopDepth() >= MAX_LOOP_DEPTH)) kind = 0;
            // Under PATTERN_LOOPS a third of the plain updates are pattern ifs; steered to #204, two in three statements
            // other than a break or a loop.
            if (profile.has(Gate.PATTERN_LOOPS) && conditionalUpdates
                    && (profile.steers(Steer.ISSUE_204) ? kind != 3 && kind != 5 && !choices.oneIn(3)
                    : kind <= 1 && choices.oneIn(3))) {
                kind = 7;
            }
            // Under PATTERN_CONDITIONS a third of the conditional updates read pattern conditions; steered, half of them
            // and of the pattern ifs in bodies without break (whose pattern ifs may break).
            if (profile.has(Gate.PATTERN_CONDITIONS) && conditionalUpdates
                    && (profile.steers(Steer.ISSUE_204) ? (kind == 2 || kind == 7 && !breakAware) && choices.oneIn(2)
                    : kind == 2 && choices.oneIn(3))) {
                kind = 8;
            }
            // Under PATTERN_EXIT a fifth of the statements of a body with break are guards that break; steered, half.
            if (profile.has(Gate.PATTERN_EXIT) && conditionalUpdates && breakAware
                    && choices.oneIn(profile.steers(Steer.ISSUE_204) ? 2 : 5)) {
                kind = 9;
            }
            if (probes() && choices.oneIn(4)) staleRead(scope, into);
            switch (kind) {
                case 9 -> {
                    var introduced = exitGuard(ctx(scope), into, new Exit(Exit.BREAK, BIG), assigned);
                    for (Var var : introduced) scope = scope.with(var);
                    if (!introduced.isEmpty()) {
                        hasBreak = true;
                        hasIfUpdate = true;
                        features.add(Feature.BREAK);
                        // An update that prefers the variable, as the rest of the body would.
                        assignment(scope, into, assigned, introduced.getFirst().name());
                    }
                }
                case 8 -> {
                    hasIfUpdate = true;
                    flowUpdate(scope, into, assigned);
                }
                case 7 -> {
                    hasIfUpdate = true;
                    patternBreak = false;
                    scope = patternUpdate(scope, into, assigned, breakAware);
                    if (patternBreak) {
                        hasBreak = true;
                        features.add(Feature.BREAK);
                    }
                }
                case 0, 1 -> assignment(scope, into, assigned, null);
                case 2 -> {
                    hasIfUpdate = true;
                    ifUpdate(scope, into, assigned);
                }
                case 3 -> {
                    hasBreak = true;
                    features.add(Feature.BREAK);
                    into.add(new G1Ast.BreakIf(test(ctx(scope), EXPRESSION_DEPTH)));
                }
                case 5 -> loop(scope, into, updated, choices.oneIn(2));
                default -> {
                    // A switch-valued local half of the time a family is declared (SWITCH_IN_LOOP).
                    if (types.stream().anyMatch(t -> t.kind == Kind.SEALED) && choices.oneIn(2)) {
                        scope = switchDeclaration(scope, into);
                    } else {
                        scope = loopLocal(scope, into);
                        if (isField(scope.locals().getLast().name())) features.add(Feature.LOOP_LOCAL_SHADOWS_FIELD);
                    }
                }
            }
        }
        // A for-each that breaks must update an earlier variable (reject-break-in-for-each-without-accumulator).
        if (!counted && assigned.isEmpty() && hasBreak) assignment(scope, into, assigned, null);
        if (hasBreak && hasIfUpdate) features.add(Feature.LOOP_BREAK_SHAPE);
        updated.addAll(assigned);
    }

    /**
     * {@code if (c) { [local;] acc = e; } [else { acc2 = e; }]}. Under SHADOW_IN_LOOP_BRANCH the branch local may
     * shadow a field of another type, and the statement after the if reads that field: #209's composition.
     */
    private void ifUpdate(Scope scope, List<Stmt> into, List<String> assigned) {
        Expr test = test(ctx(scope), EXPRESSION_DEPTH);
        var then = new ArrayList<Stmt>();
        Scope branch = scope;
        String shadowed = null;
        // Under the gate a branch local may shadow a field, half of the time it can; steered to #209, every time.
        Var field = profile.has(Gate.SHADOW_IN_LOOP_BRANCH) ? shadowable(scope) : null;
        boolean steered = profile.steers(Steer.ISSUE_209);
        if (steered && field != null || choices.oneIn(3)) {
            if (field != null && (steered || choices.oneIn(2))) {
                Type type = otherType(field.type());
                methodNames.add(field.name());
                then.add(new G1Ast.Declare(type, field.name(), expression(type, ctx(declaring(scope, field.name())), EXPRESSION_DEPTH)));
                branch = scope.with(new Var(field.name(), type, false, false, true, false));
                shadowed = field.name();
                features.add(Feature.SHADOW_IN_LOOP_BRANCH);
                features.add(Feature.FIELD_SHADOWED);
            } else {
                branch = declaration(scope, then, false, false);
            }
        }
        assignment(branch, then, assigned, null);
        List<Stmt> otherwise = null;
        if (choices.oneIn(2)) {
            otherwise = new ArrayList<>();
            assignment(scope, otherwise, assigned, null);
        }
        into.add(new G1Ast.If(test, then, otherwise));
        if (shadowed != null) fieldRead(scope, into, assigned, shadowed);
    }

    // ---------------------------------------------------------------- pattern tests (PATTERN_LOOPS, ADR-069 M1)

    /** How deep pattern ifs nest in a then-branch, and how many tests an {@code else if} chain has at most. */
    private static final int MAX_PATTERN_NESTING = 2;
    /** Whether the last {@link #patternUpdate} emitted a {@code break}. */
    private boolean patternBreak;

    /**
     * What a type test may test: a variable or a component read of a sealed type (any variant) or of a variant type
     * (that variant only), or a call of a helper returning one; {@code type} is the sealed or the variant type.
     */
    private record Tested(Var var, List<String> components, Helper helper, Type type) {
    }

    private List<Tested> tested(Ctx ctx) {
        var tested = new ArrayList<Tested>();
        for (Var var : readable(ctx)) {
            if (testable(var.type())) tested.add(new Tested(var, List.of(), null, var.type()));
            if (var.type().kind != Kind.RECORD) continue;
            for (Component component : var.type().components) {
                if (testable(component.type())) tested.add(new Tested(var, List.of(component.name()), null, component.type()));
            }
        }
        for (Helper helper : ctx.helpers()) {
            if (testable(helper.result()) && !(ctx.traceFree() && helper.traces())) {
                tested.add(new Tested(null, List.of(), helper, helper.result()));
            }
        }
        return tested;
    }

    /**
     * One of the candidates; steered to #204, a call a third of the time there is one, so that a helper's traces show
     * the order the test is evaluated in.
     */
    private Tested pick(List<Tested> candidates, List<Var> patternVariables) {
        if (profile.steers(Steer.ISSUE_204)) {
            var calls = candidates.stream().filter(t -> t.helper() != null).toList();
            if (!calls.isEmpty() && choices.oneIn(3)) return calls.get(choices.below(calls.size()));
            // A component of a pattern variable in scope half of the time: a nested test.
            var nested = candidates.stream()
                    .filter(t -> t.var() != null && !t.components().isEmpty() && patternVariables.contains(t.var())).toList();
            if (!nested.isEmpty() && choices.oneIn(2)) return nested.get(choices.below(nested.size()));
            // A value of a sealed interface's type three times in four: a test with both outcomes.
            var sealed = candidates.stream().filter(t -> t.type().kind == Kind.SEALED).toList();
            if (!sealed.isEmpty() && !choices.oneIn(4)) return sealed.get(choices.below(sealed.size()));
        }
        return candidates.get(choices.below(candidates.size()));
    }

    private static boolean testable(Type type) {
        return type.kind == Kind.SEALED || type.family != null;
    }

    /** The tested expression; a call's arguments are generated as a test's, with no switch in them. */
    private Expr testedExpression(Tested tested, Ctx ctx, int depth) {
        if (tested.helper() == null) {
            if (tested.var().field()) fieldReads++;
            if (tested.components().isEmpty()) return new G1Ast.Read(tested.var());
            features.add(Feature.COMPONENT_ACCESS);
            return new G1Ast.Access(tested.var(), tested.components());
        }
        Helper helper = tested.helper();
        features.add(Feature.HELPER_CALL);
        if (helper.traces()) traceSites++;
        var arguments = new ArrayList<Expr>();
        tests++;
        try {
            for (Var parameter : helper.parameters()) arguments.add(expression(parameter.type(), ctx, Math.max(0, depth - 1)));
        } finally {
            tests--;
        }
        return new G1Ast.Call(helper.name(), helper.result(), arguments);
    }

    /** A variant to test against: any of a sealed type's, a variant type's own. */
    private Type testedVariant(Type type) {
        return type.kind == Kind.SEALED ? type.variants.get(choices.below(type.variants.size())) : type;
    }

    /** {@code x instanceof V}, a bare type test (ADR-069 D1), or null when nothing in scope can be tested. */
    private Expr typeTest(Ctx ctx, int depth) {
        var candidates = tested(ctx);
        if (candidates.isEmpty()) return null;
        Tested tested = pick(candidates, List.of());
        Expr subject = testedExpression(tested, ctx, depth);
        features.add(Feature.BARE_TYPE_TEST);
        return new G1Ast.TypeTest(subject, testedVariant(tested.type()));
    }

    /**
     * {@code if (x instanceof V v) { … }} in a loop body (ADR-069 M1), after a local of a family's type when nothing
     * in scope can be tested, or an {@link #ifUpdate} when no family is declared; the scope after it is returned.
     */
    private Scope patternUpdate(Scope scope, List<Stmt> into, List<String> assigned, boolean breakAware) {
        if (tested(ctx(scope)).isEmpty()) {
            var families = types.stream().filter(t -> t.kind == Kind.SEALED).toList();
            if (families.isEmpty()) {
                ifUpdate(scope, into, assigned);
                return scope;
            }
            Type family = families.get(choices.below(families.size()));
            String name = freshName(false);
            into.add(new G1Ast.Declare(family, name, expression(family, ctx(declaring(scope, name)), EXPRESSION_DEPTH)));
            if (isField(name)) features.add(Feature.FIELD_SHADOWED);
            scope = scope.with(new Var(name, family, false, false, true, false));
        }
        into.add(patternIf(scope, assigned, breakAware, 0, null, null, 1));
        return scope;
    }

    /**
     * One pattern {@code if}, whose subject is, in an {@code else if}, two times in three the previous test's
     * ({@code subject}, of type {@code type}). Its then-branch may read a component of the variable into a local and
     * may nest another pattern {@code if}; it assigns an accumulator, preferring reads of the variable. Then no
     * {@code else}, an {@code else} that assigns, or an {@code else if}. In a body with {@code break}, the first test
     * of an outermost chain may end its then-branch or make its {@code else} a {@code break}, not both. The variable
     * is never assigned: it is not assignable in the branch's scope.
     */
    private G1Ast.PatternIf patternIf(Scope scope, List<String> assigned, boolean breakAware, int nesting, Expr subject,
                                      Type type, int chain) {
        if (subject == null || choices.oneIn(3)) {
            var candidates = tested(ctx(scope));
            Tested tested = pick(candidates, List.of());
            subject = testedExpression(tested, ctx(scope), EXPRESSION_DEPTH);
            type = tested.type();
        }
        Type variant = testedVariant(type);
        String binding = freshName(true);
        features.add(Feature.LOOP_PATTERN_IF);
        Var bound = new Var(binding, variant, false, false, true, false);
        patternVariables.add(bound);
        Scope branch = scope.with(bound);
        var then = new ArrayList<Stmt>();
        if (!variant.components.isEmpty() && choices.oneIn(2)) {
            Component component = variant.components.get(choices.below(variant.components.size()));
            String name = freshName(true);
            features.add(Feature.COMPONENT_ACCESS);
            then.add(new G1Ast.Declare(component.type(), name, new G1Ast.Access(bound, List.of(component.name()))));
            branch = branch.with(new Var(name, component.type(), false, false, true, false));
        }
        if (nesting + 1 < MAX_PATTERN_NESTING && choices.oneIn(4)) {
            then.add(patternIf(branch, assigned, false, nesting + 1, null, null, 1));
        }
        assignment(branch, then, assigned, choices.oneIn(3) ? null : binding);
        // Only the first test of an outermost chain breaks, in one branch at most, so that the if completes normally.
        boolean breaks = breakAware && nesting == 0 && chain == 1;
        boolean thenBreaks = breaks && choices.oneIn(profile.steers(Steer.ISSUE_204) ? 2 : 3);
        if (thenBreaks) {
            then.add(new G1Ast.Break());
            patternBreak = true;
        }
        List<Stmt> otherwise = null;
        int otherwiseKind = choices.below(4);
        // Steered to #204, an outermost test has an else if half of the time.
        if (profile.steers(Steer.ISSUE_204) && otherwiseKind == 0 && chain == 1) otherwiseKind = 3;
        if (otherwiseKind == 2) {
            otherwise = new ArrayList<>();
            if (breaks && !thenBreaks && choices.oneIn(3)) {
                otherwise.add(new G1Ast.Break());
                patternBreak = true;
            } else {
                assignment(scope, otherwise, assigned, null);
            }
        } else if (otherwiseKind == 3 && chain < MAX_PATTERN_NESTING + 1) {
            features.add(Feature.LOOP_PATTERN_ELSE_IF);
            otherwise = List.of(patternIf(scope, assigned, breakAware, nesting, subject, type, chain + 1));
        }
        return new G1Ast.PatternIf(subject, variant, binding, choices.oneIn(4), then, otherwise);
    }

    // ---------------------------------------------------------------- exit guards (PATTERN_EXIT, ADR-069 M3)

    /**
     * How a guard's exiting branch leaves (JLS 14.22: every path ends in it): {@code return} of {@code type} (a method,
     * or a lambda block's {@code boolean}), {@code yield} of the arm's type, or {@code break} from a loop body.
     */
    private record Exit(String kind, Type type) {
        static final String RETURN = "return", YIELD = "yield", BREAK = "break";
    }

    /** Whether the probes of JLS 6.3 outside G1's own model are generated (PATTERN_SCOPE_PROBES, ADR-069 verification 6). */
    private boolean probes() {
        return profile.has(Gate.PATTERN_SCOPE_PROBES);
    }

    /** Every pattern variable the method being generated declares, for PATTERN_SCOPE_PROBES' reads outside its scope. */
    private final List<Var> patternVariables = new ArrayList<>();

    /** Whether PATTERN_EXIT is on and a production chosen one time in {@code steered} or {@code plain}. */
    private boolean exits(int steered, int plain) {
        return profile.has(Gate.PATTERN_EXIT) && flows == 0
                && choices.oneIn(profile.steers(Steer.ISSUE_204) ? steered : plain);
    }

    /**
     * A guard (JLS 6.3.2.2): {@code if (!(x instanceof V v)) { exit }}, {@code if (!(x instanceof V v && c)) { exit }},
     * {@code if (!(x instanceof V v) || c) { exit }}, each sometimes with an {@code else} that falls through, or the
     * mirror {@code if (x instanceof V v && c) { falls } else { exit }}; it introduces {@code v} (and what {@code c}
     * adds) into the rest of the block. Returns those variables, after the statement and a first read of one of them
     * are added; an empty list when nothing can be tested. {@code assigned} collects a loop body's updates.
     */
    private List<Var> exitGuard(Ctx ctx, List<Stmt> into, Exit exit, List<String> assigned) {
        flows++;
        tests++;
        Flow leaf;
        Expr condition;
        List<Var> introduced;
        boolean mirrored;
        try {
            leaf = patternLeaf(ctx, List.of());
            if (leaf == null) return List.of();
            Ctx seeing = with(ctx, leaf.whenTrue()).conditionally();
            mirrored = false;
            switch (choices.below(4)) {
                case 0 -> {
                    condition = new G1Ast.Not(leaf.expr());
                    introduced = leaf.whenTrue();
                }
                case 1 -> {
                    Flow more = flow(seeing, leaf.whenTrue(), 1);
                    condition = new G1Ast.Not(new G1Ast.Logic(leaf.expr(), "&&", more.expr()));
                    introduced = concat(leaf.whenTrue(), more.whenTrue());
                }
                case 2 -> {
                    Flow more = flow(seeing, leaf.whenTrue(), 1);
                    condition = new G1Ast.Logic(new G1Ast.Not(leaf.expr()), "||", more.expr());
                    introduced = concat(leaf.whenTrue(), more.whenFalse());
                }
                default -> {
                    Flow more = flow(seeing, leaf.whenTrue(), 1);
                    condition = new G1Ast.Logic(leaf.expr(), "&&", more.expr());
                    introduced = concat(leaf.whenTrue(), more.whenTrue());
                    mirrored = true;
                }
            }
        } finally {
            flows--;
            tests--;
        }
        features.add(Feature.EXIT_GUARD);
        switch (exit.kind()) {
            case Exit.BREAK -> features.add(Feature.EXIT_BREAK);
            case Exit.YIELD -> features.add(Feature.EXIT_YIELD);
            default -> {
                if (lambdas > 0) features.add(Feature.EXIT_LAMBDA);
            }
        }
        var exiting = exitBranch(ctx, exit, assigned, 0);
        List<Stmt> falling = mirrored || choices.oneIn(3) ? fallingBranch(with(ctx, introduced), exit, assigned) : null;
        into.add(mirrored ? new G1Ast.If(condition, falling, exiting) : new G1Ast.If(condition, exiting, falling));
        // A first read of a variable it introduces, in the rest of the block.
        Var used = introduced.get(choices.below(introduced.size()));
        if (exit.kind().equals(Exit.RETURN) && lambdas > 0) return introduced;
        Expr read = new G1Ast.Read(used);
        Type type = used.type();
        if (!used.type().components.isEmpty() && choices.oneIn(2)) {
            Component component = used.type().components.get(choices.below(used.type().components.size()));
            read = new G1Ast.Access(used, List.of(component.name()));
            type = component.type();
            features.add(Feature.COMPONENT_ACCESS);
        }
        into.add(new G1Ast.Declare(type, freshName(true), read));
        return introduced;
    }

    /** A branch every path of which ends in the exit: the exit, or an if whose branches both exit, then the exit. */
    private List<Stmt> exitBranch(Ctx ctx, Exit exit, List<String> assigned, int nesting) {
        var body = new ArrayList<Stmt>();
        if (exit.kind().equals(Exit.BREAK) && choices.oneIn(2)) assignment(ctx.scope(), body, assigned, null);
        if (nesting == 0 && choices.oneIn(3)) {
            Expr test = test(ctx, 1);
            var then = exitBranch(ctx, exit, assigned, nesting + 1);
            if (choices.oneIn(2)) {
                body.add(new G1Ast.If(test, then, exitBranch(ctx, exit, assigned, nesting + 1)));
                return body;
            }
            body.add(new G1Ast.If(test, then, null));
        }
        body.add(exitStatement(ctx, exit));
        return body;
    }

    private Stmt exitStatement(Ctx ctx, Exit exit) {
        return switch (exit.kind()) {
            case Exit.BREAK -> new G1Ast.Break();
            case Exit.YIELD -> new G1Ast.Yield(expression(exit.type(), ctx.conditionally(), 1));
            default -> new G1Ast.Return(expression(exit.type(), ctx.conditionally(), 1));
        };
    }

    /** A branch that completes normally: an update in a loop body, a local elsewhere (none in a lambda block). */
    private List<Stmt> fallingBranch(Ctx ctx, Exit exit, List<String> assigned) {
        var body = new ArrayList<Stmt>();
        if (exit.kind().equals(Exit.BREAK)) assignment(ctx.scope(), body, assigned, null);
        else if (lambdas == 0 && choices.oneIn(2)) declaration(ctx.scope(), body, false, false);
        return body;
    }

    /**
     * Guards at method level, one or, steered to #204 half of the time, two in a row; the scope after them, with the
     * variables they introduce.
     */
    private Scope methodGuards(Scope scope, List<Stmt> into, Type result) {
        for (int n = profile.steers(Steer.ISSUE_204) && choices.oneIn(2) ? 2 : 1; n > 0; n--) {
            for (Var var : exitGuard(ctx(scope), into, new Exit(Exit.RETURN, result), new ArrayList<>())) scope = scope.with(var);
        }
        return scope;
    }

    /** {@code xs.any(e -> { guard; return c; })}: a lambda block with a guard, whose result reads what it introduces. */
    private Flow lambdaBlock(Ctx inside, List<Var> vars, String method, String parameter) {
        var body = new ArrayList<Stmt>();
        var introduced = exitGuard(inside, body, new Exit(Exit.RETURN, BOOL), new ArrayList<>());
        var visible = concat(vars, introduced);
        Expr result;
        if (introduced.isEmpty()) {
            result = flow(inside, vars, 1).expr();
        } else {
            Ctx after = with(inside, introduced);
            Expr read = use(introduced.get(choices.below(introduced.size())), after);
            result = choices.oneIn(2) ? read
                    : new G1Ast.Logic(read, choices.oneIn(2) ? "&&" : "||", flow(after.conditionally(), visible, 1).expr());
        }
        body.add(new G1Ast.Return(result));
        return new Flow(new G1Ast.LambdaBlock(method, parameter, body), List.of(), List.of());
    }

    /**
     * PATTERN_SCOPE_PROBES (javac-filtered, ADR-069 verification 6): a read of a pattern variable declared earlier in the
     * method, wherever it is: javac decides whether it is in scope, and the program is skipped when it is not.
     */
    private void staleRead(Scope scope, List<Stmt> into) {
        var candidates = patternVariables.stream().filter(v -> !scope.locals().contains(v)).toList();
        if (candidates.isEmpty()) return;
        Var var = candidates.get(choices.below(candidates.size()));
        features.add(Feature.PROBE_STALE_READ);
        into.add(new G1Ast.Declare(var.type(), freshName(true), new G1Ast.Read(var)));
    }

    /**
     * PATTERN_SCOPE_PROBES: {@code if (!(x instanceof V v) || true) { return e; } else { local reading v; }} and a read
     * of {@code v} after it: JLS 6.3.1 puts {@code v} in the else branch and after the statement, which G1's model does
     * not generate.
     */
    private Scope constantElse(Scope scope, List<Stmt> into, Type result) {
        Flow leaf;
        flows++;
        tests++;
        try {
            leaf = patternLeaf(ctx(scope), List.of());
        } finally {
            flows--;
            tests--;
        }
        if (leaf == null) return scope;
        features.add(Feature.PROBE_CONSTANT_ELSE);
        Var var = leaf.whenTrue().getFirst();
        var otherwise = new ArrayList<Stmt>();
        otherwise.add(new G1Ast.Declare(var.type(), freshName(true), new G1Ast.Read(var)));
        into.add(new G1Ast.If(new G1Ast.Logic(new G1Ast.Not(leaf.expr()), "||", new G1Ast.BoolConst(true)),
                List.of(new G1Ast.Return(expression(result, ctx(scope), 1))), otherwise));
        return scope.with(var);
    }

    // ---------------------------------------------------------------- pattern conditions (PATTERN_CONDITIONS, ADR-069 M2)

    /**
     * A condition and the pattern variables it introduces when true and when false (JLS 6.3.1): {@code x instanceof V v}
     * introduces {@code v} when true; {@code a && b} what either introduces when true, and {@code b} sees what {@code a}
     * introduces when true; {@code a || b} the same when false; {@code !a} swaps; parentheses keep; a boolean
     * {@code c ? a : b}, whose arms see what {@code c} introduces when true and when false, introduces nothing, as does
     * a lambda. Names are unique in a method, so no variable is introduced twice.
     */
    private record Flow(Expr expr, List<Var> whenTrue, List<Var> whenFalse) {
    }

    /** How many flows enclose the expression being generated: no flow, and no switch, is generated inside one. */
    private int flows;
    /** How many lambdas enclose the flow being generated. */
    private int lambdas;
    /** Whether a {@code while} condition's flow is being generated. */
    private boolean whileGuard;

    private static Ctx with(Ctx ctx, List<Var> vars) {
        Scope scope = ctx.scope();
        for (Var var : vars) scope = scope.with(var);
        return new Ctx(scope, ctx.helpers(), ctx.conditional(), ctx.constant(), ctx.preferred(), ctx.traceFree());
    }

    private static List<Var> concat(List<Var> first, List<Var> second) {
        var all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /**
     * A condition over {@code &&}, {@code ||}, {@code !}, {@code ?:}, a lambda or parentheses, never a lone test: a
     * whole {@code if} condition that is one pattern test is M1's form. No switch is generated inside it.
     */
    private Flow flowCondition(Ctx ctx, int depth) {
        flows++;
        tests++;
        try {
            return composite(ctx, List.of(), Math.max(1, depth));
        } finally {
            flows--;
            tests--;
        }
    }

    private Flow flow(Ctx ctx, List<Var> vars, int depth) {
        if (depth <= 0 || choices.oneIn(3)) return leaf(ctx, vars);
        if (choices.oneIn(6)) {
            Flow inner = flow(ctx, vars, depth - 1);
            return new Flow(new G1Ast.Paren(inner.expr()), inner.whenTrue(), inner.whenFalse());
        }
        return composite(ctx, vars, depth);
    }

    private Flow composite(Ctx ctx, List<Var> vars, int depth) {
        boolean lambda = !inHelper && lambdas == 0 && !ctx.constant();
        switch (choices.below(lambda ? 9 : 8)) {
            case 0, 1, 2 -> {
                // a && b, a third of the time with a pattern test on the left (rows a02, b02)
                Flow left = choices.oneIn(3) ? patternLeaf(ctx, vars) : flow(ctx, vars, depth - 1);
                if (left == null) left = flow(ctx, vars, depth - 1);
                Flow right = flow(with(ctx, left.whenTrue()).conditionally(), concat(vars, left.whenTrue()), depth - 1);
                return new Flow(new G1Ast.Logic(left.expr(), "&&", right.expr()), concat(left.whenTrue(), right.whenTrue()),
                        List.of());
            }
            case 3, 4 -> {
                Flow left = flow(ctx, vars, depth - 1);
                Flow right = flow(with(ctx, left.whenFalse()).conditionally(), concat(vars, left.whenFalse()), depth - 1);
                return new Flow(new G1Ast.Logic(left.expr(), "||", right.expr()), List.of(),
                        concat(left.whenFalse(), right.whenFalse()));
            }
            case 5, 6 -> {
                Flow operand = flow(ctx, vars, depth - 1);
                return new Flow(new G1Ast.Not(operand.expr()), operand.whenFalse(), operand.whenTrue());
            }
            case 7 -> {
                Flow test = flow(ctx, vars, depth - 1);
                Flow then = flow(with(ctx, test.whenTrue()).conditionally(), concat(vars, test.whenTrue()), depth - 1);
                Flow otherwise = flow(with(ctx, test.whenFalse()).conditionally(), concat(vars, test.whenFalse()), depth - 1);
                return new Flow(new G1Ast.Cond(test.expr(), then.expr(), otherwise.expr()), List.of(), List.of());
            }
            default -> {
                return lambda(ctx, vars, depth);
            }
        }
    }

    /**
     * {@code xs.any(e -> …)} or {@code xs.all(e -> …)} over a flow that may read {@code e} and every variable it may
     * capture: those never assigned (parameters, pattern variables, finals, for-each elements); the others still hide
     * fields of their names.
     */
    private Flow lambda(Ctx ctx, List<Var> vars, int depth) {
        String method = choices.oneIn(2) ? "any" : "all";
        String parameter = freshName(true);
        var locals = new ArrayList<Var>();
        for (Var var : ctx.scope().locals()) {
            locals.add(var.guardEligible() && !var.counter() ? var : new Var(var.name(), UNREADABLE, false, false, false, false));
        }
        var scope = new Scope(locals, ctx.scope().loopDepth()).with(new Var(parameter, BIG, false, false, true, false));
        var inside = new Ctx(scope, ctx.helpers(), true, false, null, ctx.traceFree());
        lambdas++;
        try {
            // Under PATTERN_EXIT a quarter of the lambdas are blocks with a guard; steered, half.
            if (profile.has(Gate.PATTERN_EXIT) && choices.oneIn(profile.steers(Steer.ISSUE_204) ? 2 : 4)) {
                return lambdaBlock(inside, vars, method, parameter);
            }
            Flow body = flow(inside, vars, Math.max(1, depth - 1));
            return new Flow(new G1Ast.Lambda(method, parameter, body.expr()), List.of(), List.of());
        } finally {
            lambdas--;
        }
    }

    /** A test, a read of a variable in scope, a bare type test or a plain condition. */
    private Flow leaf(Ctx ctx, List<Var> vars) {
        boolean steered = profile.steers(Steer.ISSUE_204);
        if (!vars.isEmpty() && (steered ? !choices.oneIn(3) : choices.oneIn(3))) {
            return new Flow(use(vars.get(choices.below(vars.size())), ctx), List.of(), List.of());
        }
        switch (choices.below(4)) {
            case 0, 1 -> {
                Flow pattern = patternLeaf(ctx, vars);
                if (pattern != null) return pattern;
            }
            case 2 -> {
                Expr bare = typeTest(ctx, 1);
                if (bare != null) return new Flow(bare, List.of(), List.of());
            }
            default -> {
            }
        }
        return new Flow(condition(ctx, 0), List.of(), List.of());
    }

    /** {@code x instanceof V v}, or null when nothing in scope can be tested. */
    private Flow patternLeaf(Ctx ctx, List<Var> vars) {
        var candidates = tested(ctx);
        if (candidates.isEmpty()) return null;
        Tested tested = pick(candidates, vars);
        Expr subject = testedExpression(tested, ctx, 1);
        Type variant = testedVariant(tested.type());
        String binding = freshName(true);
        features.add(Feature.PATTERN_CONDITION);
        patternVariables.add(new Var(binding, variant, false, false, true, false));
        if (lambdas > 0) features.add(Feature.PATTERN_IN_LAMBDA);
        if (whileGuard) features.add(Feature.PATTERN_IN_WHILE_CONDITION);
        return new Flow(new G1Ast.PatternTest(subject, variant, binding),
                List.of(new Var(binding, variant, false, false, true, false)), List.of());
    }

    /**
     * A read of a pattern variable in scope: a {@code boolean} component, an integer component compared, another
     * component compared with {@code equals} against its own type, or the variable compared with its own type.
     */
    private Expr use(Var var, Ctx ctx) {
        var components = var.type().components;
        if (!components.isEmpty() && !choices.oneIn(4)) {
            Component component = components.get(choices.below(components.size()));
            features.add(Feature.COMPONENT_ACCESS);
            Expr read = new G1Ast.Access(var, List.of(component.name()));
            if (component.type() == BOOL) return read;
            if (component.type() == BIG) {
                var operands = profile.has(Gate.TRACE_IN_COMPARISON) ? ctx : ctx.withoutTraces();
                return new G1Ast.Compare(read, integer(operands, 1), COMPARISONS[choices.below(COMPARISONS.length)]);
            }
            return new G1Ast.Equals(read, atom(component.type(), ctx));
        }
        return new G1Ast.Equals(new G1Ast.Read(var), atom(var.type(), ctx));
    }

    /** {@code if (flow) { locals; return e; } [else { …; return e; }]} at method level, each branch reading its variables. */
    private G1Ast.If flowIf(Scope scope, Type result, boolean otherwise) {
        Flow flow = flowCondition(ctx(scope), profile.steers(Steer.ISSUE_204) ? 3 : 2);
        var then = flowBranch(scope, flow.whenTrue(), result);
        return new G1Ast.If(flow.expr(), then, otherwise ? flowBranch(scope, flow.whenFalse(), result) : null);
    }

    private List<Stmt> flowBranch(Scope scope, List<Var> vars, Type result) {
        for (Var var : vars) scope = scope.with(var);
        String preferred = vars.isEmpty() ? null : vars.get(choices.below(vars.size())).name();
        var body = new ArrayList<Stmt>();
        if (choices.oneIn(3)) scope = declaration(scope, body, false, false);
        body.add(new G1Ast.Return(expression(result, ctx(scope).preferring(preferred), EXPRESSION_DEPTH)));
        return body;
    }

    /** {@code if (flow) { [local;] acc = e; } [else { acc2 = e; }]} in a loop body, each branch reading its variables. */
    private void flowUpdate(Scope scope, List<Stmt> into, List<String> assigned) {
        Flow flow = flowCondition(ctx(scope), profile.steers(Steer.ISSUE_204) ? 3 : 2);
        var then = new ArrayList<Stmt>();
        Scope branch = scope;
        for (Var var : flow.whenTrue()) branch = branch.with(var);
        if (choices.oneIn(3)) branch = declaration(branch, then, false, false);
        assignment(branch, then, assigned, flow.whenTrue().isEmpty() ? null : flow.whenTrue().getFirst().name());
        List<Stmt> otherwise = null;
        if (choices.oneIn(2)) {
            otherwise = new ArrayList<>();
            Scope other = scope;
            for (Var var : flow.whenFalse()) other = other.with(var);
            assignment(other, otherwise, assigned, flow.whenFalse().isEmpty() ? null : flow.whenFalse().getFirst().name());
        }
        into.add(new G1Ast.If(flow.expr(), then, otherwise));
    }

    /** A value {@code (flow ? a : b)} whose arms read the variables the condition introduces when true and when false. */
    private Expr flowTernary(Type type, Ctx ctx, int depth) {
        Flow flow = flowCondition(ctx, depth);
        String whenTrue = flow.whenTrue().isEmpty() ? null : flow.whenTrue().getFirst().name();
        String whenFalse = flow.whenFalse().isEmpty() ? null : flow.whenFalse().getFirst().name();
        Expr then = expression(type, with(ctx, flow.whenTrue()).conditionally().preferring(whenTrue), depth - 1);
        Expr otherwise = expression(type, with(ctx, flow.whenFalse()).conditionally().preferring(whenFalse), depth - 1);
        return new G1Ast.Cond(flow.expr(), then, otherwise);
    }

    /** Whether PATTERN_CONDITIONS is on and a production chosen one time in {@code steered} or {@code plain}. */
    private boolean conditions(int steered, int plain) {
        return profile.has(Gate.PATTERN_CONDITIONS) && flows == 0
                && choices.oneIn(profile.steers(Steer.ISSUE_204) ? steered : plain);
    }

    /**
     * A field of a record or sealed type that no local in scope hides, so that a branch local may take its name;
     * a record with components first, since #209 shows in a component read.
     */
    private Var shadowable(Scope scope) {
        var candidates = fields.stream().filter(f -> f.type().isValue() && readable(scope).contains(f)).toList();
        var records = candidates.stream().filter(f -> f.type().kind == Kind.RECORD && !f.type().components.isEmpty()).toList();
        if (!records.isEmpty()) candidates = records;
        return candidates.isEmpty() ? null : candidates.get(choices.below(candidates.size()));
    }

    /**
     * A type other than {@code type}: another record with the same component names if there is one (#209's own shape),
     * else another record with components, else another declared type.
     */
    private Type otherType(Type type) {
        var others = valueTypes().stream().filter(t -> t != type).toList();
        var records = others.stream().filter(t -> t.kind == Kind.RECORD && !t.components.isEmpty()).toList();
        var names = type.components.stream().map(Component::name).sorted().toList();
        var alike = records.stream().filter(t -> t.components.stream().map(Component::name).sorted().toList().equals(names)).toList();
        if (!alike.isEmpty()) records = alike;
        if (!records.isEmpty()) others = records;
        return others.isEmpty() ? BIG : others.get(choices.below(others.size()));
    }

    /** After the branch, an accumulator assigned from a component read of the field, when one has a fitting type. */
    private void fieldRead(Scope scope, List<Stmt> into, List<String> assigned, String field) {
        var ctx = ctx(scope).preferring(field);
        var targets = scope.assignable().stream()
                .filter(t -> paths(t.type(), ctx).stream().anyMatch(p -> p.var().name().equals(field))).toList();
        if (targets.isEmpty()) {
            assignment(scope, into, assigned, field);
            return;
        }
        Var target = targets.get(choices.below(targets.size()));
        assigned.add(target.name());
        accumulatorFeatures(target);
        into.add(new G1Ast.Assign(target.name(), access(target.type(), ctx)));
        shadowRead = target.name();
    }

    private void assignment(Scope scope, List<Stmt> into, List<String> assigned, String preferred) {
        var targets = scope.assignable();
        if (targets.isEmpty()) {
            declaration(scope, into, false, false);
            return;
        }
        Var target = targets.get(choices.below(targets.size()));
        assigned.add(target.name());
        accumulatorFeatures(target);
        // A switch as the assigned value half of the time: a switch in a loop body (SWITCH_IN_LOOP).
        Expr value = preferred == null && choices.oneIn(2) ? switchExpression(target.type(), ctx(scope), EXPRESSION_DEPTH) : null;
        if (value == null) value = expression(target.type(), ctx(scope).preferring(preferred), EXPRESSION_DEPTH);
        into.add(new G1Ast.Assign(target.name(), value));
    }

    private void accumulatorFeatures(Var target) {
        if (target.type().isValue()) features.add(Feature.RECORD_ACCUMULATOR);
        if (target.type().family != null) features.add(Feature.VARIANT_ACCUMULATOR);
    }

    // ---------------------------------------------------------------- expressions

    /** The variables readable here: the locals, and the fields no local hides. */
    private List<Var> readable(Scope scope) {
        var vars = new ArrayList<>(scope.locals());
        for (Var field : fields) {
            if (scope.locals().stream().noneMatch(v -> v.name().equals(field.name()))) vars.add(field);
        }
        return vars;
    }

    /** In a field initializer, only the fields declared before it. */
    private List<Var> readable(Ctx ctx) {
        return ctx.constant() ? List.copyOf(fields) : readable(ctx.scope());
    }

    private Expr expression(Type type, Ctx ctx, int depth) {
        return switch (type.kind) {
            case BIG -> integer(ctx, depth);
            case BOOL -> condition(ctx, depth);
            case RECORD, SEALED -> value(type, ctx, depth);
        };
    }

    private Expr integer(Ctx ctx, int depth) {
        if (ctx.conditional() && !ctx.constant() && choices.oneIn(6)) return failure(atom(BIG, ctx));
        if (depth == 0 || choices.below(3) == 0) return atom(BIG, ctx);
        return switch (choices.below(14)) {
            case 0 -> new G1Ast.Arith(receiver(ctx, depth - 1), "add", argument(BIG, ctx, depth - 1));
            case 1 -> new G1Ast.Arith(receiver(ctx, depth - 1), "subtract", argument(BIG, ctx, depth - 1));
            case 2 -> new G1Ast.Scale(receiver(ctx, depth - 1), 1 + choices.below(3));
            case 3 -> ctx.constant() ? atom(BIG, ctx) : ternary(BIG, ctx, depth);
            case 4, 10 -> access(BIG, ctx);
            case 5, 9 -> call(BIG, ctx, depth);
            case 6, 11 -> ctx.constant() || ctx.traceFree() ? atom(BIG, ctx) : trace(integer(ctx, depth - 1));
            case 7 -> !ctx.constant() && (ctx.conditional() || choices.oneIn(8))
                    ? failure(integer(ctx, depth - 1)) : atom(BIG, ctx);
            case 12, 13 -> switchOrAtom(BIG, ctx, depth);
            default -> atom(BIG, ctx);
        };
    }

    /**
     * The test of an {@code if}, a {@code break} or a ternary: a condition with no {@code switch} in it. javac 25
     * emits bytecode the JVM rejects (VerifyError) for a pattern switch used in a test followed by a pattern switch
     * evaluated as a call argument, so tests stay switch-free and the oracle stays sound.
     */
    private Expr test(Ctx ctx, int depth) {
        tests++;
        try {
            return condition(ctx, depth);
        } finally {
            tests--;
        }
    }

    /** How many tests enclose the expression being generated: no switch is generated inside one. */
    private int tests;

    /**
     * The receiver of a {@code BigInteger} method: an integer expression with no {@code switch} in it, since JuLC
     * rejects a method called on a switch expression (JULC0055, outside the positive grammar like an accessor on a
     * constructor expression).
     */
    private Expr receiver(Ctx ctx, int depth) {
        receivers++;
        try {
            return integer(ctx, depth);
        } finally {
            receivers--;
        }
    }

    /** How many method receivers enclose the expression being generated: no switch is generated inside one. */
    private int receivers;

    /**
     * The argument of an {@code add}, {@code subtract}, {@code compareTo} or {@code equals} call. In a default block of
     * a switch anywhere inside it, nothing is declared after the block's first loop unless DEFAULT_BLOCK_SCOPE is on
     * (#291); steered to #291's loops, half of these arguments are switches.
     */
    private Expr argument(Type type, Ctx ctx, int depth) {
        methodArguments++;
        try {
            if (profile.steers(Steer.ISSUE_291_LOOPS) && choices.oneIn(2)) {
                Expr selected = switchExpression(type, ctx, Math.max(depth, 1));
                if (selected != null) return selected;
            }
            return type == BIG ? integer(ctx, depth) : value(type, ctx, depth);
        } finally {
            methodArguments--;
        }
    }

    /** How many method-call arguments enclose the expression being generated. */
    private int methodArguments;

    /** Trace sites generated so far, counting calls of helpers that trace. */
    private int traceSites;

    private Expr trace(Expr expression) {
        traceSites++;
        features.add(Feature.TRACE);
        return new G1Ast.Trace("k" + labels++, expression);
    }

    private Expr failure(Expr expression) {
        features.add(Feature.FAILURE);
        return new G1Ast.Fail(expression);
    }

    private Expr condition(Ctx ctx, int depth) {
        if (ctx.constant()) return new G1Ast.BoolConst(choices.oneIn(2));
        // Under PATTERN_LOOPS a tenth of the conditions are a bare type test; steered to #204, a quarter.
        if (profile.has(Gate.PATTERN_LOOPS) && choices.oneIn(profile.steers(Steer.ISSUE_204) ? 4 : 10)) {
            Expr test = typeTest(ctx, depth);
            if (test != null) return test;
        }
        // Under PATTERN_CONDITIONS a tenth of the conditions are pattern conditions; steered to #204, a third.
        if (conditions(3, 10)) return flowCondition(ctx, depth).expr();
        if (depth == 0 || choices.below(3) == 0) return atom(BOOL, ctx);
        return switch (choices.below(13)) {
            case 0, 1 -> comparison(ctx, depth);
            case 2 -> new G1Ast.Logic(condition(ctx, depth - 1), "&&", condition(ctx.conditionally(), depth - 1));
            case 3 -> new G1Ast.Logic(condition(ctx, depth - 1), "||", condition(ctx.conditionally(), depth - 1));
            case 4 -> new G1Ast.Not(condition(ctx, depth - 1));
            case 5, 6, 10 -> equality(ctx, depth);
            case 7, 8, 9 -> call(BOOL, ctx, depth);
            case 12 -> switchOrAtom(BOOL, ctx, depth);
            default -> atom(BOOL, ctx);
        };
    }

    /**
     * {@code e1.compareTo(e2) OP 0}. Its operands do not trace unless TRACE_IN_COMPARISON is on: the lowering
     * evaluates an operand more than once (#146), so a trace there is an open defect's construct.
     */
    private Expr comparison(Ctx ctx, int depth) {
        var operands = profile.has(Gate.TRACE_IN_COMPARISON) ? ctx : ctx.withoutTraces();
        int traced = traceSites;
        Expr left = receiver(operands, depth - 1);
        if (profile.steers(Steer.ISSUE_146) && !ctx.constant() && !ctx.traceFree() && choices.oneIn(3)) left = trace(left);
        Expr right = argument(BIG, operands, depth - 1);
        var comparison = new G1Ast.Compare(left, right, COMPARISONS[choices.below(COMPARISONS.length)]);
        if (traceSites > traced) features.add(Feature.TRACE_IN_COMPARISON);
        return comparison;
    }

    /** {@code r.equals(e)}: a variable of a declared type against a value of the same type, or of its family. */
    private Expr equality(Ctx ctx, int depth) {
        var receivers = readable(ctx).stream().filter(v -> v.type().isValue()).toList();
        if (receivers.isEmpty()) return comparison(ctx, depth);
        var familial = receivers.stream().filter(v -> v.type().kind == Kind.SEALED || v.type().family != null).toList();
        if (!familial.isEmpty() && !choices.oneIn(4)) receivers = familial;
        Var receiver = receivers.get(choices.below(receivers.size()));
        Type type = receiver.type().family != null && !choices.oneIn(4) ? receiver.type().family : receiver.type();
        // Steered to #204, whose campaign requires agreement, no argument that JuLC rejects by design (ADR-068 D6): a
        // variant is compared with its own type only, since against its family the argument may be another variant
        // (JULC0078); and the argument is a variable, a component read or a constructor, since equals's Object
        // parameter gives a conditional or a switch over two variants no target type (JULC0012).
        if (profile.steers(Steer.ISSUE_204)) {
            type = receiver.type();
            features.add(Feature.RECORD_EQUALS);
            if (type.kind == Kind.SEALED) features.add(Feature.FAMILY_EQUALS);
            Expr read = read(receiver);
            return new G1Ast.Equals(read, choices.oneIn(2) ? atom(type, ctx) : construct(type, ctx, Math.max(1, depth - 1)));
        }
        features.add(Feature.RECORD_EQUALS);
        if (type.kind == Kind.SEALED) features.add(Feature.FAMILY_EQUALS);
        Expr read = read(receiver);
        return new G1Ast.Equals(read, argument(type, ctx, depth - 1));
    }

    private Expr value(Type type, Ctx ctx, int depth) {
        if (depth == 0 || choices.below(3) == 0) return atom(type, ctx);
        return switch (choices.below(7)) {
            case 0 -> construct(type, ctx, depth);
            case 1 -> access(type, ctx);
            case 2 -> ctx.constant() ? construct(type, ctx, depth) : ternary(type, ctx, depth);
            case 3 -> call(type, ctx, depth);
            case 5, 6 -> switchOrAtom(type, ctx, depth);
            default -> atom(type, ctx);
        };
    }

    /** {@code new R(…)}; for a sealed type, a variant's constructor. */
    private Expr construct(Type type, Ctx ctx, int depth) {
        Type record = type.kind == Kind.SEALED ? type.variants.get(choices.below(type.variants.size())) : type;
        var arguments = new ArrayList<Expr>();
        for (Component component : record.components) {
            arguments.add(expression(component.type(), ctx, Math.max(0, depth - 1)));
        }
        return new G1Ast.New(record, arguments);
    }

    private Expr ternary(Type type, Ctx ctx, int depth) {
        if (conditions(3, 6)) return flowTernary(type, ctx, depth);
        Expr test = test(ctx, depth - 1);
        Expr then = expression(type, ctx.conditionally(), depth - 1);
        return new G1Ast.Cond(test, then, expression(type, ctx.conditionally(), depth - 1));
    }

    /** A call of a helper whose result fits, or an atom. */
    private Expr call(Type type, Ctx ctx, int depth) {
        if (ctx.constant()) return atom(type, ctx);
        var callable = ctx.callable(type);
        if (callable.isEmpty()) return atom(type, ctx);
        Helper helper = callable.get(choices.below(callable.size()));
        features.add(Feature.HELPER_CALL);
        if (helper.result() == BOOL) features.add(Feature.BOOLEAN_HELPER_CALL);
        if (helper.traces()) traceSites++;
        var arguments = new ArrayList<Expr>();
        for (Var parameter : helper.parameters()) arguments.add(expression(parameter.type(), ctx, Math.max(0, depth - 1)));
        return new G1Ast.Call(helper.name(), helper.result(), arguments);
    }

    // ---------------------------------------------------------------- switch expressions (D2, M3 part 1)

    private static final int MAX_SWITCH_NESTING = 2;
    /** How many switches enclose the expression being generated, through their arms. */
    private int switchNesting;
    /** How many enclosing loop bodies contain a break: an arm there contains no loop. */
    private int breakAwareBodies;
    /** How many switches have been generated. */
    private int switches;
    /** How many restricted default blocks enclose the yield being generated after their loop: no switch there (#291). */
    private int afterDefaultLoop;

    /** A value a switch can select on: a variable of a family's type, or a component of that type read from one. */
    private record Subject(Var var, Expr expression, Type family) {
    }

    private List<Subject> subjects(Ctx ctx) {
        var subjects = new ArrayList<Subject>();
        for (Var var : readable(ctx)) {
            if (var.type().kind == Kind.SEALED) subjects.add(new Subject(var, new G1Ast.Read(var), var.type()));
            if (var.type().kind != Kind.RECORD) continue;
            for (Component component : var.type().components) {
                if (component.type().kind == Kind.SEALED) {
                    subjects.add(new Subject(var, new G1Ast.Access(var, List.of(component.name())), component.type()));
                }
            }
        }
        return subjects;
    }

    private Expr switchOrAtom(Type result, Ctx ctx, int depth) {
        Expr value = switchExpression(result, ctx, depth);
        return value != null ? value : atom(result, ctx);
    }

    /**
     * {@code switch (s) { case V q -> …; … default -> …; }} over a sealed value, or null where none can be generated.
     * The arms cover every variant once, in an order chosen independently of {@code permits} and declaration
     * orders, or some of them followed by {@code default}: one arm per variant until guards (M3 part 2).
     */
    private Expr switchExpression(Type result, Ctx ctx, int depth) {
        if (ctx.constant() || depth < 1 || switchNesting >= MAX_SWITCH_NESTING || receivers > 0 || tests > 0
                || afterDefaultLoop > 0) {
            return null;
        }
        var subjects = subjects(ctx);
        if (subjects.isEmpty()) return null;
        Subject subject = subjects.get(choices.below(subjects.size()));
        if (subject.var().field()) fieldReads++;
        features.add(Feature.SWITCH);
        switches++;
        if (switchNesting > 0) features.add(Feature.NESTED_SWITCH);
        if (ctx.scope().loopDepth() > 0) features.add(Feature.SWITCH_IN_LOOP);
        var order = permuted(subject.family().variants);
        // Some variants and default two times in three; steered to #291, always.
        boolean someVariants = order.size() > 1
                && (profile.steers(Steer.ISSUE_291) || profile.steers(Steer.ISSUE_291_LOOPS) || !choices.oneIn(3));
        int covered = someVariants ? 1 + choices.below(order.size() - 1) : order.size();
        var arms = new ArrayList<G1Ast.Arm>();
        switchNesting++;
        try {
            for (Type variant : order.subList(0, covered)) arms.add(arm(variant, result, ctx, depth));
            if (covered < order.size()) arms.add(arm(null, result, ctx, depth));
        } finally {
            switchNesting--;
        }
        return new G1Ast.Switch(subject.expression(), subject.family(), result, arms);
    }

    /**
     * An arm: an expression, or half of the time a block of one or two statements (locals, or a loop over an
     * accumulator the arm owns) ending in {@code yield}. Arms are evaluated only when selected, so they may fail. Inside
     * a block, enclosing variables are read-only: an arm never assigns them (ADR-048). A block is not generated where
     * traces are not allowed (a {@code compareTo} operand), so that its statements cannot trace there.
     */
    private G1Ast.Arm arm(Type variant, Type result, Ctx ctx, int depth) {
        Scope scope = ctx.scope();
        String binding = null;
        Var bound = null;
        if (variant != null) {
            binding = freshName(false);
            bound = new Var(binding, variant, false, false, true, false);
            scope = scope.with(bound);
            features.add(Feature.TYPE_PATTERN);
        } else {
            features.add(Feature.DEFAULT_ARM);
        }
        var armCtx = new Ctx(scope, ctx.helpers(), true, false, null, ctx.traceFree());
        boolean defaultBlock = variant == null;
        boolean steered = defaultBlock && profile.steers(Steer.ISSUE_291);
        // A local declared after a loop in a default block, in a switch inside a method-call argument, is lost (#291):
        // without the gate, nothing is declared there after the first loop, not even by a switch in the yield.
        boolean inArgument = defaultBlock && methodArguments > 0;
        boolean loopsSteered = inArgument && profile.steers(Steer.ISSUE_291_LOOPS);
        boolean restricted = inArgument && !profile.has(Gate.DEFAULT_BLOCK_SCOPE);
        if (ctx.traceFree() || !steered && !loopsSteered && choices.oneIn(2)) {
            // A nested switch as the arm a third of the time it can be one (NESTED_SWITCH).
            Expr nested = choices.oneIn(3) ? switchExpression(result, armCtx, depth - 1) : null;
            return new G1Ast.Arm(variant, binding, List.of(), nested != null ? nested : expression(result, armCtx, depth - 1));
        }
        features.add(Feature.YIELD_BLOCK);
        var body = new ArrayList<Stmt>();
        var owned = new Scope(scope.locals().stream()
                .map(v -> new Var(v.name(), v.type(), v.counter(), false, v.guardEligible(), v.field())).toList(), scope.loopDepth());
        // A local declared directly in a default block keeps its type after the switch (#291): gated if named like a field.
        Boolean shadow = defaultBlock && !profile.has(Gate.DEFAULT_BLOCK_SCOPE) ? false : null;
        if (steered) owned = defaultShadow(owned, body);
        var updated = new ArrayList<String>();
        int loops = 0;
        Var yielded = null;
        for (int n = loopsSteered ? 2 : 1 + choices.below(2); n > 0; n--) {
            if (restricted && loops > 0) break;
            if (inArgument && loops > 0) features.add(Feature.DEFAULT_BLOCK_AFTER_LOOP);
            // Steered to #291's loops: a loop, then a declaration or a while loop, whose counter is declared after it.
            boolean loop = breakAwareBodies == 0 && owned.loopDepth() < MAX_LOOP_DEPTH
                    && (loopsSteered ? loops == 0 || choices.oneIn(2) : !choices.oneIn(3));
            if (loop) {
                owned = armLoop(owned, body, bound, shadow, updated, loopsSteered && loops > 0);
                loops++;
            } else if (loopsSteered && loops > 0) {
                // The reviewer's form: a local of the arm's type declared after the loop, which the yield reads.
                String name = freshName(true);
                body.add(new G1Ast.Declare(result, name, expression(result, ctx(declaring(owned, name)), EXPRESSION_DEPTH - 1)));
                owned = owned.with(new Var(name, result, false, false, true, false));
                yielded = owned.locals().getLast();
            } else {
                owned = declaration(owned, body, false, shadow);
            }
            if (defaultBlock && isField(owned.locals().getLast().name())) features.add(Feature.DEFAULT_BLOCK_SHADOW);
        }
        if (bound != null && updated.contains(binding)) features.add(Feature.ASSIGN_PATTERN_BINDING);
        for (Stmt statement : body) {
            if (bound != null && statement instanceof G1Ast.Declare local && local.init() instanceof G1Ast.Read read
                    && read.var().name().equals(binding) && updated.contains(local.name())) {
                features.add(Feature.BINDING_ACCUMULATOR);
            }
        }
        // Under PATTERN_EXIT a quarter of the blocks end in a guard that yields; steered, every one.
        if (!restricted && yielded == null && exits(1, 4)) {
            var guarded = new Ctx(owned, ctx.helpers(), true, false, null, false);
            for (Var var : exitGuard(guarded, body, new Exit(Exit.YIELD, result), new ArrayList<>())) owned = owned.with(var);
        }
        var valueCtx = new Ctx(owned, ctx.helpers(), true, false, null, false);
        // After a loop there, the yield declares nothing either: no switch, whose bindings and blocks would.
        boolean quiet = restricted && loops > 0;
        int before = switches;
        if (quiet) afterDefaultLoop++;
        Expr value;
        try {
            value = yielded != null ? read(yielded) : expression(result, valueCtx, depth - 1);
        } finally {
            if (quiet) afterDefaultLoop--;
        }
        if (inArgument && loops > 0 && switches > before) features.add(Feature.DEFAULT_BLOCK_AFTER_LOOP);
        return new G1Ast.Arm(variant, binding, body, value);
    }

    /**
     * A loop over an accumulator the arm owns: two times in three a copy of the case-pattern binding, #162's positive
     * form ({@code LoopConditionalLocalTest}), else a new value. Under ASSIGN_PATTERN_BINDING the loop may assign the
     * binding itself instead, #162's historical construct, which the fixed compiler rejects.
     */
    private Scope armLoop(Scope scope, List<Stmt> into, Var binding, Boolean shadow, List<String> updated, boolean counted) {
        features.add(Feature.LOOP_IN_ARM);
        Scope loopScope;
        boolean reassign = binding != null && profile.has(Gate.ASSIGN_PATTERN_BINDING)
                && (profile.steers(Steer.ISSUE_162) || choices.oneIn(2));
        if (reassign) {
            loopScope = new Scope(scope.locals().stream().map(v -> v.name().equals(binding.name())
                    ? new Var(v.name(), v.type(), false, true, false, false) : v).toList(), scope.loopDepth());
        } else {
            String name = freshName(Boolean.FALSE.equals(shadow));
            Type type;
            Expr init;
            if (binding != null && !choices.oneIn(3)) {
                type = binding.type();
                init = new G1Ast.Read(binding);
            } else {
                type = anyType();
                init = expression(type, ctx(declaring(scope, name)), EXPRESSION_DEPTH - 1);
            }
            into.add(new G1Ast.Declare(type, name, init));
            if (isField(name)) features.add(Feature.FIELD_SHADOWED);
            loopScope = scope.with(new Var(name, type, false, true, false, false));
        }
        loop(loopScope, into, updated, !counted && choices.oneIn(2));
        return loopScope;
    }

    /**
     * Steered to #291: the first statement of a default arm block, a local with the name of a field and another type,
     * which the method's result then reads.
     */
    private Scope defaultShadow(Scope scope, List<Stmt> into) {
        Var field = shadowable(scope);
        if (field == null || methodNames.contains(field.name())) return scope;
        // A quarter of the time an integer, so that a later component read of the field does not resolve.
        Type type = choices.oneIn(4) ? BIG : otherType(field.type());
        methodNames.add(field.name());
        into.add(new G1Ast.Declare(type, field.name(), expression(type, ctx(declaring(scope, field.name())), EXPRESSION_DEPTH - 1)));
        features.add(Feature.DEFAULT_BLOCK_SHADOW);
        features.add(Feature.FIELD_SHADOWED);
        defaultShadowed = field.name();
        return scope.with(new Var(field.name(), type, false, false, true, false));
    }

    /** A component read and the variable it starts from. */
    private record Path(Var var, List<String> components) {
        boolean chained() {
            return components.size() > 1;
        }
    }

    /**
     * The component reads of a type: {@code v.c()} on a variable of a record type, and {@code v.c().d()} through a
     * standalone record component.
     */
    private List<Path> paths(Type type, Ctx ctx) {
        var paths = new ArrayList<Path>();
        for (Var var : readable(ctx)) {
            if (var.type().kind != Kind.RECORD) continue;
            for (Component component : var.type().components) {
                if (component.type().fits(type)) paths.add(new Path(var, List.of(component.name())));
                if (component.type().kind == Kind.RECORD) {
                    for (Component inner : component.type().components) {
                        if (inner.type().fits(type)) paths.add(new Path(var, List.of(component.name(), inner.name())));
                    }
                }
            }
        }
        return paths;
    }

    /** A component read of the type, preferring the context's field; an atom if there is none. */
    private Expr access(Type type, Ctx ctx) {
        var paths = paths(type, ctx);
        if (paths.isEmpty()) return atom(type, ctx);
        var preferred = paths.stream().filter(p -> p.var().name().equals(ctx.preferred())).toList();
        var chained = paths.stream().filter(Path::chained).toList();
        Path path = !preferred.isEmpty() ? preferred.get(choices.below(preferred.size()))
                : !chained.isEmpty() && !choices.oneIn(3) ? chained.get(choices.below(chained.size()))
                : paths.get(choices.below(paths.size()));
        features.add(Feature.COMPONENT_ACCESS);
        if (path.chained()) features.add(Feature.CHAINED_ACCESS);
        if (path.var().field()) fieldReads++;
        return new G1Ast.Access(path.var(), path.components());
    }

    /**
     * A variable of the type, a component read, or a constant: a constructor with atom arguments for a record. An
     * atom calls no helper, so atoms stay small (their nesting is bounded by the types').
     */
    private Expr atom(Type type, Ctx ctx) {
        var vars = readable(ctx).stream().filter(v -> v.type().fits(type)).toList();
        var preferred = vars.stream().filter(v -> v.name().equals(ctx.preferred())).toList();
        if (!preferred.isEmpty()) return read(preferred.getFirst());
        int pick = choices.below(4);
        var paths = paths(type, ctx);
        if (pick == 3 && !paths.isEmpty() || pick == 2 && paths.stream().anyMatch(Path::chained)) return access(type, ctx);
        if (!vars.isEmpty() && pick > 0) return read(vars.get(choices.below(vars.size())));
        return switch (type.kind) {
            case BIG -> new G1Ast.IntConst(choices.below(11) - 5);
            case BOOL -> ctx.constant() ? new G1Ast.BoolConst(choices.oneIn(2))
                    : choices.oneIn(3) ? equality(ctx, 1) : comparison(ctx, 1);
            case RECORD, SEALED -> construct(type, ctx, 1);
        };
    }

    private Expr read(Var var) {
        if (var.field()) fieldReads++;
        return new G1Ast.Read(var);
    }
}
