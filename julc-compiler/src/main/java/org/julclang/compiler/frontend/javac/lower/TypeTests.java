package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.DeconstructionPatternTree;
import com.sun.source.tree.InstanceOfTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.subset.RecordDeclarations;
import org.julclang.compiler.frontend.javac.subset.RecordPatternFix;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code instanceof} (ADR-068 I12): a comparison of constructor tags. The variant and its tag are what javac bound, by
 * identity, so a record of another sum with the variant's simple name is not it, and two variants of one simple name have
 * two tags. A tag means something only within one sealed interface, so the operand's static type must be the variant or
 * that interface. Anything else is rejected: the comparison would run on a value of some other type, where
 * Java tests the value's class.
 * <p>
 * <b>Evaluation order.</b> The operand is evaluated once, then the test. In {@code if (e instanceof V v)}, {@code Ifs} binds
 * {@code e} once, unless it is a variable or a literal ({@code PirHelpers.readOnce}, #146): the test and {@code v} read that
 * one binding, so a trace in {@code e} shows once, as in Java. In a guard the dispatch reads the operand it is given.
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
        return instanceOf(tree, frame, () -> lowering.expr(tree.getExpression(), frame));
    }

    /**
     * {@link #instanceOf(InstanceOfTree, Frame)} of {@code operand}, the term of {@code tree}'s operand. In a guard too, the
     * test reads {@code operand} and does not lower the operand again, so a caller that bound it once reads it once.
     */
    PirTerm instanceOf(InstanceOfTree tree, Frame frame, Supplier<PirTerm> operand) {
        if (frame.inGuard()) return totalDispatch(tree, operand);
        var scrutinee = operand.get();
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
    private PirTerm totalDispatch(InstanceOfTree tree, Supplier<PirTerm> operand) {
        var variant = guardVariantOf(tree);
        var scrutinee = operand.get();
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

    /**
     * Whether a value of {@code type} is a value of {@code element}'s type: that type, or an intersection with it among its
     * bounds ({@code c ? new S.A() : new S.B()} is {@code Record & S}), as Java's value is a value of each bound.
     */
    private boolean isOf(TypeMirror type, TypeElement element) {
        if (type instanceof IntersectionType intersection) return intersection.getBounds().stream().anyMatch(bound -> isOf(bound, element));
        return element.equals(facts.types().asElement(type));
    }

    /** The constructor {@code tree} tests for, of the one sealed interface it can mean. */
    private PirType.Constructor constructor(InstanceOfTree tree, TypeElement variant) {
        var parents = facts.sealedParents(variant);
        if (parents.isEmpty()) throw unsupported(tree);
        if (variant.getKind() != ElementKind.RECORD || facts.hasAnnotation(variant, JavacNames.NEW_TYPE)) {
            throw RecordDeclarations.notPlainRecord(facts, tree, variant, parents.getFirst());
        }
        if (parents.size() > 1) throw RecordDeclarations.twoSealedParents(facts, tree, variant, parents);
        var parent = parents.getFirst();
        var operandType = facts.type(tree.getExpression());
        if (!isOf(operandType, variant) && !isOf(operandType, parent)) {
            throw facts.rejection(DiagnosticCodes.INSTANCEOF_OPERAND_UNRELATED, tree, facts.location(tree).fragment(),
                    ArrayIdentityEquality.text(operandType), parent.getSimpleName());
        }
        var sum = (PirType.SumType) facts.registry().lookupNominal(facts.identity(parent)).orElseThrow();
        return sum.constructors().get(facts.permitted(parent).indexOf(variant));
    }

    private CompilerException unsupported(InstanceOfTree tree) {
        // The source's words: javac's tree text would print a var component as the type it inferred.
        String fix = tree.getPattern() instanceof DeconstructionPatternTree recordPattern
                ? RecordPatternFix.ofInstanceof(facts, recordPattern, facts.text(tree.getExpression()))
                : null;
        // A plain type test has no pattern: it is reported at the instanceof itself.
        var where = tree.getPattern() != null ? tree.getPattern() : tree;
        return facts.rejectionWithFix(DiagnosticCodes.INSTANCEOF_RECORD_PATTERN_UNSUPPORTED, fix, where, facts.location(tree).fragment());
    }
}
