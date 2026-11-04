package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.SwitchResults;
import org.julclang.compiler.frontend.javac.subset.RecordDeclarations;
import org.julclang.compiler.frontend.javac.subset.SwitchRules;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.TypeMethodRegistry;
import org.julclang.compiler.resolve.LibraryMethodRegistry;
import org.julclang.core.Constant;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * {@code switch} expressions over a sealed interface, in the reference's two shapes, each reviewed against its source in
 * {@code PirGenerator}: {@link #unguarded} and {@link #guarded} (ADR-067 D2). A switch with a guard takes the second, and a
 * variant is its {@link TypeElement}: its branch is its place in what the interface permits, which is the order the registry
 * took (I11), so two variants of one simple name are two branches.
 * <p>
 * <b>Evaluation order.</b> The selector is evaluated once, before any label: a {@code DataMatch} binds its scrutinee, and
 * then dispatches on the tag. A branch decodes its variant's fields, each once, before anything in the arm. Then a guard
 * runs in source order, and one that is false continues with the next arm of the variant and finally {@code default}, so
 * at most one arm's body runs. Only the selected arm's guards and body run. A variant with no arm decodes nothing. There is
 * no fall-through: {@code SwitchRules} rejects a group that would need it.
 * <p>
 * <b>Generation order</b>, which decides which of two errors comes first, is the reference's: what an arm updates, then the
 * selector, then the rules, then {@code default} and the arms. Unguarded, {@code default} is last, because javac has
 * rejected a pattern after it, and guarded it is first.
 */
final class Switches {

    private final JavacFacts facts;
    private final Terms terms;
    private final Typing typing;
    private final Lowering lowering;
    private final Statements statements;
    private final TypeTests typeTests;
    private final SwitchRules rules;
    private final Guards guards;

    Switches(JavacFacts facts, Terms terms, Typing typing, Lowering lowering, Statements statements, CallTargets targets,
             TypeTests typeTests, TypeMethodRegistry typeMethods) {
        this.facts = facts;
        this.terms = terms;
        this.typing = typing;
        this.lowering = lowering;
        this.statements = statements;
        this.typeTests = typeTests;
        this.rules = new SwitchRules(facts);
        this.guards = new Guards(facts, typing, lowering, targets, typeMethods, rules);
    }

    /** {@link Guards#exact}. */
    PirTerm exact(ExpressionTree tree) {
        return guards.exact(tree);
    }

    PirTerm expression(SwitchExpressionTree tree, Frame frame) {
        rules.requireLocalUpdates(tree);
        var selectorTree = ((ParenthesizedTree) tree.getExpression()).getExpression();
        var selector = lowering.expr(selectorTree, frame);
        var sum = sumOf(tree, selectorTree, selector, frame);
        var sealed = sealedOf(selectorTree, sum);
        if (!sealed.getTypeParameters().isEmpty()) {
            throw facts.rejection(DiagnosticCodes.SWITCH_REQUIRES_SEALED_INTERFACE, tree, sum.name()
                    + ", a sealed interface with type parameters; JuLC drops type arguments, so it "
                    + "cannot decide which variants apply or whether the switch is exhaustive");
        }
        var model = rules.check(tree, sealed, sum);
        var result = model.guarded() ? guarded(selector, sum, sealed, model, frame) : unguarded(selector, sum, sealed, model, frame);
        typing.requireOneRepresentation(tree, "a switch expression", typing.held(SwitchResults.of(tree)));
        typing.recordSwitch(result);
        return result;
    }

    /** What the reference types the selector by: its term, and failing that what it reads of the expression. */
    private PirType.SumType sumOf(SwitchExpressionTree tree, ExpressionTree selectorTree, PirTerm selector, Frame frame) {
        var type = typing.ofVariable(selector);
        if (type instanceof PirType.SumType sum) return sum;
        if (guards.referenceType(selectorTree, frame) instanceof PirType.SumType sum) return sum;
        throw facts.rejection(DiagnosticCodes.SWITCH_REQUIRES_SEALED_INTERFACE, tree, LibraryMethodRegistry.pirTypeName(type));
    }

    /** The sealed interface javac types the selector by, which must be the reference's: its tags are that interface's. */
    private TypeElement sealedOf(ExpressionTree selectorTree, PirType.SumType sum) {
        TypeMirror type = facts.type(selectorTree);
        if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement sealed
                && sealed.getKind() == ElementKind.INTERFACE
                && sum.equals(facts.registry().lookupNominal(facts.identity(sealed)).orElse(null))) {
            return sealed;
        }
        // The reference's rule, with javac's type of the selector (JULC0007).
        throw facts.rejection(DiagnosticCodes.SWITCH_REQUIRES_SEALED_INTERFACE, selectorTree, ArrayIdentityEquality.text(type));
    }

    /** A variant's constructor, with the stops of {@code instanceof}: a tag means something only for a plain record of one sum. */
    private int branchOf(SwitchRules.Arm arm, TypeElement sealed) {
        var variant = arm.variant();
        if (variant.getKind() != ElementKind.RECORD || facts.hasAnnotation(variant, JavacNames.NEW_TYPE)) {
            throw RecordDeclarations.notPlainRecord(facts, arm.pattern(), variant, sealed);
        }
        var parents = facts.sealedParents(variant);
        if (parents.size() > 1) throw RecordDeclarations.twoSealedParents(facts, arm.pattern(), variant, parents);
        return facts.permitted(sealed).indexOf(variant);
    }

    private static PirType.RecordType variantType(PirType.Constructor constructor) {
        return new PirType.RecordType(constructor.name(), constructor.fields());
    }

    private static Set<String> fieldNames(PirType.Constructor constructor) {
        var names = new HashSet<String>();
        constructor.fields().forEach(field -> names.add(field.name()));
        return names;
    }

    // --- A switch with no guard (ADR-067 I8) ---

    /**
     * One branch per constructor, in tag order. An arm's fields are bound under names no source name can be, with the
     * pattern variable's in them, and a constructor with no arm holds {@code default}: one term, placed in each.
     */
    private PirTerm unguarded(PirTerm selector, PirType.SumType sum, TypeElement sealed, SwitchRules.Model model, Frame frame) {
        var constructors = sum.constructors();
        var branches = new PirTerm.MatchBranch[constructors.size()];
        for (var arm : model.arms()) {
            int index = branchOf(arm, sealed);
            var constructor = constructors.get(index);
            var element = facts.element(arm.pattern().getVariable());
            var scope = frame.scope().bind(element, variantType(constructor));
            var binder = scope.lookup(element);
            var bindings = new ArrayList<String>();
            for (int i = 0; i < constructor.fields().size(); i++) bindings.add("#__pfield-" + binder.name() + "-" + i);
            var body = body(arm.entry(), frame.in(scope, frame.patterns().arm(element, fieldNames(constructor), bindings, false)));
            branches[index] = terms.branch(constructor.name(), bindings, fieldTypes(constructor), body, binder);
        }
        var defaultBody = model.defaultEntry() == null ? null : body(model.defaultEntry(), frame);
        for (int i = 0; i < branches.length; i++) {
            if (branches[i] != null) continue;
            var constructor = constructors.get(i);
            branches[i] = defaultBody == null
                    ? terms.generatedBranch(constructor.name(), List.of(), new PirTerm.Error(new PirType.UnitType()))
                    : terms.generatedBranch(constructor.name(), fieldTypes(constructor), defaultBody);
        }
        return new PirTerm.DataMatch(selector, List.of(branches));
    }

    private static List<PirType> fieldTypes(PirType.Constructor constructor) {
        return constructor.fields().stream().map(PirType.Field::type).toList();
    }

    // --- A switch with a guard (ADR-067 D2) ---

    /** An arm, lowered: {@code guard} is null where the label is unguarded. */
    private record Compiled(SwitchRules.Arm arm, Scope.Binder binder, PirTerm guard, PirTerm body) {
    }

    /**
     * One {@code DataMatch} over the selector, so ADR-038's and ADR-041's dispatch apply. In a constructor's branch its fields
     * are decoded once, under names all of its arms share, and its arms form a chain in source order: each binds its
     * pattern variable to the scrutinee, tests its guard and runs its body; a false guard continues with the next arm, and
     * finally with {@code default}, a join bound outside the match. A continuation is bound as a unit lambda before the arm's
     * binder, and inlined only when the arm's variable is not free in it (F9). So a later arm or {@code default} never
     * resolves a name under an earlier arm's binding. A one-constructor sum checks the tag first (F11). Every generated name
     * is chosen here, before the term is built, against what can be in its scope (ADR-060).
     */
    private PirTerm guarded(PirTerm selector, PirType.SumType sum, TypeElement sealed, SwitchRules.Model model, Frame frame) {
        var constructors = sum.constructors();
        var avoid = new HashSet<String>();
        frame.patterns().addNamesTo(avoid);
        var armsByBranch = new LinkedHashMap<Integer, List<SwitchRules.Arm>>();
        for (var arm : model.arms()) armsByBranch.computeIfAbsent(branchOf(arm, sealed), key -> new ArrayList<>()).add(arm);
        var fieldBindings = new LinkedHashMap<Integer, List<String>>();
        for (int c = 0; c < constructors.size(); c++) {
            if (!armsByBranch.containsKey(c)) continue;
            var constructor = constructors.get(c);
            var names = new ArrayList<String>();
            for (int i = 0; i < constructor.fields().size(); i++) {
                var name = PirHelpers.hygienicName("#case_field", avoid);
                avoid.add(name);
                names.add(name);
            }
            fieldBindings.put(c, names);
        }

        // default and every arm are compiled in the switch's scope; an arm adds only its own bindings (F9).
        PirTerm defaultBody = model.defaultEntry() == null ? null : body(model.defaultEntry(), frame);
        var compiledByBranch = new LinkedHashMap<Integer, List<Compiled>>();
        var compiled = new ArrayList<Compiled>();
        for (var arm : model.arms()) {
            int c = branchOf(arm, sealed);
            var one = compile(arm, constructors.get(c), fieldBindings.get(c), frame);
            compiled.add(one);
            compiledByBranch.computeIfAbsent(c, key -> new ArrayList<>()).add(one);
        }

        var userTerms = new ArrayList<PirTerm>();
        userTerms.add(selector);
        if (defaultBody != null) userTerms.add(defaultBody);
        for (var arm : compiled) {
            if (arm.guard() != null) userTerms.add(arm.guard());
            userTerms.add(arm.body());
        }
        avoid.addAll(PirHelpers.freeVariables(userTerms.toArray(PirTerm[]::new)));
        var dataName = PirHelpers.hygienicName("#case_data", avoid);
        avoid.add(dataName);
        var defaultName = PirHelpers.hygienicName("#case_default", avoid);
        avoid.add(defaultName);
        var unitName = PirHelpers.hygienicName("#case_unit", avoid);
        avoid.add(unitName);
        var unit = new PirTerm.Const(Constant.unit());
        PirTerm defaultCall = defaultBody == null ? null
                : new PirTerm.App(terms.generated(defaultName, new PirType.FunType(new PirType.UnitType(), typing.ofTerm(defaultBody))), unit);

        var branches = new ArrayList<PirTerm.MatchBranch>();
        for (int c = 0; c < constructors.size(); c++) {
            var constructor = constructors.get(c);
            // Unreachable for well-formed values without default: javac proved every constructor covered by an unguarded arm.
            PirTerm rest = defaultCall != null ? defaultCall : new PirTerm.Error(new PirType.UnitType());
            var arms = compiledByBranch.get(c);
            if (arms == null) {
                branches.add(terms.generatedBranch(constructor.name(), List.of(), rest));
                continue;
            }
            for (int i = arms.size() - 1; i >= 0; i--) rest = chain(arms.get(i), rest, dataName, unitName, avoid);
            branches.add(terms.guardedBranch(constructor.name(), fieldBindings.get(c), fieldTypes(constructor), rest, dataName));
        }

        PirTerm scrutinee = constructors.size() == 1 ? typeTests.tagChecked(selector, avoid) : selector;
        PirTerm match = new PirTerm.DataMatch(scrutinee, branches);
        if (defaultBody != null && PirHelpers.freeVariables(match).contains(defaultName)) {
            match = terms.generatedLet(defaultName, terms.generatedLam(unitName, new PirType.UnitType(), defaultBody), match);
        }
        return match;
    }

    private Compiled compile(SwitchRules.Arm arm, PirType.Constructor constructor, List<String> bindings, Frame frame) {
        var element = facts.element(arm.pattern().getVariable());
        var scope = frame.scope().bind(element, variantType(constructor));
        var inner = frame.in(scope, frame.patterns().arm(element, fieldNames(constructor), bindings, true));
        var guard = arm.guard() == null ? null : guards.guard(arm, inner);
        return new Compiled(arm, scope.lookup(element), guard, body(arm.entry(), inner));
    }

    /**
     * Arm then {@code rest}. The placement decides by the variable's source name, which is what a free read of {@code rest}
     * resolves to in the reference, and {@link Scope} may have marked a binder's name beside a static field.
     */
    private PirTerm chain(Compiled arm, PirTerm rest, String dataName, String unitName, Set<String> avoid) {
        var binder = arm.binder();
        var data = terms.generated(dataName, new PirType.DataType());
        if (arm.guard() == null) return terms.let(binder, data, arm.body()); // javac rejected any later arm for it as dominated.
        if (!readsName(rest, binder)) return terms.let(binder, data, new PirTerm.IfThenElse(arm.guard(), arm.body(), rest));
        var scoped = new HashSet<>(avoid);
        scoped.addAll(PirHelpers.freeVariables(arm.guard(), arm.body()));
        var nextName = PirHelpers.hygienicName("#case_next", scoped);
        var next = terms.generated(nextName, new PirType.FunType(new PirType.UnitType(), typing.ofTerm(rest)));
        return terms.generatedLet(nextName, terms.generatedLam(unitName, new PirType.UnitType(), rest),
                terms.let(binder, data, new PirTerm.IfThenElse(arm.guard(), arm.body(), new PirTerm.App(next, new PirTerm.Const(Constant.unit())))));
    }

    private static boolean readsName(PirTerm rest, Scope.Binder binder) {
        String source = binder.element().getSimpleName().toString();
        for (var free : PirHelpers.freeVariables(rest)) {
            if (Scope.sourceName(free).equals(source)) return true;
        }
        return false;
    }

    // --- An arm's body ---

    /** The value of a case: its expression, or what its statements yield. Each arm is lowered in its own frame (#291). */
    private PirTerm body(CaseTree entry, Frame frame) {
        if (entry.getCaseKind() == CaseTree.CaseKind.RULE) {
            return switch (entry.getBody()) {
                case ExpressionTree value -> lowering.expr(value, frame);
                case BlockTree block -> block(block, frame);
                default -> throw facts.rejection(DiagnosticCodes.STATEMENT_UNSUPPORTED, entry.getBody(), facts.location(entry.getBody()).fragment());
            };
        }
        List<? extends StatementTree> group = entry.getStatements();
        if (group.size() == 1 && group.getFirst() instanceof BlockTree block) return block(block, frame);
        return statements.sequence(group, 0, frame, null);
    }

    /** A case block must yield on every path, by the reference's reading, which is stricter than javac's in a bare block. */
    private PirTerm block(BlockTree block, Frame frame) {
        if (!Exits.allPathsYield(block.getStatements())) {
            throw facts.rejection("switch case block may not yield a value on all execution paths",
                    "Ensure all code paths in the case block end with a 'yield' statement.", block);
        }
        return statements.block(block, frame);
    }
}
