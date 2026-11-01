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
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.math.BigInteger;
import java.util.ArrayList;

/**
 * {@code instanceof} (ADR-068 I12): a comparison of constructor tags. The variant and its tag are what javac bound, by
 * identity, so a record of another sum with the variant's simple name is not it, and two variants of one simple name have
 * two tags. A tag means something only within one sealed interface, so the operand's static type must be the variant or
 * that interface. Anything else stops at {@code JULC0084}: the comparison would run on a value of some other type, where
 * Java tests the value's class.
 * <p>
 * <b>Evaluation order.</b> The operand is evaluated once, then the test. As in the reference, {@code if (e instanceof V v)}
 * lowers {@code e} a second time for {@code v}, so where the test holds {@code e} runs twice: a trace in it shows twice.
 */
final class TypeTests {

    private final JavacFacts facts;
    private final Lowering lowering;

    TypeTests(JavacFacts facts, Lowering lowering) {
        this.facts = facts;
        this.lowering = lowering;
    }

    /** {@code FstPair(UnConstrData(operand)) == tag}. */
    PirTerm instanceOf(InstanceOfTree tree, Frame frame) {
        var scrutinee = lowering.expr(tree.getExpression(), frame);
        var constructor = constructor(tree);
        var tag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), scrutinee));
        return PirHelpers.builtinApp2(DefaultFun.EqualsInteger, tag,
                new PirTerm.Const(Constant.integer(BigInteger.valueOf(constructor.tag()))));
    }

    /** The constructor {@code tree} tests for, of the one sealed interface it can mean. */
    private PirType.Constructor constructor(InstanceOfTree tree) {
        if (!(tree.getPattern() instanceof BindingPatternTree pattern
                && facts.types().asElement(facts.type(pattern.getVariable())) instanceof TypeElement variant)) {
            throw unsupported(tree);
        }
        var parents = new ArrayList<TypeElement>();
        for (var type : variant.getInterfaces()) {
            var parent = facts.typeElement(type);
            boolean sealed = parent.getKind() == ElementKind.INTERFACE && parent.getModifiers().contains(Modifier.SEALED);
            if (sealed && facts.registry().lookupNominal(facts.identity(parent)).orElse(null) instanceof PirType.SumType) {
                parents.add(parent);
            }
        }
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
        return facts.locatedRejection("Unsupported instanceof pattern: " + facts.location(tree).fragment(),
                "instanceof is supported only with sealed interface variants: if (x instanceof Variant v) { ... }", tree);
    }
}
