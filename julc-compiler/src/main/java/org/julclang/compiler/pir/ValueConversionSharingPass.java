package org.julclang.compiler.pir;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.debug.PirDebugProvenance;
import org.julclang.core.BuiltinSemantics;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.recursion.Trampoline;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * ADR-042 (O8) and ADR-044 (O15): share one strict conversion of a variable across a scope.
 *
 * <p>A <em>unit</em> is a closed, deterministic computation of a variable {@code x}:
 * <ul>
 *   <li>O8, {@link Shape#VALUE_CONVERSION}: {@code [(builtin unValueData) x]} or {@code [w x]}
 *       where {@code w} is a once-bound library wrapper of exactly that shape
 *       ({@code NativeValueLib.fromData});</li>
 *   <li>O15, {@link Shape#FIELD_CHAIN}: a record field projection
 *       {@code D(headList(tailList^k(sndPair(unConstrData(x)))))} where {@code D} is exactly one
 *       {@link PirHelpers#wrapDecode} arm ({@link Arm}); the raw chain inside a decode belongs to
 *       that unit and is never a unit of its own;</li>
 *   <li>O15, {@link Shape#FIELDS_PREFIX}: the fields list {@code sndPair(unConstrData(x))}.</li>
 * </ul>
 * Two occurrences are the same unit when their {@link Key} is equal: the root variable's name,
 * the shape, and for field chains the depth and decode arm. Nothing else is inspected.
 *
 * <p>For a scope {@code S} in which a unit {@code U(x)} occurs at least twice without {@code x}
 * being rebound, the pass emits {@code let v = U(x) in S[U(x) := v]} exactly when {@code U(x)}
 * is the first non-trivial evaluation on every path through {@code S}. The shared unit
 * therefore ran first on every path already: no result, trace, failure point or failure text
 * changes, only the budget of the later occurrences and the script size. A path that reaches
 * no later occurrence (an untaken branch, an empty loop) pays the binding alone: one lambda,
 * one application and one variable lookup.
 *
 * <p>Trivial evaluations are variable lookups, constants, lambdas, and under-saturated builtin
 * or once-bound {@code Let} lambda applications whose arguments are trivial. None of these can
 * fail, trace or run user code before the unit: the CEK machine collects builtin arguments
 * unchecked until saturation, and applying a lambda chain to fewer arguments than its depth
 * only builds a closure. Every other node (saturated calls, traces, errors, matches, recursive
 * bindings, non-trivial conditions) blocks the proof.
 *
 * <p>Rounds. Field chains are shared first, to a fixed point (a shared chain is a variable and
 * may root further chains, {@code b.inner().x()} then {@code b.inner().y()}); then the fields
 * prefix over whatever distinct chains remain, including those inside the bindings just
 * inserted; then native Value conversions, which may now apply to a shared projection. Each
 * round re-reads the current term; fresh names ({@code #field-N}, {@code #fields-N},
 * {@code #value-N}) stay unique across rounds. Two refinements apply to every class: the body
 * of a single recursive binding of a lambda can lead (see {@link #leadingUnit}), and units
 * inside a binding the UPLC optimiser will drop are ignored (see {@link #isDead}).
 *
 * <p>Gate: exact PV11 target and a safe optimization level; the Value class additionally needs
 * {@link ProtocolCapability#VALUE_CONSTANTS}. Each class can be switched off on its own
 * ({@link CompilationContext#ruleEnabled}); provenance {@link #RULE} is recorded when the Value
 * class fires and {@link #PROJECTION_RULE} when either projection class fires. NONE and
 * BASELINE keep their historical bytes.
 */
public final class ValueConversionSharingPass {
    public static final String RULE = "pv11.o8.value-sharing";
    public static final String PROJECTION_RULE = "pv11.o15.projection-sharing";

    private static final PirType NATIVE_VALUE = new PirType.NativeValueType();
    private static final PirType DATA = new PirType.DataType();
    private static final PirType FIELDS = new PirType.ListType(DATA);
    private static final PirTerm ONE = new PirTerm.Const(Constant.integer(BigInteger.ONE));

    /** The unit classes, one per sharing round, with the prefix of the names they bind. */
    enum Shape {
        FIELD_CHAIN("#field-"),
        FIELDS_PREFIX("#fields-"),
        VALUE_CONVERSION("#value-");

        final String namePrefix;

        Shape(String namePrefix) {
            this.namePrefix = namePrefix;
        }
    }

    /** The decode wrapped around a raw field, exactly as {@link PirHelpers#wrapDecode} emits it. */
    enum Arm {
        RAW(DATA),
        INTEGER(new PirType.IntegerType()),
        BYTES(new PirType.ByteStringType()),
        LIST(FIELDS),
        MAP(new PirType.MapType(DATA, DATA)),
        BOOL(new PirType.BoolType()),
        STRING(new PirType.StringType());

        final PirType type;

        Arm(PirType type) {
            this.type = type;
        }
    }

    /** One unit class; equal keys are the same deterministic computation of {@code variable}. */
    record Key(String variable, Shape shape, int depth, Arm arm) {
        PirType type() {
            return switch (shape) {
                case FIELD_CHAIN -> arm.type;
                case FIELDS_PREFIX -> FIELDS;
                case VALUE_CONVERSION -> NATIVE_VALUE;
            };
        }
    }

    private final CompilationContext context;
    private final IdentityHashMap<PirTerm, SourceLocation> positions = new IdentityHashMap<>();
    private final Set<String> names = new HashSet<>();
    private final Map<String, Integer> nextName = new HashMap<>();
    private final Map<String, Integer> binderCounts = new HashMap<>();
    private final Map<String, PirTerm> letValues = new LinkedHashMap<>();
    /** Lambda depth of every once-bound {@code Let} whose value is a lambda chain. */
    private final Map<String, Integer> lambdaDepths = new HashMap<>();
    /** Once-bound wrappers of the shape {@code (lam p [(builtin unValueData) p])}. */
    private final Set<String> aliases = new HashSet<>();
    /** Units occurring at least twice anywhere in the program, in first-occurrence order. */
    private final Set<Key> candidates = new LinkedHashSet<>();
    /** Memo for {@link #isDead}: bindings the UPLC optimiser will drop and this pass therefore ignores. */
    private final IdentityHashMap<PirTerm, Boolean> deadBindings = new IdentityHashMap<>();
    /** Memo for {@link #liveFree}: the free variables of a sub-term once its dead bindings are dropped. */
    private final IdentityHashMap<PirTerm, Set<String>> liveFreeVariables = new IdentityHashMap<>();
    private Shape shape;
    private boolean changed;
    private boolean valueApplied;
    private boolean projectionApplied;
    private final PirDebugProvenance debugProvenance;

    public ValueConversionSharingPass(CompilationContext context, Map<PirTerm, SourceLocation> positions) {
        this(context, positions, null);
    }

    public ValueConversionSharingPass(CompilationContext context, Map<PirTerm, SourceLocation> positions,
                                      PirDebugProvenance debugProvenance) {
        this.context = context;
        this.debugProvenance = debugProvenance;
        if (positions != null) this.positions.putAll(positions);
    }

    public record Result(PirTerm term, Map<PirTerm, SourceLocation> positions) {}

    public Result lower(PirTerm term) {
        if (!context.target().equals(CompilerTarget.PLUTUS_V3_PV11)
                || !context.optimizationLevel().pv11SafeRulesEnabled()) {
            return new Result(term, positions);
        }
        boolean projections = context.ruleEnabled(PROJECTION_RULE);
        boolean values = context.supports(ProtocolCapability.VALUE_CONSTANTS) && context.ruleEnabled(RULE);
        if (!projections && !values) return new Result(term, positions);

        var rewritten = term;
        if (projections) {
            do {
                changed = false;
                rewritten = round(rewritten, Shape.FIELD_CHAIN);
            } while (changed);
            rewritten = round(rewritten, Shape.FIELDS_PREFIX);
        }
        if (values) rewritten = round(rewritten, Shape.VALUE_CONVERSION);
        if (projectionApplied) context.recordOptimizationRule(PROJECTION_RULE);
        if (valueApplied) context.recordOptimizationRule(RULE);
        return new Result(rewritten, positions);
    }

    /** One sharing round over the current term for one unit class. */
    private PirTerm round(PirTerm term, Shape shape) {
        this.shape = shape;
        binderCounts.clear();
        letValues.clear();
        lambdaDepths.clear();
        aliases.clear();
        candidates.clear();
        collectBinders(term);
        letValues.forEach((name, value) -> {
            if (binderCounts.get(name) != 1) return;
            int depth = lambdaDepth(value);
            if (depth > 0) lambdaDepths.put(name, depth);
            if (isConversionWrapper(value)) aliases.add(name);
        });
        var sites = new LinkedHashMap<Key, Integer>();
        collectSites(term, sites);
        sites.forEach((key, count) -> { if (count >= 2) candidates.add(key); });
        if (candidates.isEmpty()) return term;
        return rewrite(term).run();
    }

    /**
     * Pre-order: share at the outermost scope whose first non-trivial evaluation is the unit. Stack-safe, as are the
     * walks below: a program's term nests as deeply as it has methods and statements (issue #327, ADR-074).
     */
    private Trampoline<PirTerm> rewrite(PirTerm term) {
        return Trampoline.defer(() -> {
            for (var key : candidates) {
                var lead = leadingUnit(term, key);
                if (lead == null || countUnits(term, key) < 2) continue;
                String shared = fresh(key.shape().namePrefix);
                var body = mapUnits(term, key, use -> remember(use, new PirTerm.Var(shared, key.type()))).run();
                changed = true;
                if (key.shape() == Shape.VALUE_CONVERSION) valueApplied = true; else projectionApplied = true;
                // The inserted value is non-trivial, so a later candidate can only be shared below it:
                // evaluation order between units is preserved.
                return rewrite(body).map(rewritten -> remember(term, new PirTerm.Let(shared, lead, rewritten)));
            }
            Trampoline<PirTerm> rebuilt = switch (term) {
                case PirTerm.Let let when isDead(let) ->
                        rewrite(let.body()).map(body -> new PirTerm.Let(let.name(), let.value(), body));
                case PirTerm.LetRec rec when isDead(rec) ->
                        rewrite(rec.body()).map(body -> new PirTerm.LetRec(rec.bindings(), body));
                default -> PirHelpers.traverseChildren(term, this::rewrite);
            };
            return rebuilt.map(replacement -> remember(term, replacement));
        });
    }

    /**
     * A binding of a lambda whose name is not referenced by the live part of its body. The UPLC
     * optimiser drops such a binding (a lambda, or the fixpoint of a lambda, is pure) after
     * dropping the dead bindings inside the body, so units inside it are neither counted nor
     * rewritten: sharing there would only change the bytes of source-map builds and record
     * provenance for code that is not in the artifact. Every library method the program does not
     * call, directly or through another live method, is bound this way.
     */
    private boolean isDead(PirTerm term) {
        return dead(term).run();
    }

    private Trampoline<Boolean> dead(PirTerm term) {
        return Trampoline.defer(() -> {
            var known = deadBindings.get(term);
            if (known != null) return Trampoline.done(known);
            Trampoline<Boolean> dead = switch (term) {
                case PirTerm.Let let -> let.value() instanceof PirTerm.Lam
                        ? liveFree(let.body()).map(free -> !free.contains(let.name()))
                        : Trampoline.done(false);
                case PirTerm.LetRec rec -> rec.bindings().size() == 1
                        && rec.bindings().getFirst().value() instanceof PirTerm.Lam
                        ? liveFree(rec.body()).map(free -> !free.contains(rec.bindings().getFirst().name()))
                        : Trampoline.done(false);
                default -> Trampoline.done(false);
            };
            return dead.map(result -> {
                deadBindings.put(term, result);
                return result;
            });
        });
    }

    /** Free variables of {@code term} with its dead bindings dropped (the optimiser's view of the term). */
    private Trampoline<Set<String>> liveFree(PirTerm term) {
        return Trampoline.defer(() -> {
            var known = liveFreeVariables.get(term);
            if (known != null) return Trampoline.done(known);
            var free = new HashSet<String>();
            Trampoline<Void> filled = switch (term) {
                case PirTerm.Var v -> {
                    free.add(v.name());
                    yield Trampoline.done(null);
                }
                case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> Trampoline.done(null);
                case PirTerm.Lam l -> liveFree(l.body()).map(body -> {
                    free.addAll(body);
                    free.remove(l.param());
                    return null;
                });
                case PirTerm.Let l -> addLiveFree(free, l.body()).then(ignored -> dead(l)).then(dead -> dead
                        ? Trampoline.<Void>done(null)
                        : liveFree(l.value()).map(value -> {
                            free.remove(l.name());
                            free.addAll(value);
                            return null;
                        }));
                case PirTerm.LetRec r -> addLiveFree(free, r.body()).then(ignored -> dead(r)).then(dead -> dead
                        ? Trampoline.<Void>done(null)
                        : addLiveFree(free, r.bindings().stream().map(PirTerm.Binding::value).toList()).map(ignored -> {
                            r.bindings().forEach(b -> free.remove(b.name()));
                            return null;
                        }));
                case PirTerm.App a -> addLiveFree(free, List.of(a.function(), a.argument()));
                case PirTerm.IfThenElse i -> addLiveFree(free, List.of(i.cond(), i.thenBranch(), i.elseBranch()));
                case PirTerm.Trace t -> addLiveFree(free, List.of(t.message(), t.body()));
                case PirTerm.DataConstr c -> addLiveFree(free, c.fields());
                case PirTerm.IntegerCase c -> addLiveFree(free, c.scrutinee()).then(ignored -> addLiveFree(free, c.branches()));
                case PirTerm.ListMatch m -> addLiveFree(free, List.of(m.scrutinee(), m.nilBranch()))
                        .then(ignored -> liveFree(m.consBranch())).map(consFree -> {
                            var cons = new HashSet<>(consFree);
                            cons.remove(m.headName());
                            cons.remove(m.tailName());
                            free.addAll(cons);
                            return null;
                        });
                case PirTerm.PairMatch m -> addLiveFree(free, m.scrutinee())
                        .then(ignored -> liveFree(m.body())).map(bodyFree -> {
                            var body = new HashSet<>(bodyFree);
                            body.remove(m.firstName());
                            body.remove(m.secondName());
                            free.addAll(body);
                            return null;
                        });
                case PirTerm.DataMatch m -> addLiveFree(free, m.scrutinee()).then(ignored ->
                        Trampoline.each(m.branches(), b -> liveFree(b.body()).map(bodyFree -> {
                            var body = new HashSet<>(bodyFree);
                            b.bindings().forEach(body::remove);
                            if (b.patternVar() != null) body.remove(b.patternVar());
                            free.addAll(body);
                            return (Void) null;
                        }))).map(ignored -> null);
            };
            return filled.map(ignored -> {
                var result = Set.copyOf(free);
                liveFreeVariables.put(term, result);
                return result;
            });
        });
    }

    private Trampoline<Void> addLiveFree(Set<String> free, PirTerm term) {
        return liveFree(term).map(found -> {
            free.addAll(found);
            return null;
        });
    }

    private Trampoline<Void> addLiveFree(Set<String> free, List<PirTerm> terms) {
        return Trampoline.each(terms, term -> addLiveFree(free, term)).map(ignored -> null);
    }

    /**
     * The unique occurrence of {@code key} that every path through {@code term} evaluates
     * before any other non-trivial step, or null. Function position evaluates before argument
     * position; a {@code Let} value before its body; a condition or scrutinee before its
     * branches; the body of a single recursive binding of a lambda after that binding. Branch
     * bodies, lambdas, traces, errors, other recursive bindings and constructor builds never
     * lead.
     */
    private PirTerm leadingUnit(PirTerm term, Key key) {
        return leading(term, key).run();
    }

    private Trampoline<PirTerm> leading(PirTerm term, Key key) {
        return Trampoline.defer(() -> {
            var found = keyOf(term, key.shape());
            if (key.equals(found)) return Trampoline.done(term);
            // A unit of another key is a leaf: the raw chain inside a decode belongs to the decode
            // and never leads on its own, exactly as the collector sees it.
            if (found != null) return Trampoline.done(null);
            return switch (term) {
                case PirTerm.App app -> leading(app.function(), key).then(inFunction -> {
                    if (inFunction != null) return Trampoline.done(inFunction);
                    return isTrivial(app.function()) ? leading(app.argument(), key) : Trampoline.done(null);
                });
                case PirTerm.Let let -> leading(let.value(), key).then(inValue -> {
                    if (inValue != null) return Trampoline.done(inValue);
                    // The shared binding is inserted above this Let, so the Let must not bind a free
                    // variable of the unit: neither the variable itself nor a wrapper alias (a
                    // wrapper-spelled conversion [w x] is free in both x and w).
                    return isTrivial(let.value()) && !let.name().equals(key.variable()) && !aliases.contains(let.name())
                            ? leading(let.body(), key) : Trampoline.done(null);
                });
                // A single recursive binding of a lambda only builds its closure (Z applied to a
                // lambda: a fixed number of beta steps over values, the fact the UPLC optimiser
                // relies on to drop an unused one), so its body can lead when the binding does not
                // capture a free variable of the unit. The per-site JulcList.get lowering wraps every
                // site in such a binding. The multi-binding (Bekić) lowering is not claimed.
                case PirTerm.LetRec rec -> rec.bindings().size() == 1
                        && rec.bindings().getFirst().value() instanceof PirTerm.Lam
                        && !rec.bindings().getFirst().name().equals(key.variable())
                        && !aliases.contains(rec.bindings().getFirst().name())
                        ? leading(rec.body(), key) : Trampoline.done(null);
                // Branches are exclusive: a unit leading in both arms of a conditional would be
                // evaluated once per path either way, so sharing it above the conditional only adds
                // a binding. Only the condition (or a scrutinee) can lead.
                case PirTerm.IfThenElse ite -> leading(ite.cond(), key);
                case PirTerm.DataMatch match -> leading(match.scrutinee(), key);
                case PirTerm.ListMatch match -> leading(match.scrutinee(), key);
                case PirTerm.PairMatch match -> leading(match.scrutinee(), key);
                case PirTerm.IntegerCase c -> leading(c.scrutinee(), key);
                case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Lam _,
                     PirTerm.Trace _, PirTerm.Error _, PirTerm.DataConstr _ -> Trampoline.done(null);
            };
        });
    }

    /** Evaluation that cannot fail, trace, or run user code. */
    private boolean isTrivial(PirTerm term) {
        return trivial(term).run();
    }

    private Trampoline<Boolean> trivial(PirTerm term) {
        return Trampoline.defer(() -> switch (term) {
            case PirTerm.Var _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Lam _ -> Trampoline.done(true);
            case PirTerm.App app -> unsaturatedSpine(app);
            default -> Trampoline.done(false);
        });
    }

    /** A builtin or once-bound lambda chain applied to fewer trivial arguments than its arity. */
    private Trampoline<Boolean> unsaturatedSpine(PirTerm.App app) {
        var arguments = new ArrayList<PirTerm>();
        PirTerm head = app;
        while (head instanceof PirTerm.App spine) {
            arguments.add(spine.argument());
            head = spine.function();
        }
        PirTerm function = head;
        return Trampoline.all(arguments, this::trivial).map(allTrivial -> {
            if (!allTrivial) return false;
            Integer arity = switch (function) {
                case PirTerm.Builtin builtin -> {
                    var sig = BuiltinSemantics.find(builtin.fun());
                    yield sig == null ? null : sig.valueArity();
                }
                case PirTerm.Var var -> lambdaDepths.get(var.name());
                default -> null;
            };
            return arity != null && arguments.size() < arity;
        });
    }

    /** The key of {@code term} if it is a unit of {@code shape}, else null. */
    private Key keyOf(PirTerm term, Shape shape) {
        return switch (shape) {
            case VALUE_CONVERSION -> term instanceof PirTerm.App app
                    && app.argument() instanceof PirTerm.Var argument
                    && (isBuiltin(app.function(), DefaultFun.UnValueData)
                        || app.function() instanceof PirTerm.Var function && aliases.contains(function.name()))
                    ? new Key(argument.name(), shape, 0, Arm.RAW) : null;
            case FIELDS_PREFIX -> {
                String root = prefixRoot(term);
                yield root == null ? null : new Key(root, shape, 0, Arm.RAW);
            }
            case FIELD_CHAIN -> fieldChain(term);
        };
    }

    /** {@code x} when {@code term} is {@code sndPair(unConstrData(x))}, else null. */
    private static String prefixRoot(PirTerm term) {
        return term instanceof PirTerm.App fields && isBuiltin(fields.function(), DefaultFun.SndPair)
                && fields.argument() instanceof PirTerm.App constr && isBuiltin(constr.function(), DefaultFun.UnConstrData)
                && constr.argument() instanceof PirTerm.Var root
                ? root.name() : null;
    }

    /**
     * A field chain with its decode arm, matched outermost arm first so that the raw chain
     * inside a decode is part of that unit (an integer field is shared as {@code unIData(...)},
     * never as the raw {@code headList(...)} it wraps).
     */
    private static Key fieldChain(PirTerm term) {
        if (term instanceof PirTerm.App equals && equals.argument().equals(ONE)
                && equals.function() instanceof PirTerm.App tag && isBuiltin(tag.function(), DefaultFun.EqualsInteger)
                && tag.argument() instanceof PirTerm.App first && isBuiltin(first.function(), DefaultFun.FstPair)
                && first.argument() instanceof PirTerm.App constr && isBuiltin(constr.function(), DefaultFun.UnConstrData)) {
            var key = rawChain(constr.argument(), Arm.BOOL);
            if (key != null) return key;
        }
        if (term instanceof PirTerm.App decode && isBuiltin(decode.function(), DefaultFun.DecodeUtf8)
                && decode.argument() instanceof PirTerm.App bytes && isBuiltin(bytes.function(), DefaultFun.UnBData)) {
            var key = rawChain(bytes.argument(), Arm.STRING);
            if (key != null) return key;
        }
        if (term instanceof PirTerm.App app && app.function() instanceof PirTerm.Builtin builtin) {
            Arm arm = switch (builtin.fun()) {
                case UnIData -> Arm.INTEGER;
                case UnBData -> Arm.BYTES;
                case UnListData -> Arm.LIST;
                case UnMapData -> Arm.MAP;
                default -> null;
            };
            if (arm != null) {
                var key = rawChain(app.argument(), arm);
                if (key != null) return key;
            }
        }
        return rawChain(term, Arm.RAW);
    }

    /** {@code headList(tailList^k(sndPair(unConstrData(x))))} as a key of depth {@code k}, else null. */
    private static Key rawChain(PirTerm term, Arm arm) {
        if (!(term instanceof PirTerm.App head && isBuiltin(head.function(), DefaultFun.HeadList))) return null;
        int depth = 0;
        PirTerm current = head.argument();
        while (current instanceof PirTerm.App tail && isBuiltin(tail.function(), DefaultFun.TailList)) {
            depth++;
            current = tail.argument();
        }
        String root = prefixRoot(current);
        return root == null ? null : new Key(root, Shape.FIELD_CHAIN, depth, arm);
    }

    private static boolean isBuiltin(PirTerm term, DefaultFun fun) {
        return term instanceof PirTerm.Builtin builtin && builtin.fun() == fun;
    }

    private static boolean isConversionWrapper(PirTerm value) {
        return value instanceof PirTerm.Lam lam
                && lam.body() instanceof PirTerm.App app
                && isBuiltin(app.function(), DefaultFun.UnValueData)
                && app.argument() instanceof PirTerm.Var argument && argument.name().equals(lam.param());
    }

    private static int lambdaDepth(PirTerm value) {
        int depth = 0;
        while (value instanceof PirTerm.Lam lam) { depth++; value = lam.body(); }
        return depth;
    }

    private int countUnits(PirTerm term, Key key) {
        int[] count = {0};
        mapUnits(term, key, use -> { count[0]++; return use; }).run();
        return count[0];
    }

    /**
     * Visit every occurrence of {@code key} under this lexical binding of its variable; a
     * matched unit is a leaf, a unit of another key of the same class is an opaque leaf (the
     * raw chain inside a decode is part of the decode), and rebindings are opaque.
     */
    private Trampoline<PirTerm> mapUnits(PirTerm term, Key key, UnaryOperator<PirTerm> use) {
        return Trampoline.defer(() -> {
            var found = keyOf(term, key.shape());
            if (key.equals(found)) return Trampoline.done(use.apply(term));
            if (found != null) return Trampoline.done(term);
            String variable = key.variable();
            Trampoline<PirTerm> result = switch (term) {
                case PirTerm.Lam lam when lam.param().equals(variable) -> Trampoline.done(term);
                case PirTerm.Let let when isDead(let) -> let.name().equals(variable) ? Trampoline.done(term)
                        : mapUnits(let.body(), key, use).map(body -> new PirTerm.Let(let.name(), let.value(), body));
                case PirTerm.Let let when let.name().equals(variable) ->
                        mapUnits(let.value(), key, use).map(value -> new PirTerm.Let(let.name(), value, let.body()));
                case PirTerm.LetRec rec when rec.bindings().stream().anyMatch(b -> b.name().equals(variable)) ->
                        Trampoline.done(term);
                case PirTerm.LetRec rec when isDead(rec) ->
                        mapUnits(rec.body(), key, use).map(body -> new PirTerm.LetRec(rec.bindings(), body));
                case PirTerm.ListMatch match -> mapUnits(match.scrutinee(), key, use).then(scrutinee ->
                        mapUnits(match.nilBranch(), key, use).then(nil ->
                                (match.headName().equals(variable) || match.tailName().equals(variable)
                                        ? Trampoline.done(match.consBranch()) : mapUnits(match.consBranch(), key, use))
                                        .map(cons -> new PirTerm.ListMatch(scrutinee, match.headName(), match.tailName(),
                                                nil, cons))));
                case PirTerm.PairMatch match -> mapUnits(match.scrutinee(), key, use).then(scrutinee ->
                        (match.firstName().equals(variable) || match.secondName().equals(variable)
                                ? Trampoline.done(match.body()) : mapUnits(match.body(), key, use))
                                .map(body -> new PirTerm.PairMatch(scrutinee, match.pairType(), match.firstName(),
                                        match.secondName(), body)));
                case PirTerm.DataMatch match -> mapUnits(match.scrutinee(), key, use).then(scrutinee ->
                        Trampoline.each(match.branches(), b ->
                                        (b.bindings().contains(variable) || variable.equals(b.patternVar())
                                                ? Trampoline.done(b.body()) : mapUnits(b.body(), key, use))
                                                .map(body -> new PirTerm.MatchBranch(b.constructorName(), b.bindings(),
                                                        b.bindingTypes(), body, b.patternVar())))
                                .map(branches -> new PirTerm.DataMatch(scrutinee, branches)));
                default -> PirHelpers.traverseChildren(term, child -> mapUnits(child, key, use));
            };
            return result.map(replacement -> remember(term, replacement));
        });
    }

    /**
     * Count every unit of the current shape by key, in pre-order; a matched unit is a leaf, a dead binding's value is
     * skipped. A work list, not recursion (issue #327).
     */
    private void collectSites(PirTerm root, Map<Key, Integer> sites) {
        var pending = new ArrayDeque<PirTerm>();
        pending.push(root);
        while (!pending.isEmpty()) {
            var term = pending.pop();
            var key = keyOf(term, shape);
            if (key != null) {
                sites.merge(key, 1, Integer::sum);
                continue;
            }
            switch (term) {
                case PirTerm.Let let when isDead(let) -> pending.push(let.body());
                case PirTerm.LetRec rec when isDead(rec) -> pending.push(rec.body());
                default -> pushChildren(pending, term);
            }
        }
    }

    /** Every binder and variable name, and each let's first value, in pre-order. A work list, not recursion (issue #327). */
    private void collectBinders(PirTerm root) {
        var pending = new ArrayDeque<PirTerm>();
        pending.push(root);
        while (!pending.isEmpty()) {
            var term = pending.pop();
            switch (term) {
                case PirTerm.Var v -> names.add(v.name());
                case PirTerm.Lam l -> bind(l.param());
                case PirTerm.Let l -> { bind(l.name()); letValues.putIfAbsent(l.name(), l.value()); }
                case PirTerm.LetRec r -> r.bindings().forEach(b -> bind(b.name()));
                case PirTerm.ListMatch m -> { bind(m.headName()); bind(m.tailName()); }
                case PirTerm.PairMatch m -> { bind(m.firstName()); bind(m.secondName()); }
                case PirTerm.DataMatch m -> m.branches().forEach(b -> {
                    b.bindings().forEach(this::bind);
                    if (b.patternVar() != null) bind(b.patternVar());
                });
                case PirTerm.IntegerCase _, PirTerm.App _, PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _,
                     PirTerm.IfThenElse _, PirTerm.Trace _, PirTerm.DataConstr _ -> { }
            }
            pushChildren(pending, term);
        }
    }

    /** Pushes the children of {@code term} so that they pop in {@link PirHelpers#mapChildren}'s order. */
    private static void pushChildren(ArrayDeque<PirTerm> pending, PirTerm term) {
        var children = PirHelpers.children(term);
        for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
    }

    private void bind(String name) {
        names.add(name);
        binderCounts.merge(name, 1, Integer::sum);
    }

    private String fresh(String prefix) {
        String name;
        do {
            int index = nextName.merge(prefix, 1, Integer::sum) - 1;
            name = prefix + index;
        } while (!names.add(name));
        return name;
    }

    /** Preserve unchanged nodes and map replacements back to their original source locations. */
    private PirTerm remember(PirTerm original, PirTerm replacement) {
        if (original.equals(replacement)) return original;
        var location = positions.get(original);
        if (location != null) positions.put(replacement, location);
        if (debugProvenance != null) debugProvenance.transfer(original, replacement, RULE);
        return replacement;
    }
}
