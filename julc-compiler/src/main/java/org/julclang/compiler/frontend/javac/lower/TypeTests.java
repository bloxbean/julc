package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.InstanceOfTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code instanceof} (ADR-068 I12): a comparison of constructor tags. The variant and its tag are what javac bound, by
 * identity, so a record of another sum with the variant's simple name is not it, and two variants of one simple name have
 * two tags. A tag means something only within one sealed interface, so the operand's static type must be the variant or
 * that interface. Anything else stops at {@code JULC0084}: the comparison would run on a value of some other type, where
 * Java tests the value's class.
 * <p>
 * <b>Evaluation order.</b> The operand is evaluated once, then the test. As in the reference, {@code if (e instanceof V v)}
 * lowers {@code e} a second time for {@code v}, so where the test holds {@code e} runs twice: a trace in it shows twice.
 * In a guard the operand is evaluated once, by the dispatch.
 */
final class TypeTests {

    private final JavacFacts facts;
    private final Terms terms;
    private final Typing typing;
    private final Lowering lowering;

    TypeTests(JavacFacts facts, Terms terms, Typing typing, Lowering lowering) {
        this.facts = facts;
        this.terms = terms;
        this.typing = typing;
        this.lowering = lowering;
    }

    /** {@code FstPair(UnConstrData(operand)) == tag}. In a guard, {@link #totalDispatch}. */
    PirTerm instanceOf(InstanceOfTree tree, Frame frame) {
        if (frame.inGuard()) return totalDispatch(tree, frame);
        var scrutinee = lowering.expr(tree.getExpression(), frame);
        var constructor = constructor(tree, variantOf(tree));
        var tag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), scrutinee));
        return PirHelpers.builtinApp2(DefaultFun.EqualsInteger, tag,
                new PirTerm.Const(Constant.integer(BigInteger.valueOf(constructor.tag()))));
    }

    /**
     * {@code x instanceof V} inside a guard (ADR-067 D1, F3): a dispatch over the constructors of V's sealed interface,
     * {@code true} for V and {@code false} for the others, so a tag that is no constructor fails and selects no later arm.
     * {@code SwitchRules} has shown the operand is V or that interface, and rejected a pattern the arm could see.
     */
    private PirTerm totalDispatch(InstanceOfTree tree, Frame frame) {
        var variant = guardVariantOf(tree);
        var scrutinee = lowering.expr(tree.getExpression(), frame);
        var tested = constructor(tree, variant);
        var parent = facts.sealedParents(variant).getFirst();
        var constructors = ((PirType.SumType) facts.registry().lookupNominal(facts.identity(parent)).orElseThrow()).constructors();
        var branches = new ArrayList<PirTerm.MatchBranch>();
        for (var constructor : constructors) {
            branches.add(terms.generatedBranch(constructor.name(), List.of(), new PirTerm.Const(Constant.bool(constructor.tag() == tested.tag()))));
        }
        var selected = constructors.size() == 1 ? tagChecked(scrutinee, new HashSet<>(PirHelpers.freeVariables(scrutinee))) : scrutinee;
        PirTerm result = new PirTerm.DataMatch(selected, branches);
        typing.recordRead(result, new PirType.BoolType());
        return result;
    }

    /**
     * {@code selector}, the only constructor of its sum, with its tag checked: a {@code DataMatch} with one branch dispatches
     * without a tag test, which would accept any {@code Constr} as that constructor (ADR-067 D2, F11). The name is added to
     * {@code avoid}.
     */
    PirTerm tagChecked(PirTerm selector, Set<String> avoid) {
        var name = PirHelpers.hygienicName("#case_selector", avoid);
        avoid.add(name);
        var value = terms.generated(name, new PirType.DataType());
        var tag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), value));
        return terms.generatedLet(name, selector, new PirTerm.IfThenElse(
                PirHelpers.builtinApp2(DefaultFun.EqualsInteger, tag, new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                value, new PirTerm.Error(new PirType.DataType())));
    }

    private TypeElement variantOf(InstanceOfTree tree) {
        if (tree.getPattern() instanceof BindingPatternTree pattern
                && facts.types().asElement(facts.type(pattern.getVariable())) instanceof TypeElement variant) {
            return variant;
        }
        throw unsupported(tree);
    }

    /** A guard may test a type as well as a pattern. */
    private TypeElement guardVariantOf(InstanceOfTree tree) {
        return tree.getPattern() == null ? (TypeElement) facts.types().asElement(facts.type(tree.getType())) : variantOf(tree);
    }

    /** The constructor {@code tree} tests for, of the one sealed interface it can mean. */
    private PirType.Constructor constructor(InstanceOfTree tree, TypeElement variant) {
        var parents = facts.sealedParents(variant);
        if (parents.isEmpty()) throw unsupported(tree);
        if (variant.getKind() != ElementKind.RECORD || facts.hasAnnotation(variant, JavacNames.NEW_TYPE)) {
            throw NotPorted.at(facts, tree, "an instanceof of a variant that is not a plain record");
        }
        if (parents.size() > 1) throw NotPorted.at(facts, tree, "an instanceof of a variant of two sealed interfaces");
        var parent = parents.getFirst();
        var operand = facts.types().asElement(facts.type(tree.getExpression()));
        if (!variant.equals(operand) && !parent.equals(operand)) {
            throw NotPorted.at(facts, tree, "an instanceof of an operand outside the variant's sealed interface");
        }
        var sum = (PirType.SumType) facts.registry().lookupNominal(facts.identity(parent)).orElseThrow();
        return sum.constructors().get(facts.permitted(parent).indexOf(variant));
    }

    private CompilerException unsupported(InstanceOfTree tree) {
        return facts.rejection("Unsupported instanceof pattern: " + tree, tree);
    }
}
