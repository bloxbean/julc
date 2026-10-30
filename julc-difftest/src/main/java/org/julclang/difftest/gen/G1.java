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
 * The second program grammar (ADR-066 D2, G1 r2): user records and sealed interfaces with record variants,
 * {@code static final} fields, helpers, method-level {@code if} and {@code instanceof} patterns, G0's loops
 * with accumulators of any generated type, record and sealed arguments and results, traces and failures. The
 * {@code switch} productions are M3's. The productions build a typed AST ({@link G1Ast}), which {@link G1Printer}
 * prints.
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
    public static final String VERSION = "G1 r2";

    /** The switches of a generation profile (ADR-066 D6), each gating a construct whose defect is open. */
    public enum Gate {
        /** {@code when} guards (#275); M3. */
        GUARDS,
        /** Record patterns (#276); M3. */
        RECORD_PATTERNS,
        /** {@code equals} between distinct record types (#277); M3. */
        CROSS_TYPE_EQUALS,
        /** A record implementing two sealed interfaces at different positions (#279); M3. */
        SHARED_VARIANT,
        /** Reassigning a {@code switch} case-pattern binding inside a loop (#162); M3, historical. */
        ASSIGN_PATTERN_BINDING,
        /** Zero-parameter helpers (#280). */
        ZERO_ARG_HELPERS,
        /** A local in an {@code if} branch inside a loop body shadowing a field of another type (#209). */
        SHADOW_IN_LOOP_BRANCH,
        /** Loop bodies with {@code break} and conditional updates (#254, #255). */
        LOOP_BREAK_SHAPES,
        /**
         * A trace in an operand of {@code compareTo}, directly or through a helper (#146). {@code signum} lowers its
         * receiver the same way; G1 emits none, and probes found no other emitted operation that does.
         */
        TRACE_IN_COMPARISON;

        /** The switches G1 r2 defines but does not implement yet: they change no program until M3. */
        public static final Set<Gate> INERT =
                Collections.unmodifiableSet(EnumSet.of(GUARDS, RECORD_PATTERNS, CROSS_TYPE_EQUALS, SHARED_VARIANT,
                        ASSIGN_PATTERN_BINDING));
    }

    /**
     * A generation profile: {@link #VERSION} and the value of every {@link Gate} (ADR-066 D6). Its text, such as
     * {@code G1 r2 -GUARDS -RECORD_PATTERNS … +ZERO_ARG_HELPERS …}, is printed in every record and replayed.
     */
    public record Profile(Set<Gate> on) {

        /** Every gate off: the campaigns' profile while every gated defect is open. */
        public static final Profile DEFAULT = new Profile(EnumSet.noneOf(Gate.class));
        /** The historical profile that rediscovers #209 (ADR-066 D10.1). */
        public static final Profile REDISCOVER_209 = DEFAULT.with(Gate.SHADOW_IN_LOOP_BRANCH);
        /** The historical profile that rediscovers #280 (ADR-066 D10.1). */
        public static final Profile REDISCOVER_280 = DEFAULT.with(Gate.ZERO_ARG_HELPERS);
        /** The historical profile that rediscovers #146's double evaluation of a traced operand (ADR-066 D10.1). */
        public static final Profile REDISCOVER_146 = DEFAULT.with(Gate.TRACE_IN_COMPARISON);

        public Profile {
            var copy = EnumSet.noneOf(Gate.class);
            copy.addAll(on);
            on = Collections.unmodifiableSet(copy);
        }

        public boolean has(Gate gate) {
            return on.contains(gate);
        }

        public Profile with(Gate gate) {
            var gates = EnumSet.noneOf(Gate.class);
            gates.addAll(on);
            gates.add(gate);
            return new Profile(gates);
        }

        /** {@link #VERSION} followed by {@code +NAME} or {@code -NAME} for every gate, in declaration order. */
        public String text() {
            var text = new StringBuilder(VERSION);
            for (Gate gate : Gate.values()) text.append(' ').append(has(gate) ? '+' : '-').append(gate.name());
            return text.toString();
        }

        /** The profile of a record's {@code profile:} line; it must name this version and every gate once. */
        public static Profile parse(String text) {
            if (!text.startsWith(VERSION + " ")) throw new HarnessException("Not a " + VERSION + " profile: " + text);
            var on = EnumSet.noneOf(Gate.class);
            var seen = EnumSet.noneOf(Gate.class);
            for (String token : text.substring(VERSION.length()).trim().split("\\s+")) {
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
            return new Profile(on);
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
        /** A call of a helper that returns {@code boolean}. */
        BOOLEAN_HELPER_CALL,
        /** A family whose {@code permits} order differs from its variants' declaration order and name order. */
        PERMITS_PERMUTED,
        /** {@code equals} against a value of a sealed family's type. */
        FAMILY_EQUALS,
        /** A local declared in a loop body with a field's name; too rare for a PR quota, so {@code G1GatesTest} checks it. */
        LOOP_LOCAL_SHADOWS_FIELD,
        /** Statistics only. */
        LOOP, BREAK,
        /** Gated constructs (ADR-066 D6): a program under the default profile never contains one. */
        SHADOW_IN_LOOP_BRANCH, ZERO_ARG_HELPER, LOOP_BREAK_SHAPE, TRACE_IN_COMPARISON;

        /**
         * The features with a quota: D8's list without the {@code switch} features, which are M3's, and the
         * sub-features that keep a shape from hiding behind a sibling's quota (review of M2, F63, F70, F71).
         */
        public static final Set<Feature> QUOTA = Collections.unmodifiableSet(EnumSet.range(RECORD, FAMILY_EQUALS));
        /** The marker of each implemented gate's construct. */
        public static final Set<Feature> GATED =
                Collections.unmodifiableSet(EnumSet.of(SHADOW_IN_LOOP_BRANCH, ZERO_ARG_HELPER, LOOP_BREAK_SHAPE,
                        TRACE_IN_COMPARISON));
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
        if (profile.has(Gate.SHADOW_IN_LOOP_BRANCH)) {
            // #209's own shape: two records with the same components in different orders (see mirror).
            Type first = new Type(Kind.RECORD, "R0");
            var names = new ArrayList<>(POOL);
            for (int i = 2 + choices.below(2); i > 0; i--) {
                first.components.add(new Component(names.remove(choices.below(names.size())), choices.oneIn(4) ? BOOL : BIG));
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
                Type sealed = new Type(Kind.SEALED, "S" + families);
                int variants = 1 + choices.below(3);
                var created = new ArrayList<Type>();
                for (int i = 0; i < variants; i++) {
                    Type variant = new Type(Kind.RECORD, (char) ('A' + i) + String.valueOf(families));
                    variant.family = sealed;
                    components(variant);
                    created.add(variant);
                }
                // The permits order, and so the tags, is a permutation of the creation order (ADR-066 D3).
                sealed.variants.addAll(permuted(created));
                sealed.depth = created.stream().mapToInt(t -> t.depth).max().orElse(1);
                types.add(sealed);
                families++;
                features.add(Feature.SEALED);
            } else if (records < 2) {
                Type record = new Type(Kind.RECORD, "R" + records);
                components(record);
                types.add(record);
                records++;
                features.add(Feature.RECORD);
            }
        }
        if (families == 2) features.add(Feature.TWO_FAMILIES);
    }

    /** Zero to three components of {@code BigInteger}, {@code boolean} or an earlier type, names from the pool. */
    private void components(Type record) {
        int count = Math.min(3, choices.below(6));
        if (count == 0) features.add(Feature.ZERO_COMPONENTS);
        var names = new ArrayList<>(POOL);
        int depth = 1;
        // A standalone record often holds an earlier standalone record with components: chained reads need one.
        var inner = types.stream().filter(t -> t.kind == Kind.RECORD && t.depth < 2 && !t.components.isEmpty()).toList();
        boolean nest = record.family == null && !inner.isEmpty() && count > 0 && choices.oneIn(2);
        for (int i = 0; i < count; i++) {
            var candidates = new ArrayList<>(List.of(BIG, BIG, BOOL));
            if (i == 0 && nest) candidates = new ArrayList<>(inner);
            for (Type type : i == 0 && nest ? List.<Type>of() : types) {
                if (type.depth >= 2) continue;
                candidates.addAll(List.of(type, type));
                // A standalone record inside a record gives chained reads (CHAINED_ACCESS).
                if (type.kind == Kind.RECORD) candidates.addAll(List.of(type, type));
            }
            Type type = candidates.get(choices.below(candidates.size()));
            if (type.kind == Kind.RECORD) features.add(Feature.NESTED_RECORD);
            if (type.kind == Kind.SEALED) features.add(Feature.SEALED_COMPONENT);
            if (type.isValue()) depth = Math.max(depth, type.depth + 1);
            String name = names.remove(choices.below(names.size()));
            record.components.add(new Component(name, type));
        }
        record.depth = depth;
    }

    /**
     * Under SHADOW_IN_LOOP_BRANCH, #209's own shape: a second record with the first one's components in another
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
        // Under SHADOW_IN_LOOP_BRANCH, #209 needs a field of a declared type to shadow.
        boolean shadowing = profile.has(Gate.SHADOW_IN_LOOP_BRANCH) && !valueTypes().isEmpty();
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

    /** The accumulator #209's composition last assigned from a field read, which the result then prefers. */
    private String shadowRead;

    /** How many field reads expressions have generated, to tell a field initialized from another. */
    private int fieldReads;

    /** Zero to two helpers of one to three parameters (zero under ZERO_ARG_HELPERS), each calling only earlier ones. */
    private void declareHelpers() {
        var names = new ArrayList<>(POOL);
        for (int n = choices.below(3); n > 0; n--) {
            String name = names.remove(choices.below(names.size()));
            int arity = profile.has(Gate.ZERO_ARG_HELPERS) && choices.oneIn(2) ? 0 : 1 + choices.below(3);
            var parameters = new ArrayList<Var>();
            var parameterNames = new ArrayList<>(POOL);
            for (int i = 0; i < arity; i++) {
                parameters.add(new Var(parameterNames.remove(choices.below(parameterNames.size())), anyType(), false, false,
                        true, false));
            }
            // Boolean helpers are called from conditions (BOOLEAN_HELPER_CALL).
            Type result = choices.oneIn(2) ? BOOL : anyType();
            var scope = new Scope(parameters, 0);
            var ctx = new Ctx(scope, List.copyOf(helpers), false, false, null, false);
            int traced = traceSites;
            Expr body = expression(result, ctx, EXPRESSION_DEPTH);
            if (arity == 0) {
                features.add(Feature.ZERO_ARG_HELPER);
                // #280 shows only when the body does something observable at script start.
                if (result == BIG && !choices.oneIn(4)) body = trace(body);
            }
            helperDeclarations.add(new G1Ast.Helper(name, parameters, result, body));
            helpers.add(new Helper(name, parameters, result, traceSites > traced));
        }
    }

    // ---------------------------------------------------------------- the entry method

    private G1Ast.Method method() {
        methodNames.clear();
        methodNames.addAll(List.of("a", "b", "xs"));
        Type result = !types.isEmpty() && !choices.oneIn(3) ? declaredType() : anyType();
        // A variant typed as itself, with a nonzero tag, as the result a quarter of the time (VARIANT_RESULT).
        var tagged = valueTypes().stream().filter(t -> t.family != null && t.tag() != 0).toList();
        if (!tagged.isEmpty() && choices.oneIn(4)) result = tagged.get(choices.below(tagged.size()));
        if (result.kind == Kind.RECORD && result.family == null) features.add(Feature.RECORD_RESULT);
        if (result.kind == Kind.SEALED) features.add(Feature.SEALED_RESULT);
        if (result.family != null && result.tag() != 0) features.add(Feature.VARIANT_RESULT);
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
        boolean shadowing = profile.has(Gate.SHADOW_IN_LOOP_BRANCH);
        for (int n = choices.below(5) + (shadowing ? 1 : 0); n > 0; n--) {
            int kind = choices.below(8);
            // Under SHADOW_IN_LOOP_BRANCH, more loops: #209 lives in a loop body.
            if (profile.has(Gate.SHADOW_IN_LOOP_BRANCH) && kind != 1 && choices.oneIn(2)) kind = 1;
            switch (kind) {
                case 0 -> scope = declaration(scope, body, true, null);
                case 1, 2, 3 -> {
                    if (scope.assignable().isEmpty()) scope = accumulator(scope, body);
                    if (scope.assignable().size() < 2 && !choices.oneIn(3)) scope = accumulator(scope, body);
                    loop(scope, body, updated, choices.oneIn(2));
                }
                case 4 -> earlyReturn(scope, body, result);
                case 5, 6 -> scope = instanceOf(scope, body, result);
                default -> scope = declaration(scope, body, false, null);
            }
        }
        if (choices.oneIn(3)) {
            features.add(Feature.METHOD_LEVEL_IF);
            Expr test = condition(ctx(scope), EXPRESSION_DEPTH);
            var then = branch(scope, result);
            body.add(new G1Ast.If(test, then, branch(scope, result)));
        } else {
            body.add(new G1Ast.Return(expression(result, ctx(scope).preferring(shadowRead), EXPRESSION_DEPTH)));
        }
        return new G1Ast.Method(result, parameters, body);
    }

    private Ctx ctx(Scope scope) {
        return new Ctx(scope, helpers, false, false, null, false);
    }

    /** {@code if (c) { locals; return e; }} at method level. */
    private void earlyReturn(Scope scope, List<Stmt> into, Type result) {
        features.add(Feature.METHOD_LEVEL_IF);
        Expr test = condition(ctx(scope), EXPRESSION_DEPTH);
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
        // A variant accumulator a quarter of the time (VARIANT_ACCUMULATOR).
        var variants = valueTypes().stream().filter(t -> t.family != null).toList();
        if (!variants.isEmpty() && choices.oneIn(4)) type = variants.get(choices.below(variants.size()));
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

    private void loop(Scope scope, List<Stmt> into, List<String> updated, boolean forEach) {
        features.add(Feature.LOOP);
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
            body(body.with(new Var(counter, BIG, true, false, false, false)), statements, updated, true);
            into.add(new G1Ast.While(counter, bound, statements));
        }
    }

    private void body(Scope scope, List<Stmt> into, List<String> updated, boolean counted) {
        var assigned = new ArrayList<String>();
        boolean hasBreak = false;
        // A body with break contains no loop (reject-nested-loop-in-break-loop). Loop bodies with break and
        // conditional updates are gated (LOOP_BREAK_SHAPES, #254, #255).
        boolean breakAware = choices.oneIn(3) && !profile.has(Gate.SHADOW_IN_LOOP_BRANCH);
        boolean conditionalUpdates = !breakAware || profile.has(Gate.LOOP_BREAK_SHAPES);
        boolean hasIfUpdate = false;
        for (int n = 1 + choices.below(3); n > 0; n--) {
            int kind = choices.below(7);
            if (kind <= 1 && profile.has(Gate.SHADOW_IN_LOOP_BRANCH) && choices.oneIn(2)) kind = 2;
            if (kind == 3 && !breakAware) kind = 0;
            if (kind == 2 && !conditionalUpdates) kind = 1;
            if (kind == 5 && (breakAware || scope.loopDepth() >= MAX_LOOP_DEPTH)) kind = 0;
            switch (kind) {
                case 0, 1 -> assignment(scope, into, assigned, null);
                case 2 -> {
                    hasIfUpdate = true;
                    ifUpdate(scope, into, assigned);
                }
                case 3 -> {
                    hasBreak = true;
                    features.add(Feature.BREAK);
                    into.add(new G1Ast.BreakIf(condition(ctx(scope), EXPRESSION_DEPTH)));
                }
                case 5 -> loop(scope, into, updated, choices.oneIn(2));
                default -> {
                    scope = loopLocal(scope, into);
                    if (isField(scope.locals().getLast().name())) features.add(Feature.LOOP_LOCAL_SHADOWS_FIELD);
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
        Expr test = condition(ctx(scope), EXPRESSION_DEPTH);
        var then = new ArrayList<Stmt>();
        Scope branch = scope;
        String shadowed = null;
        Var field = profile.has(Gate.SHADOW_IN_LOOP_BRANCH) ? shadowable(scope) : null;
        if (field != null || choices.oneIn(3)) {
            if (field != null) {
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
        into.add(new G1Ast.Assign(target.name(), expression(target.type(), ctx(scope).preferring(preferred), EXPRESSION_DEPTH)));
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
        return switch (choices.below(12)) {
            case 0 -> new G1Ast.Arith(integer(ctx, depth - 1), "add", integer(ctx, depth - 1));
            case 1 -> new G1Ast.Arith(integer(ctx, depth - 1), "subtract", integer(ctx, depth - 1));
            case 2 -> new G1Ast.Scale(integer(ctx, depth - 1), 1 + choices.below(3));
            case 3 -> ctx.constant() ? atom(BIG, ctx) : ternary(BIG, ctx, depth);
            case 4, 10 -> access(BIG, ctx);
            case 5, 9 -> call(BIG, ctx, depth);
            case 6, 11 -> ctx.constant() || ctx.traceFree() ? atom(BIG, ctx) : trace(integer(ctx, depth - 1));
            case 7 -> !ctx.constant() && (ctx.conditional() || choices.oneIn(8))
                    ? failure(integer(ctx, depth - 1)) : atom(BIG, ctx);
            default -> atom(BIG, ctx);
        };
    }

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
        if (depth == 0 || choices.below(3) == 0) return atom(BOOL, ctx);
        return switch (choices.below(10)) {
            case 0, 1 -> comparison(ctx, depth);
            case 2 -> new G1Ast.Logic(condition(ctx, depth - 1), "&&", condition(ctx.conditionally(), depth - 1));
            case 3 -> new G1Ast.Logic(condition(ctx, depth - 1), "||", condition(ctx.conditionally(), depth - 1));
            case 4 -> new G1Ast.Not(condition(ctx, depth - 1));
            case 5, 6 -> equality(ctx, depth);
            case 7, 8 -> call(BOOL, ctx, depth);
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
        Expr left = integer(operands, depth - 1);
        if (profile.has(Gate.TRACE_IN_COMPARISON) && !ctx.constant() && !ctx.traceFree() && choices.oneIn(3)) left = trace(left);
        Expr right = integer(operands, depth - 1);
        var comparison = new G1Ast.Compare(left, right, COMPARISONS[choices.below(COMPARISONS.length)]);
        if (traceSites > traced) features.add(Feature.TRACE_IN_COMPARISON);
        return comparison;
    }

    /** {@code r.equals(e)}: a variable of a declared type against a value of the same type, or of its family. */
    private Expr equality(Ctx ctx, int depth) {
        var receivers = readable(ctx).stream().filter(v -> v.type().isValue()).toList();
        if (receivers.isEmpty()) return comparison(ctx, depth);
        var familial = receivers.stream().filter(v -> v.type().kind == Kind.SEALED || v.type().family != null).toList();
        if (!familial.isEmpty() && choices.oneIn(2)) receivers = familial;
        Var receiver = receivers.get(choices.below(receivers.size()));
        Type type = receiver.type().family != null && !choices.oneIn(3) ? receiver.type().family : receiver.type();
        features.add(Feature.RECORD_EQUALS);
        if (type.kind == Kind.SEALED) features.add(Feature.FAMILY_EQUALS);
        Expr read = read(receiver);
        return new G1Ast.Equals(read, value(type, ctx, depth - 1));
    }

    private Expr value(Type type, Ctx ctx, int depth) {
        if (depth == 0 || choices.below(3) == 0) return atom(type, ctx);
        return switch (choices.below(5)) {
            case 0 -> construct(type, ctx, depth);
            case 1 -> access(type, ctx);
            case 2 -> ctx.constant() ? construct(type, ctx, depth) : ternary(type, ctx, depth);
            case 3 -> call(type, ctx, depth);
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
        Expr test = condition(ctx, depth - 1);
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
            case BOOL -> ctx.constant() ? new G1Ast.BoolConst(choices.oneIn(2)) : comparison(ctx, 1);
            case RECORD, SEALED -> construct(type, ctx, 1);
        };
    }

    private Expr read(Var var) {
        if (var.field()) fieldReads++;
        return new G1Ast.Read(var);
    }
}
