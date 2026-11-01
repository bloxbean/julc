package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import java.math.BigInteger;

/**
 * Names and API constants (ADR-068 I3, I12). A name is what javac bound it to: the {@link Scope} is keyed by the element,
 * so a parameter shadowing a static field, or a user type named like an API type, resolves as in Java with no rule here.
 * Reading a variable has no effect, so this family has no evaluation order to state.
 */
final class Names {

    private final JavacFacts facts;
    private final Terms terms;

    Names(JavacFacts facts, Terms terms) {
        this.facts = facts;
        this.terms = terms;
    }

    PirTerm identifier(IdentifierTree tree, Frame frame) {
        var element = facts.element(tree);
        var binder = frame.scope().lookup(element);
        if (binder != null) return terms.var(element, binder);
        // javac bound the name to a field no binder stands for: one without an initializer, or another class's.
        if (element.getKind() == ElementKind.FIELD && !tree.getName().contentEquals("this")
                && !tree.getName().contentEquals("super")) {
            throw facts.rejection("Undefined variable: " + tree.getName(), tree);
        }
        throw NotPorted.at(facts, tree);
    }

    /**
     * The API constants the reference knows, written as a class name, a dot and the field: {@code BigInteger.ZERO},
     * {@code ONE}, {@code TWO}, {@code TEN} and {@code PlutusData.UNIT}. The field is the one javac resolved, so a user's
     * {@code BigInteger} is not this; any other spelling, such as a qualified class name, the reference rejects.
     */
    PirTerm constant(MemberSelectTree tree) {
        if (tree.getExpression() instanceof IdentifierTree qualifier
                && facts.element(qualifier) instanceof TypeElement
                && facts.element(tree).getEnclosingElement() instanceof TypeElement owner) {
            String name = tree.getIdentifier().toString();
            switch (facts.identity(owner)) {
                case JavacNames.BIG_INTEGER -> {
                    var value = switch (name) {
                        case "ZERO" -> BigInteger.ZERO;
                        case "ONE" -> BigInteger.ONE;
                        case "TWO" -> BigInteger.TWO;
                        case "TEN" -> BigInteger.TEN;
                        default -> null;
                    };
                    if (value != null) return terms.at(tree, new PirTerm.Const(Constant.integer(value)));
                }
                case JavacNames.PLUTUS_DATA -> {
                    if (name.equals("UNIT")) return terms.at(tree, unit());
                }
                default -> {
                }
            }
        }
        throw NotPorted.at(facts, tree);
    }

    /** {@code Constr(0, [])}. */
    private static PirTerm unit() {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData), new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData), new PirTerm.Const(Constant.unit())));
    }
}
