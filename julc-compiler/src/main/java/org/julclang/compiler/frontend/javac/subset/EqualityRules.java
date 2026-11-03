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

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * ADR-068 D6's equality rules: where the JVM and UPLC would mean different things by an expression that javac accepts,
 * the expression is rejected. The scan applies them wherever the subset does, and {@code Operators} applies
 * {@link #requireValueEquality} to each {@code ==} it lowers, which is what holds them at every position of a guard.
 * <ul>
 *   <li>{@code JULC0086}: {@code ==} and {@code !=} between references, unless both are constant strings of the plain
 *       form below: their {@code Equals} is value equality on-chain and identity in Java. In a guard too: a constant
 *       outside that form stops there unless the whole guard is constant, since a term of it can read otherwise than Java,
 *       and no guard evaluator takes it over.</li>
 *   <li>{@code JULC0090}: string {@code +} with an operand that is not a {@code String} (#296).</li>
 *   <li>{@code JULC0087}: an operation that compares elements or keys, where the JVM's equality for the compared static
 *       type reaches an array by identity. {@link EqualityOperations} is the table of operations,
 *       {@link ArrayIdentityEquality} the predicate. So does a collection of no known element or key type, which is the
 *       unresolved type D6 names, and an operand {@link EqualityRelation} cannot read.</li>
 *   <li>{@code JULC0078}: the same operations between types {@link EqualityRelation} finds unrelated: Java's answer is
 *       false, and on-chain {@code EqualsData} compares their representations, which can be equal.</li>
 * </ul>
 * {@code equals} between two types with no class in common is {@code JULC0078} too (ADR-067 D5, r4; ADR-068 M5): Java's
 * answer is false, and on-chain the reference compares representations, which can be equal or fail. The external
 * {@code UVerifyProxy.java:70} writes {@code TxId.equals(byte[])}, and stops here; see {@code adr/evidence/068-m5a.md}.
 * A raw receiver read through a cast stops at the cast, and {@code ValuesLib} holds one: the cast's slice decides it.
 * Inside a {@code switch} guard the guard's own rules speak first: {@link GuardRules} applies the others to a guard that is
 * not constant, after its own, and {@code Operators} applies {@link #requireValueEquality} to each {@code ==} that is lowered,
 * so no position in a guard goes unread.
 */
public final class EqualityRules {

    private final JavacFacts facts;
    private final ArrayIdentityEquality arrays;
    private final EqualityOperations operations;
    private final EqualityRelation relation;

    public EqualityRules(JavacFacts facts) {
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
    public void requireValueEquality(BinaryTree tree) {
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

    /** The package of the stdlib's libraries, which only its bundled sources declare. */
    private static final String STDLIB_LIBRARIES = "org.julclang.stdlib.lib.";

    /** Whether {@code tree} is in a generic static method of a unit compiled as a library: a template (ADR-059). */
    private boolean inTemplate(Tree tree) {
        for (var at = facts.enclosing(tree, Tree.Kind.METHOD); at != null; at = facts.enclosing(at, Tree.Kind.METHOD)) {
            var method = (ExecutableElement) facts.element(at);
            if (!method.getTypeParameters().isEmpty() && method.getModifiers().contains(Modifier.STATIC)
                    && facts.inOfferedLibrary(method)) {
                return true;
            }
        }
        return false;
    }

    private boolean isString(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).equals(JavacNames.STRING);
    }

    /**
     * {@code JULC0087} and {@code JULC0078}, at each compared type of an operation in {@link EqualityOperations}' table. A
     * generic library method is exempt: Java cannot call it, so only a producer's request instantiates it, and equality is the
     * producer's (D6, "Generic library methods").
     */
    void check(MethodInvocationTree tree) {
        if (inTemplate(tree)) return;
        var method = (ExecutableElement) facts.element(tree.getMethodSelect());
        var argumentTypes = new ArrayList<TypeMirror>();
        for (var argument : tree.getArguments()) argumentTypes.add(facts.type(argument));
        for (var site : operations.sites(method, receiver(tree, method), argumentTypes)) {
            var result = arrays.check(site.compared(), site.julcCollection());
            if (result.kind() == ArrayIdentityEquality.Kind.ARRAY_IDENTITY) {
                throw CompilerTypeDiagnostics.arrayEquals(site.operation(), result.path(), facts.location(tree));
            }
            if (result.rejected()) {
                throw CompilerTypeDiagnostics.equalityUnread(site.operation(), result.path(), facts.location(tree));
            }
            if (site.raw()) {
                if (isRawReceiver(tree, method)) {
                    throw CompilerTypeDiagnostics.equalityUnread(site.operation(), "a raw collection", facts.location(tree));
                }
            } else if (site.argument() != EqualityOperations.NO_ARGUMENT) {
                requireRelated(site, tree);
            }
        }
    }

    /** The other side of the comparison, by each type it can have: javac types a conditional argument by its target. */
    private void requireRelated(EqualityOperations.Site site, MethodInvocationTree tree) {
        var argument = tree.getArguments().get(site.argument());
        // Arms of two types have no type, whatever they are compared with (ArmTypes): that rule speaks first.
        ArmTypes.requireOneType(facts, argument);
        var results = new ArrayList<EqualityRelation.Result>();
        for (var operand : EqualityRelation.operands(argument)) results.add(relation.between(site.compared(), facts.type(operand)));
        // JULC0078 only where Java's answer is false whatever the values: every operand unrelated. Where one can be equal and
        // another cannot, or the relation cannot be decided, it is JULC0087 (ADR-067 I12: unknown sets keep no constant answer).
        if (!results.isEmpty() && results.stream().allMatch(result -> result.kind() == EqualityRelation.Kind.UNRELATED)) {
            var result = results.getFirst();
            throw CompilerTypeDiagnostics.equalsAlwaysFalse(site.operation(),
                    ArrayIdentityEquality.text(result.left()), ArrayIdentityEquality.text(result.right()), facts.location(tree));
        }
        for (var result : results) {
            if (result.kind() != EqualityRelation.Kind.RELATED) {
                throw CompilerTypeDiagnostics.equalityUnread(site.operation(),
                        ArrayIdentityEquality.text(result.left()) + " against " + ArrayIdentityEquality.text(result.right())
                                + ", which cannot be decided", facts.location(tree));
            }
        }
    }

    private boolean isRawReceiver(MethodInvocationTree tree, ExecutableElement method) {
        if (method.getModifiers().contains(Modifier.STATIC) || !(tree.getMethodSelect() instanceof MemberSelectTree select)) return false;
        var receiver = select.getExpression();
        while (receiver instanceof ParenthesizedTree parenthesized) receiver = parenthesized.getExpression();
        // The stdlib views a Value as its map of Data through a raw cast (ValuesLib._containsPolicy); its keys are Data, which
        // compare by value. Only a bundled source declares that package (D3, JULC0081).
        return !(receiver instanceof TypeCastTree && inTheStdlib(tree));
    }

    private boolean inTheStdlib(Tree tree) {
        var type = facts.enclosing(tree, Tree.Kind.CLASS);
        return type != null && facts.identity((TypeElement) facts.element(type)).startsWith(STDLIB_LIBRARIES);
    }

    /** The receiver's static type, the enclosing class's for a simple name, or null for a static call. */
    private TypeMirror receiver(MethodInvocationTree tree, ExecutableElement method) {
        if (method.getModifiers().contains(Modifier.STATIC)) return null;
        if (tree.getMethodSelect() instanceof MemberSelectTree select) return facts.type(select.getExpression());
        return facts.element(facts.enclosing(tree, Tree.Kind.CLASS, Tree.Kind.RECORD, Tree.Kind.INTERFACE)).asType();
    }
}
