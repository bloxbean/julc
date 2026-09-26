package org.julclang.compiler.uplc;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.CompilerTargetDiagnostics;
import org.julclang.compiler.debug.PirDebugProvenance;
import org.julclang.compiler.pir.PirSubstitution;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.Term;
import org.julclang.core.source.SourceLocation;
import org.julclang.vm.ProtocolCapability;

import java.math.BigInteger;
import java.util.*;

/**
 * Translates PIR terms to UPLC terms.
 * Performs type erasure and De Bruijn index computation.
 * <p>
 * When source map generation is enabled (via {@code pirPositions}), the generator
 * propagates source locations from PIR terms to their outermost UPLC terms,
 * building an {@link IdentityHashMap} for runtime error location tracking.
 */
public class UplcGenerator {

    public static final String PV11_CASE_LIST_RULE = "pv11.o3.case-list";

    public static final String PV11_CASE_PAIR_RULE = "pv11.o4.case-pair";
    public static final String PV11_CASE_BOOL_RULE = "pv11.o2.case-bool";
    public static final String PV11_CASE_INTEGER_RULE = "pv11.o5.case-integer";

    private final Deque<String> scope = new ArrayDeque<>();

    /** The single resolved target shared by this lowering invocation. */
    private final CompilationContext context;

    /** PIR term → source location (provided by PirGenerator when source maps are enabled). */
    private final Map<PirTerm, SourceLocation> pirPositions;

    /** UPLC term → source location (built during generation). */
    private final IdentityHashMap<Term, SourceLocation> uplcPositions = new IdentityHashMap<>();
    private final IdentityHashMap<Term, SourceLocation> exactUplcPositions = new IdentityHashMap<>();
    private final IdentityHashMap<Term.Lam, PirDebugProvenance.Association> emittedBinders = new IdentityHashMap<>();
    private final PirDebugProvenance debugProvenance;

    /** Stack of inherited source locations for propagation to inner terms. */
    private final Deque<SourceLocation> locationStack = new ArrayDeque<>();

    public UplcGenerator() {
        this(CompilationContext.pv11Defaults(), null);
    }

    /**
     * Create a UplcGenerator with source position mapping.
     *
     * @param pirPositions PIR term to source location map from PirGenerator (nullable)
     */
    public UplcGenerator(Map<PirTerm, SourceLocation> pirPositions) {
        this(CompilationContext.pv11Defaults(), pirPositions);
    }

    /** Create a target-aware UPLC generator with optional source positions. */
    public UplcGenerator(
            CompilationContext context,
            Map<PirTerm, SourceLocation> pirPositions) {
        this(context, pirPositions, null);
    }

    /** Opt-in lowering with exact Java declaration binder bookkeeping. */
    public UplcGenerator(
            CompilationContext context,
            Map<PirTerm, SourceLocation> pirPositions,
            PirDebugProvenance debugProvenance) {
        this.context = Objects.requireNonNull(context, "context");
        this.pirPositions = pirPositions != null ? pirPositions : Map.of();
        this.debugProvenance = debugProvenance;
    }

    /**
     * Get the UPLC term → source location map built during generation.
     * Only populated when pirPositions was provided.
     */
    public IdentityHashMap<Term, SourceLocation> getUplcPositions() {
        return uplcPositions;
    }

    public IdentityHashMap<Term, SourceLocation> getExactUplcPositions() {
        return new IdentityHashMap<>(exactUplcPositions);
    }

    public IdentityHashMap<Term.Lam, PirDebugProvenance.Association> getEmittedBinders() {
        return new IdentityHashMap<>(emittedBinders);
    }

    public Term generate(PirTerm pir) {
        var term = generateCore(pir);
        // Record source position: check if this PIR term has a direct mapping,
        // otherwise inherit from parent context (locationStack)
        if (!pirPositions.isEmpty()) {
            var loc = pirPositions.get(pir);
            if (loc != null) {
                uplcPositions.put(term, loc);
                exactUplcPositions.put(term, loc);
            } else if (!locationStack.isEmpty() && !uplcPositions.containsKey(term)) {
                // Propagate parent location to inner terms that lack their own
                uplcPositions.put(term, locationStack.peek());
            }
        }
        return term;
    }

    private Term generateCore(PirTerm pir) {
        // Push source location context for children to inherit
        if (!pirPositions.isEmpty()) {
            var loc = pirPositions.get(pir);
            if (loc != null) {
                locationStack.push(loc);
            }
        }
        try {
            return generateInner(pir);
        } finally {
            if (!pirPositions.isEmpty() && pirPositions.containsKey(pir)) {
                locationStack.pop();
            }
        }
    }

    private Term generateInner(PirTerm pir) {
        return switch (pir) {
            case PirTerm.Var(var name, _) -> {
                // Field accessor pseudo-variables are handled by their containing App
                if (name.startsWith(".")) {
                    throw new CompilerException("Bare field accessor not supported: " + name);
                }
                yield Term.var(deBruijnIndex(name));
            }

            case PirTerm.Const(var value) -> Term.const_(value);

            case PirTerm.Builtin(var fun) -> generateBuiltin(fun);

            case PirTerm.Lam(var param, _, var body) -> {
                scope.push(param);
                var bodyTerm = generate(body);
                scope.pop();
                var lambda = new Term.Lam(param, bodyTerm);
                recordBinder(pir, lambda);
                yield lambda;
            }

            case PirTerm.App(var function, var argument) -> {
                // Handle field accessor: App(Var(".field"), scope) -> field extraction
                if (function instanceof PirTerm.Var(var name, _) && name.startsWith(".")) {
                    // For MVP, field access on Data-typed values is just passed through
                    // The ValidatorWrapper handles the actual field extraction
                    yield Term.apply(
                            Term.var(deBruijnIndex(name.substring(1))),
                            generate(argument));
                }
                yield Term.apply(generate(function), generate(argument));
            }

            case PirTerm.Let(var name, var value, var body) -> {
                // Let(name, val, body) -> Apply(Lam(name, body'), val')
                var valTerm = generate(value);
                scope.push(name);
                var bodyTerm = generate(body);
                scope.pop();
                var lambda = new Term.Lam(name, bodyTerm);
                recordBinder(pir, lambda);
                yield Term.apply(lambda, valTerm);
            }

            case PirTerm.LetRec letRec -> generateLetRec(letRec);

            case PirTerm.IfThenElse(var cond, var thenBranch, var elseBranch) -> {
                if (context.optimizationLevel().pv11SafeRulesEnabled()
                        && context.supports(ProtocolCapability.CASE_ON_BUILTIN_CONSTANTS)) {
                    context.recordOptimizationRule(PV11_CASE_BOOL_RULE);
                    // Case Bool branch order is False (0), then True (1).
                    // Case evaluates the scrutinee once and only the selected branch.
                    yield new Term.Case(
                            generate(cond),
                            List.of(generate(elseBranch), generate(thenBranch)));
                }
                // Force(Apply(Apply(Apply(Force(Builtin(IfThenElse)), cond), Delay(then)), Delay(else)))
                var ifBuiltin = Term.force(Term.builtin(DefaultFun.IfThenElse));
                yield Term.force(
                        Term.apply(
                                Term.apply(
                                        Term.apply(ifBuiltin, generate(cond)),
                                        Term.delay(generate(thenBranch))),
                                Term.delay(generate(elseBranch))));
            }

            case PirTerm.IntegerCase(var scrutinee, var branches) -> {
                if (!integerCaseEnabled()) {
                    throw new CompilerException("IntegerCase requires the PV11 safe lowering profile");
                }
                // Integer Case: branch i is selected for scrutinee value i; branches take no
                // arguments and only the selected branch is evaluated (ADR-041 O5).
                var scrutineeTerm = generate(scrutinee);
                var branchTerms = new ArrayList<Term>(branches.size());
                for (var branch : branches) branchTerms.add(generate(branch));
                yield new Term.Case(scrutineeTerm, branchTerms);
            }

            case PirTerm.PairMatch(var scrutinee, _, var first, var second, var body) -> {
                if (!pairCaseEnabled()) {
                    throw new CompilerException("PairMatch requires the PV11 safe lowering profile");
                }
                context.recordOptimizationRule(PV11_CASE_PAIR_RULE);
                var pairTerm = generate(scrutinee);
                scope.push(first);
                scope.push(second);
                Term bodyTerm;
                try {
                    bodyTerm = generate(body);
                } finally {
                    scope.pop();
                    scope.pop();
                }
                yield new Term.Case(pairTerm, List.of(Term.lam(first, Term.lam(second, bodyTerm))));
            }

            case PirTerm.ListMatch(var scrutinee, var head, var tail, var nil, var cons) -> {
                if (!context.target().equals(CompilerTarget.PLUTUS_V3_PV11)
                        || !context.optimizationLevel().pv11SafeRulesEnabled()
                        || !context.supports(ProtocolCapability.CASE_ON_BUILTIN_CONSTANTS)) {
                    throw new CompilerException("ListMatch requires the PV11 safe lowering profile");
                }
                context.recordOptimizationRule(PV11_CASE_LIST_RULE);
                var listTerm = generate(scrutinee);
                var nilTerm = generate(nil);
                scope.push(head);
                scope.push(tail);
                Term consTerm;
                try {
                    consTerm = generate(cons);
                } finally {
                    scope.pop();
                    scope.pop();
                }
                // List Case: h::t selects branch 0 (applied to h then t); [] selects branch 1.
                yield new Term.Case(listTerm,
                        List.of(Term.lam(head, Term.lam(tail, consTerm)), nilTerm));
            }

            case PirTerm.DataConstr(var tag, var dataType, var fields) -> {
                // Get field types from the record/sum type
                var fieldTypes = getFieldTypes(dataType, tag);

                // Build the Data list from right to left: MkCons(last, MkNilData())
                Term fieldList = Term.apply(
                        wrapForces(Term.builtin(DefaultFun.MkNilData), 0),
                        Term.const_(Constant.unit()));
                for (int i = fields.size() - 1; i >= 0; i--) {
                    var fieldTerm = generate(fields.get(i));
                    // Wrap with Data encoding based on field type
                    if (i < fieldTypes.size()) {
                        fieldTerm = wrapDataEncode(fieldTerm, fieldTypes.get(i));
                    }
                    fieldList = Term.apply(
                            Term.apply(
                                    wrapForces(Term.builtin(DefaultFun.MkCons), forceCount(DefaultFun.MkCons)),
                                    fieldTerm),
                            fieldList);
                }

                // ConstrData(tag, fieldList)
                yield Term.apply(
                        Term.apply(Term.builtin(DefaultFun.ConstrData),
                                Term.const_(Constant.integer(BigInteger.valueOf(tag)))),
                        fieldList);
            }

            case PirTerm.DataMatch(var scrutinee, var branches) -> {
                yield generateDataMatch(scrutinee, branches);
            }

            case PirTerm.Error _ -> Term.error();

            case PirTerm.Trace(var message, var body) -> {
                // Apply(Apply(Force(Builtin(Trace)), msg), body)
                // Trace is polymorphic (1 Force), so: Force(Builtin(Trace))
                // Unlike IfThenElse, Trace evaluates its second arg eagerly (no Delay/Force needed)
                var traceBuiltin = Term.force(Term.builtin(DefaultFun.Trace));
                yield Term.apply(
                        Term.apply(traceBuiltin, generate(message)),
                        generate(body));
            }
        };
    }

    private Term generateLetRec(PirTerm.LetRec letRec) {
        // Z-combinator implementation for recursive bindings.
        // For single binding: LetRec([name = body], expr)
        //   → Let(name, Apply(fix, Lam(name, body')), expr')
        // where fix = \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))

        if (letRec.bindings().size() == 1) {
            var binding = letRec.bindings().getFirst();
            var name = binding.name();
            var value = binding.value();

            // Build the Z-combinator:
            // fix = \f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))
            // In UPLC with De Bruijn indices:
            // fix = Lam("f", Apply(
            //   Lam("x", Apply(Var(2), Lam("v", Apply(Apply(Var(2), Var(2)), Var(1))))),
            //   Lam("x", Apply(Var(2), Lam("v", Apply(Apply(Var(2), Var(2)), Var(1)))))))

            var innerBody = Term.lam("v",
                    Term.apply(Term.apply(Term.var(2), Term.var(2)), Term.var(1)));
            var branch = Term.lam("x", Term.apply(Term.var(2), innerBody));
            var fix = Term.lam("f", Term.apply(branch, branch));

            // Generate the recursive function body: Lam(name, body')
            // The body references 'name' which is the recursive reference
            scope.push(name);
            var bodyTerm = generate(value);
            scope.pop();
            var recursiveLam = Term.lam(name, bodyTerm);

            // Apply fix to the recursive lambda
            var fixedFn = Term.apply(fix, recursiveLam);

            // Now bind name = fixedFn and generate the expression
            scope.push(name);
            var exprTerm = generate(letRec.body());
            scope.pop();

            return Term.apply(Term.lam(name, exprTerm), fixedFn);
        }

        // Multi-binding LetRec: dependency analysis + topological sort + Bekic's theorem
        return generateMultiBindingLetRec(letRec);
    }

    /**
     * Handle multi-binding LetRec by analyzing dependencies between bindings.
     * <p>
     * Strategy:
     * 1. Build a dependency graph (which bindings reference which others)
     * 2. If bindings can be topologically sorted (no mutual cycles), nest them as single-binding LetRec/Let
     * 3. Decompose mutual cycles recursively with Bekic's theorem
     */
    private Term generateMultiBindingLetRec(PirTerm.LetRec letRec) {
        var bindings = letRec.bindings();
        var bindingNames = new LinkedHashSet<String>();
        for (var b : bindings) bindingNames.add(b.name());

        // Build dependency graph: for each binding, which OTHER bindings does it reference?
        var deps = new LinkedHashMap<String, Set<String>>();
        var selfRecursive = new LinkedHashSet<String>();
        var bindingMap = new LinkedHashMap<String, PirTerm>();
        for (var b : bindings) {
            var freeVars = PirSubstitution.collectFreeVarNames(b.value());
            var otherDeps = new LinkedHashSet<String>();
            for (var dep : freeVars) {
                if (bindingNames.contains(dep) && !dep.equals(b.name())) {
                    otherDeps.add(dep);
                }
            }
            if (freeVars.contains(b.name())) {
                selfRecursive.add(b.name());
            }
            deps.put(b.name(), otherDeps);
            bindingMap.put(b.name(), b.value());
        }

        // Try topological sort (handle non-mutual case)
        var sorted = topologicalSort(deps);
        if (sorted != null) {
            // No mutual cycles — nest single-binding LetRec (for self-recursive) or Let (non-recursive)
            PirTerm result = letRec.body();
            // Process in reverse topological order (last dependency first → innermost binding)
            for (int i = sorted.size() - 1; i >= 0; i--) {
                var name = sorted.get(i);
                var value = bindingMap.get(name);
                if (selfRecursive.contains(name)) {
                    result = new PirTerm.LetRec(List.of(new PirTerm.Binding(name, value)), result);
                } else {
                    result = new PirTerm.Let(name, value, result);
                }
            }
            return generate(result);
        }

        // Mutual cycle detected — use the direct two-binding form or recursively
        // decompose a larger group into smaller fixed points.
        if (bindings.size() == 2) {
            return generateBekicLetRec(bindings.get(0), bindings.get(1), letRec.body());
        }
        return generate(decomposeBekic(bindings, letRec.body()));
    }

    /**
     * Recursively apply Bekic decomposition to an insertion-ordered mutual group. Each projection
     * of the inner fixed point is shared by a strict binding in the resulting body. Function-only
     * producer validation remains responsible for ensuring that tying the recursive environment is
     * non-eager.
     */
    private PirTerm decomposeBekic(List<PirTerm.Binding> bindings, PirTerm body) {
        if (bindings.size() <= 2) return new PirTerm.LetRec(bindings, body);
        var outer = bindings.getFirst();
        var inner = List.copyOf(bindings.subList(1, bindings.size()));
        PirTerm outerValue = outer.value();
        for (var binding : inner) {
            var projection =
                    new PirTerm.LetRec(
                            inner, new PirTerm.Var(binding.name(), new PirType.DataType()));
            outerValue = PirSubstitution.substitute(outerValue, binding.name(), projection);
        }
        PirTerm result = body;
        for (int i = inner.size() - 1; i >= 0; i--) {
            var binding = inner.get(i);
            var projection =
                    new PirTerm.LetRec(
                            inner, new PirTerm.Var(binding.name(), new PirType.DataType()));
            result = new PirTerm.Let(binding.name(), projection, result);
        }
        return new PirTerm.LetRec(List.of(new PirTerm.Binding(outer.name(), outerValue)), result);
    }

    /**
     * Topological sort of bindings based on their inter-dependencies.
     * Returns null if a cycle is detected (mutual recursion).
     * Returns sorted list in dependency order (first has no deps, last depends on earlier ones).
     */
    private List<String> topologicalSort(Map<String, Set<String>> deps) {
        var result = new ArrayList<String>();
        var visited = new LinkedHashSet<String>();
        var inProgress = new LinkedHashSet<String>();

        for (var name : deps.keySet()) {
            if (!visited.contains(name)) {
                if (!topoVisit(name, deps, visited, inProgress, result)) {
                    return null; // cycle detected
                }
            }
        }
        return result;
    }

    private boolean topoVisit(String name, Map<String, Set<String>> deps,
                              Set<String> visited, Set<String> inProgress, List<String> result) {
        if (inProgress.contains(name)) return false; // cycle
        if (visited.contains(name)) return true;
        inProgress.add(name);
        for (var dep : deps.getOrDefault(name, Set.of())) {
            if (!topoVisit(dep, deps, visited, inProgress, result)) return false;
        }
        inProgress.remove(name);
        visited.add(name);
        result.add(name);
        return true;
    }

    /**
     * Apply Bekic's theorem to decompose 2-binding mutual recursion into nested single-binding LetRecs.
     * <p>
     * Given: LetRec([A = bodyA, B = bodyB], mainBody)
     * <p>
     * Produces:
     *   LetRec([A = bodyA[B := LetRec([B = bodyB[A := Var(A)]], Var(B))]],
     *     Let(B, LetRec([B = bodyB[A := Var(A)]], Var(B)),
     *       mainBody))
     * <p>
     * Where bodyA[B := ...] means substitute all free occurrences of B in bodyA with the inner LetRec.
     * Inside the inner LetRec for B, self-references to B work via Z-combinator,
     * and references to A resolve to the outer LetRec's binding.
     */
    private Term generateBekicLetRec(PirTerm.Binding bindingA, PirTerm.Binding bindingB,
                                      PirTerm mainBody) {
        var nameA = bindingA.name();
        var nameB = bindingB.name();
        var bodyA = bindingA.value();
        var bodyB = bindingB.value();

        // Inner LetRec for B: LetRec([B = bodyB], Var(B))
        // Inside bodyB, references to A are free (will be captured by outer LetRec)
        var innerLetRecB = new PirTerm.LetRec(
                List.of(new PirTerm.Binding(nameB, bodyB)),
                new PirTerm.Var(nameB, new PirType.DataType()));

        // Substitute B in bodyA with the inner LetRec
        var bodyASubstituted = PirSubstitution.substitute(bodyA, nameB, innerLetRecB);

        // Outer LetRec for A: LetRec([A = bodyA'], ...)
        // where bodyA' has B replaced by the inner LetRec
        var outerLetRecA = new PirTerm.LetRec(
                List.of(new PirTerm.Binding(nameA, bodyASubstituted)),
                // After binding A, define B and then evaluate mainBody
                new PirTerm.Let(nameB, innerLetRecB, mainBody));

        return generate(outerLetRecA);
    }

    /** Shared O3/O4/O5 legality gate: exact PV11 target, safe profile, Case on builtin constants. */
    private boolean pv11CaseOnBuiltinEnabled() {
        return context.target().equals(CompilerTarget.PLUTUS_V3_PV11)
                && context.optimizationLevel().pv11SafeRulesEnabled()
                && context.supports(ProtocolCapability.CASE_ON_BUILTIN_CONSTANTS);
    }

    private boolean pairCaseEnabled() {
        return pv11CaseOnBuiltinEnabled();
    }

    private boolean integerCaseEnabled() {
        return pv11CaseOnBuiltinEnabled();
    }

    /**
     * Expand DataMatch with branch-local field extraction and unchanged tag dispatch.
     * ADR-038 uses typed pair destructuring under the O4 gate; other profiles retain
     * the historical UnConstrData/FstPair/SndPair expansion.
     */
    private Term generateDataMatch(PirTerm scrutinee, List<PirTerm.MatchBranch> branches) {
        // ADR-060: the dispatch binders are reserved names, chosen against every name a branch
        // can see or bind (its body's free variables, field bindings and pattern variable), so no
        // branch code is captured and no branch binder intercepts a generated reference.
        var avoid = new HashSet<String>();
        for (var branch : branches) {
            avoid.addAll(PirSubstitution.collectFreeVarNames(branch.body()));
            avoid.addAll(branch.bindings());
            if (branch.patternVar() != null) avoid.add(branch.patternVar());
        }
        var dataName = PirHelpers.hygienicName("#__match_data", avoid);
        var pairName = PirHelpers.hygienicName("#__match_pair", avoid);
        var tagName = PirHelpers.hygienicName("#__match_tag", avoid);
        var fieldsName = PirHelpers.hygienicName("#__match_fields", avoid);

        // Build the dispatch: under the PV11 safe profile a single integer Case selects the
        // branch by tag (ADR-041 O5); otherwise the historical equality chain
        // IfThenElse(tag==0, branch0, IfThenElse(tag==1, branch1, ...Error)).
        PirTerm dispatch;
        if (branches.size() == 1) {
            dispatch = buildBranchFieldExtraction(branches.get(0), fieldsName, dataName, avoid);
        } else if (branches.size() >= 2 && integerCaseEnabled()) {
            // Constructor tags are dense 0..n-1 by construction: buildDataMatch emits exactly one
            // branch per constructor in tag order. Any other tag fails at selection, before any
            // branch runs, which is the point the legacy chain reached its terminal Error.
            context.recordOptimizationRule(PV11_CASE_INTEGER_RULE);
            var bodies = new ArrayList<PirTerm>(branches.size());
            for (var branch : branches) {
                bodies.add(buildBranchFieldExtraction(branch, fieldsName, dataName, avoid));
            }
            dispatch = new PirTerm.IntegerCase(
                    new PirTerm.Var(tagName, new PirType.IntegerType()), bodies);
        } else {
            dispatch = new PirTerm.Error(new PirType.UnitType());
            for (int i = branches.size() - 1; i >= 0; i--) {
                var branchBody = buildBranchFieldExtraction(branches.get(i), fieldsName, dataName, avoid);
                var tagCheck = new PirTerm.App(
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                new PirTerm.Var(tagName, new PirType.IntegerType())),
                        new PirTerm.Const(Constant.integer(BigInteger.valueOf(i))));
                dispatch = new PirTerm.IfThenElse(tagCheck, branchBody, dispatch);
            }
        }

        // Wrap in: let data = scrutinee
        //          let pair = UnConstrData(data)
        //          let tag = FstPair(pair)
        //          let fields = SndPair(pair)
        //          dispatch
        var dataVar = new PirTerm.Var(dataName, new PirType.DataType());
        if (pairCaseEnabled()) {
            // ADR-038: successful UnConstrData proves the native pair by construction.
            // Keep data strict and once-bound, and decoding inside the unchanged dispatch.
            var pairType = new PirType.PairType(new PirType.IntegerType(),
                    new PirType.ListType(new PirType.DataType()));
            return generate(new PirTerm.Let(dataName, scrutinee,
                    new PirTerm.PairMatch(pirApp1(DefaultFun.UnConstrData, dataVar),
                            pairType, tagName, fieldsName, dispatch)));
        }
        var pairVar = new PirTerm.Var(pairName, new PirType.DataType());
        var matchPir = new PirTerm.Let(dataName, scrutinee,
                new PirTerm.Let(pairName,
                        pirApp1(DefaultFun.UnConstrData, dataVar),
                        new PirTerm.Let(tagName,
                                pirApp1(DefaultFun.FstPair, pairVar),
                                new PirTerm.Let(fieldsName,
                                        pirApp1(DefaultFun.SndPair, pairVar),
                                        dispatch))));
        return generate(matchPir);
    }

    /**
     * Build PIR for extracting fields from a Data list and binding them in the branch body.
     * HeadList/TailList for extraction; the shared typed decoder for field values.
     */
    private PirTerm buildBranchFieldExtraction(PirTerm.MatchBranch branch, String fieldsName, String dataName,
                                               Set<String> avoid) {
        var bindings = branch.bindings();
        var bindingTypes = branch.bindingTypes();

        PirTerm result = branch.body();

        if (!bindings.isEmpty()) {
            // Build Let chain for field extractions
            var lets = new ArrayList<PirTerm.Let>();

            for (int j = 0; j < bindings.size(); j++) {
                var listVar = (j == 0) ? fieldsName : PirHelpers.hygienicName("#__rest_" + (j - 1), avoid);
                var listRef = new PirTerm.Var(listVar, new PirType.DataType());

                // Decode field: UnIData(HeadList(fields)) for Integer, etc.
                var headExpr = pirApp1(DefaultFun.HeadList, listRef);
                var decodedExpr = PirHelpers.wrapDecode(headExpr, bindingTypes.get(j));
                lets.add(new PirTerm.Let(bindings.get(j), decodedExpr, null)); // body filled later

                if (j + 1 < bindings.size()) {
                    var tailExpr = pirApp1(DefaultFun.TailList, listRef);
                    lets.add(new PirTerm.Let(PirHelpers.hygienicName("#__rest_" + j, avoid), tailExpr, null)); // body filled later
                }
            }

            // Build nested Let chain from inside out
            for (int j = lets.size() - 1; j >= 0; j--) {
                var let = lets.get(j);
                result = new PirTerm.Let(let.name(), let.value(), result);
            }
        }

        // If pattern variable exists, wrap with Let binding to the scrutinee data
        if (branch.patternVar() != null && dataName != null) {
            result = new PirTerm.Let(branch.patternVar(),
                    new PirTerm.Var(dataName, new PirType.DataType()), result);
        }

        return result;
    }

    /** Create a PIR Builtin application with 1 arg. */
    private static PirTerm pirApp1(DefaultFun fun, PirTerm arg) {
        return new PirTerm.App(new PirTerm.Builtin(fun), arg);
    }

    private int deBruijnIndex(String name) {
        int index = 1; // De Bruijn indices are 1-based
        for (var n : scope) {
            if (n.equals(name)) return index;
            index++;
        }
        throw new CompilerException("Unbound variable: " + name);
    }

    private void recordBinder(PirTerm pir, Term.Lam lambda) {
        if (debugProvenance == null) return;
        var association = debugProvenance.association(pir);
        if (association != null) emittedBinders.put(lambda, association);
    }

    /**
     * Get the number of Force wrappers needed for a polymorphic builtin.
     */
    static int forceCount(DefaultFun fun) {
        return switch (fun) {
            // 2 Forces (2 type variables: ∀ a b)
            case FstPair, SndPair, ChooseList -> 2;
            // 1 Force (1 type variable: ∀ a)
            case IfThenElse, ChooseUnit, Trace, ChooseData,
                 MkCons, HeadList, TailList, NullList,
                 DropList, LengthOfArray, ListToArray, IndexArray, MultiIndexArray -> 1;
            // 0 Forces (monomorphic)
            default -> 0;
        };
    }

    /**
     * Enforce the current compiler's Plutus V3/PV11 target at the final
     * common lowering boundary. This catches direct PIR, public Builtins calls,
     * library wrappers, and every JulcCompiler entry point.
     */
    private Term generateBuiltin(DefaultFun fun) {
        if (!context.resolvedTarget().featureProfile().isBuiltinAvailable(fun)) {
            throw CompilerTargetDiagnostics.unavailableBuiltin(
                    context, fun, currentSourceLocation());
        }
        return wrapForces(Term.builtin(fun), forceCount(fun));
    }

    private SourceLocation currentSourceLocation() {
        return locationStack.isEmpty() ? null : locationStack.peek();
    }

    private static Term wrapForces(Term term, int count) {
        for (int i = 0; i < count; i++) {
            term = Term.force(term);
        }
        return term;
    }

    /**
     * Get the PIR types of fields for a DataConstr's data type.
     */
    private static List<PirType> getFieldTypes(PirType dataType, int tag) {
        if (dataType instanceof PirType.RecordType rt) {
            return rt.fields().stream().map(PirType.Field::type).toList();
        }
        if (dataType instanceof PirType.SumType st) {
            for (var ctor : st.constructors()) {
                if (ctor.tag() == tag) {
                    return ctor.fields().stream().map(PirType.Field::type).toList();
                }
            }
        }
        return List.of();
    }

    /**
     * Wrap a UPLC term with the appropriate Data encoding based on PIR type.
     * Integer → IData, ByteString → BData, List → ListData, Map → MapData, etc.
     */
    private Term wrapDataEncode(Term value, PirType type) {
        if (PirType.containsNativeOpaque(type)) {
            throw CompilerTypeDiagnostics.nativeTypeMismatch(
                    "UPLC Data construction", type, new PirType.DataType(),
                    currentSourceLocation());
        }
        return switch (type) {
            case PirType.IntegerType _ -> Term.apply(Term.builtin(DefaultFun.IData), value);
            case PirType.ByteStringType _ -> Term.apply(Term.builtin(DefaultFun.BData), value);
            case PirType.ListType _ -> Term.apply(Term.builtin(DefaultFun.ListData), value);
            case PirType.MapType _ -> Term.apply(Term.builtin(DefaultFun.MapData), value);
            case PirType.BoolType _ -> {
                // Bool: True → ConstrData(1,[]), False → ConstrData(0,[])
                var nilData = Term.apply(
                        wrapForces(Term.builtin(DefaultFun.MkNilData), 0),
                        Term.const_(Constant.unit()));
                var ifThenElse = wrapForces(Term.builtin(DefaultFun.IfThenElse), 1);
                yield Term.force(
                        Term.apply(
                                Term.apply(
                                        Term.apply(ifThenElse, value),
                                        Term.delay(Term.apply(
                                                Term.apply(Term.builtin(DefaultFun.ConstrData),
                                                        Term.const_(Constant.integer(BigInteger.ONE))),
                                                nilData))),
                                Term.delay(Term.apply(
                                        Term.apply(Term.builtin(DefaultFun.ConstrData),
                                                Term.const_(Constant.integer(BigInteger.ZERO))),
                                        nilData))));
            }
            case PirType.StringType _ -> Term.apply(Term.builtin(DefaultFun.BData),
                    Term.apply(Term.builtin(DefaultFun.EncodeUtf8), value));
            case PirType.DataType _, PirType.RecordType _, PirType.SumType _,
                    PirType.NamedTypeRef _ -> value; // Already Data
            default -> value; // Pass through for unknown types
        };
    }
}
