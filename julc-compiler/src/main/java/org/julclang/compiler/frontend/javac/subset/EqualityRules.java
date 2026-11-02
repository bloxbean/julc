package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.EqualityOperations;
import org.julclang.compiler.frontend.javac.EqualityRelation;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.NotPorted;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * ADR-068 D6's equality rules: where the JVM and UPLC would mean different things by an expression that javac accepts,
 * the expression is rejected. They apply wherever the subset does, so they are rules of the scan, not of the lowering.
 * <ul>
 *   <li>{@code JULC0086}: {@code ==} and {@code !=} between references, unless both are constant strings of the plain
 *       form below: their {@code Equals} is value equality on-chain and identity in Java.</li>
 *   <li>{@code JULC0090}: string {@code +} with an operand that is not a {@code String} (#296).</li>
 *   <li>{@code JULC0087}: an operation that compares elements or keys, where the JVM's equality for the compared static
 *       type reaches an array by identity. {@link EqualityOperations} is the table of operations,
 *       {@link ArrayIdentityEquality} the predicate. So does a collection of no known element or key type, which is the
 *       unresolved type D6 names, and an operand {@link EqualityRelation} cannot read.</li>
 *   <li>{@code JULC0078}: the same operations between types {@link EqualityRelation} finds unrelated: Java's answer is
 *       false, and on-chain {@code EqualsData} compares their representations, which can be equal.</li>
 * </ul>
 * {@code equals} between two types with no class in common does not lower, and is no rule yet: the corpora hold a validator
 * that writes it, so {@code JULC0078} there is a maintainer's decision. A raw receiver read through a cast stops at
 * the cast, and {@code ValuesLib} holds one: the cast's slice decides it.
 * Inside a {@code switch} guard ADR-067's evaluator decides {@code ==}, and {@link GuardRules} applies the others to a guard
 * that is not constant, after its own.
 */
final class EqualityRules {

    private final JavacFacts facts;
    private final ArrayIdentityEquality arrays;
    private final EqualityOperations operations;
    private final EqualityRelation relation;

    EqualityRules(JavacFacts facts) {
        this.facts = facts;
        this.arrays = facts.arrayEquality();
        this.operations = facts.equalityOperations();
        this.relation = facts.equalityRelation();
    }

    void check(BinaryTree tree) {
        switch (tree.getKind()) {
            case EQUAL_TO, NOT_EQUAL_TO -> requireValueEquality(tree);
            case PLUS -> requireStrings(tree);
            default -> {
            }
        }
    }

    /** JLS 15.21: with a primitive operand this is numeric or boolean equality; otherwise it is identity. */
    private void requireValueEquality(BinaryTree tree) {
        var left = facts.type(tree.getLeftOperand());
        var right = facts.type(tree.getRightOperand());
        if (left.getKind().isPrimitive() || right.getKind().isPrimitive()) return;
        if (left.getKind() == TypeKind.NULL || right.getKind() == TypeKind.NULL) return; // null has a rule of its own
        if (isPlainConstantString(tree.getLeftOperand(), new HashSet<>())
                && isPlainConstantString(tree.getRightOperand(), new HashSet<>())) {
            return;
        }
        var at = facts.location(tree);
        throw CompilerTypeDiagnostics.referenceEquality(at.fragment(), ArrayIdentityEquality.text(left), at);
    }

    /**
     * Built from string literals, constant {@code String} variables and string {@code +} alone, and so, recursively, is the
     * initializer of every constant variable it reaches: those have Java's value without integer semantics, where
     * {@code (2147483647 + 1 < 0 ? "a" : "b") == "a"} has none. A constant from a class file has no initializer, and
     * {@code getConstantValue()} is Java's exact value.
     */
    private boolean isPlainConstantString(ExpressionTree tree, Set<Element> reached) {
        return switch (tree) {
            case LiteralTree literal -> literal.getValue() instanceof String;
            case ParenthesizedTree parenthesized -> isPlainConstantString(parenthesized.getExpression(), reached);
            case BinaryTree binary -> binary.getKind() == Tree.Kind.PLUS
                    && isPlainConstantString(binary.getLeftOperand(), reached)
                    && isPlainConstantString(binary.getRightOperand(), reached);
            case IdentifierTree name -> isPlainConstantVariable(facts.element(name), reached);
            case MemberSelectTree select -> isPlainConstantVariable(facts.element(select), reached);
            default -> false;
        };
    }

    private boolean isPlainConstantVariable(Element element, Set<Element> reached) {
        if (!(element instanceof VariableElement variable && variable.getConstantValue() instanceof String)) return false;
        if (!reached.add(variable)) return true;
        var initializer = facts.initializer(variable);
        return initializer == null || isPlainConstantString(initializer, reached);
    }

    private void requireStrings(BinaryTree tree) {
        if (!isString(facts.type(tree))) return;
        for (var operand : new ExpressionTree[]{tree.getLeftOperand(), tree.getRightOperand()}) {
            var type = facts.type(operand);
            if (!isString(type)) {
                var at = facts.location(tree);
                throw CompilerTypeDiagnostics.stringConcatNonString(at.fragment(), ArrayIdentityEquality.text(type), at);
            }
        }
    }

    private boolean isString(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).equals(JavacNames.STRING);
    }

    /** {@code JULC0087} and {@code JULC0078}, at each compared type of an operation in {@link EqualityOperations}' table. */
    void check(MethodInvocationTree tree) {
        var method = (ExecutableElement) facts.element(tree.getMethodSelect());
        var argumentTypes = new ArrayList<TypeMirror>();
        for (var argument : tree.getArguments()) argumentTypes.add(facts.type(argument));
        for (var site : operations.sites(method, receiver(tree, method), argumentTypes)) {
            var result = arrays.check(site.compared(), site.julcCollection());
            if (result.rejected()) {
                throw CompilerTypeDiagnostics.arrayEquals(site.operation(), result.path(), facts.location(tree));
            }
            if (site.raw()) {
                if (isRawReceiver(tree, method)) {
                    throw CompilerTypeDiagnostics.arrayEquals(site.operation(), "a raw collection", facts.location(tree));
                }
            } else if (site.argument() != EqualityOperations.NO_ARGUMENT) {
                requireRelated(site, tree);
            }
        }
    }

    /** The other side of the comparison, by each type it can have: javac types a conditional argument by its target. */
    private void requireRelated(EqualityOperations.Site site, MethodInvocationTree tree) {
        for (var operand : EqualityRelation.operands(tree.getArguments().get(site.argument()))) {
            var result = relation.between(site.compared(), facts.type(operand));
            switch (result.kind()) {
                case RELATED -> {
                }
                case UNRESOLVED -> throw CompilerTypeDiagnostics.arrayEquals(site.operation(),
                        ArrayIdentityEquality.text(result.left()) + " against " + ArrayIdentityEquality.text(result.right())
                                + ", which cannot be decided", facts.location(tree));
                case UNRELATED -> {
                    if (!site.collectionOperation() && !result.inArgument()) {
                        throw NotPorted.at(facts, tree, "an equals between unrelated types");
                    }
                    throw CompilerTypeDiagnostics.equalsAlwaysFalse(site.operation(), ArrayIdentityEquality.text(result.left()),
                            ArrayIdentityEquality.text(result.right()), facts.location(tree));
                }
            }
        }
    }

    private boolean isRawReceiver(MethodInvocationTree tree, ExecutableElement method) {
        if (method.getModifiers().contains(Modifier.STATIC) || !(tree.getMethodSelect() instanceof MemberSelectTree select)) return false;
        var receiver = select.getExpression();
        while (receiver instanceof ParenthesizedTree parenthesized) receiver = parenthesized.getExpression();
        return !(receiver instanceof TypeCastTree);
    }

    /** The receiver's static type, the enclosing class's for a simple name, or null for a static call. */
    private TypeMirror receiver(MethodInvocationTree tree, ExecutableElement method) {
        if (method.getModifiers().contains(Modifier.STATIC)) return null;
        if (tree.getMethodSelect() instanceof MemberSelectTree select) return facts.type(select.getExpression());
        return facts.element(facts.enclosing(tree, Tree.Kind.CLASS, Tree.Kind.RECORD, Tree.Kind.INTERFACE)).asType();
    }
}
