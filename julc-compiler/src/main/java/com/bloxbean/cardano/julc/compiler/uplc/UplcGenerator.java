package com.bloxbean.cardano.julc.compiler.uplc;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.pir.PirSubstitution;
import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;

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

    private final Deque<String> scope = new ArrayDeque<>();

    /** PIR term → source location (provided by PirGenerator when source maps are enabled). */
    private final Map<PirTerm, SourceLocation> pirPositions;

    /** UPLC term → source location (built during generation). */
    private final IdentityHashMap<Term, SourceLocation> uplcPositions = new IdentityHashMap<>();

    /** Stack of inherited source locations for propagation to inner terms. */
    private final Deque<SourceLocation> locationStack = new ArrayDeque<>();

    public UplcGenerator() {
        this(null);
    }

    /**
     * Create a UplcGenerator with source position mapping.
     *
     * @param pirPositions PIR term to source location map from PirGenerator (nullable)
     */
    public UplcGenerator(Map<PirTerm, SourceLocation> pirPositions) {
        this.pirPositions = pirPositions != null ? pirPositions : Map.of();
    }

    /**
     * Get the UPLC term → source location map built during generation.
     * Only populated when pirPositions was provided.
     */
    public IdentityHashMap<Term, SourceLocation> getUplcPositions() {
        return uplcPositions;
    }

    public Term generate(PirTerm pir) {
        var term = generateCore(pir);
        // Record source position: check if this PIR term has a direct mapping,
        // otherwise inherit from parent context (locationStack)
        if (!pirPositions.isEmpty()) {
            var loc = pirPositions.get(pir);
            if (loc != null) {
                uplcPositions.put(term, loc);
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

            case PirTerm.Builtin(var fun) -> wrapForces(Term.builtin(fun), forceCount(fun));

            case PirTerm.Lam(var param, _, var body) -> {
                scope.push(param);
                var bodyTerm = generate(body);
                scope.pop();
                yield Term.lam(param, bodyTerm);
            }

            case PirTerm.App(var function, var argument) -> {
                // Handle field accessor: App(Var(".field"), scope) -> field extraction
                if (function instanceof PirTerm.Var(var name, _) && name.startsWith(".")) {
                    // For MVP, field access on Data-typed values is just passed through
                    // The ValidatorWrapper/DataCodecGenerator handles the actual field extraction
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
                yield Term.apply(Term.lam(name, bodyTerm), valTerm);
            }

            case PirTerm.LetRec letRec -> generateLetRec(letRec);

            case PirTerm.IfThenElse(var cond, var thenBranch, var elseBranch) -> {
                // Force(Apply(Apply(Apply(Force(Builtin(IfThenElse)), cond), Delay(then)), Delay(else)))
                var ifBuiltin = Term.force(Term.builtin(DefaultFun.IfThenElse));
                yield Term.force(
                        Term.apply(
                                Term.apply(
                                        Term.apply(ifBuiltin, generate(cond)),
                                        Term.delay(generate(thenBranch))),
                                Term.delay(generate(elseBranch))));
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
     * 3. If exactly 2 bindings form a mutual cycle, apply Bekic's theorem
     * 4. If cycle has >2 bindings, throw an error (not yet supported)
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

        // Mutual cycle detected — try Bekic's theorem for 2-binding case
        if (bindings.size() == 2) {
            return generateBekicLetRec(bindings.get(0), bindings.get(1), letRec.body());
        }

        // >2 mutual bindings: not yet supported
        throw new CompilerException("Mutually recursive bindings with more than 2 participants not yet supported: "
                + String.join(", ", bindingNames));
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

    /**
     * Generate Data-based pattern matching for DataMatch.
     * Builds the equivalent PIR using UnConstrData + FstPair + SndPair +
     * IfThenElse tag dispatch + HeadList/TailList field extraction,
     * then generates UPLC from that PIR.
     */
    private Term generateDataMatch(PirTerm scrutinee, List<PirTerm.MatchBranch> branches) {
        var dataName = "__match_data";
        var pairName = "__match_pair";
        var tagName = "__match_tag";
        var fieldsName = "__match_fields";

        // Build the dispatch chain: IfThenElse(tag==0, branch0, IfThenElse(tag==1, branch1, ...Error))
        PirTerm dispatch;
        if (branches.size() == 1) {
            dispatch = buildBranchFieldExtraction(branches.get(0), fieldsName, dataName);
        } else {
            dispatch = new PirTerm.Error(new PirType.UnitType());
            for (int i = branches.size() - 1; i >= 0; i--) {
                var branchBody = buildBranchFieldExtraction(branches.get(i), fieldsName, dataName);
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
     * HeadList/TailList for extraction, UnIData/UnBData for decoding.
     */
    private PirTerm buildBranchFieldExtraction(PirTerm.MatchBranch branch, String fieldsName, String dataName) {
        var bindings = branch.bindings();
        var bindingTypes = branch.bindingTypes();

        PirTerm result = branch.body();

        if (!bindings.isEmpty()) {
            // Build Let chain for field extractions
            var lets = new ArrayList<PirTerm.Let>();

            for (int j = 0; j < bindings.size(); j++) {
                var listVar = (j == 0) ? fieldsName : "__rest_" + (j - 1);
                var listRef = new PirTerm.Var(listVar, new PirType.DataType());

                // Decode field: UnIData(HeadList(fields)) for Integer, etc.
                var headExpr = pirApp1(DefaultFun.HeadList, listRef);
                var decodedExpr = pirWrapDecode(headExpr, bindingTypes.get(j));
                lets.add(new PirTerm.Let(bindings.get(j), decodedExpr, null)); // body filled later

                if (j + 1 < bindings.size()) {
                    var tailExpr = pirApp1(DefaultFun.TailList, listRef);
                    lets.add(new PirTerm.Let("__rest_" + j, tailExpr, null)); // body filled later
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

    /** Wrap a PIR Data value with the appropriate decoding based on type. */
    private static PirTerm pirWrapDecode(PirTerm data, PirType type) {
        return switch (type) {
            case PirType.IntegerType _ -> pirApp1(DefaultFun.UnIData, data);
            case PirType.ByteStringType _ -> pirApp1(DefaultFun.UnBData, data);
            case PirType.ListType _ -> pirApp1(DefaultFun.UnListData, data);
            case PirType.MapType _ -> pirApp1(DefaultFun.UnMapData, data);
            default -> data; // DataType, RecordType, SumType etc. — already Data
        };
    }

    private int deBruijnIndex(String name) {
        int index = 1; // De Bruijn indices are 1-based
        for (var n : scope) {
            if (n.equals(name)) return index;
            index++;
        }
        throw new CompilerException("Unbound variable: " + name);
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
                 SerialiseData, MkCons, HeadList, TailList, NullList,
                 DropList, LengthOfArray, ListToArray, IndexArray, MultiIndexArray -> 1;
            // 0 Forces (monomorphic)
            default -> 0;
        };
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
    private static Term wrapDataEncode(Term value, PirType type) {
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
            case PirType.DataType _, PirType.RecordType _, PirType.SumType _ -> value; // Already Data
            default -> value; // Pass through for unknown types
        };
    }
}
