package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.SwitchResults;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The Java type of a guard expression as ADR-067 D4 establishes it, ported from {@code SwitchPatternChecker.sourceType}.
 * javac knows every type here, and that does not widen what a guard may hold (I2): what the reference establishes from a
 * declaration is read from the element javac bound, and what it asks its caller is asked of {@code leaf}, in the same
 * places. So {@code !l.isEmpty()} stays {@code JULC0072} where {@code l.isEmpty()} lowers.
 * <p>
 * Reads types and builds no term, so it has no evaluation order to state.
 */
public final class GuardTypes {

    /**
     * {@code BOOLEAN} for {@code boolean}, {@code UNKNOWN} when neither the shape nor {@code leaf} says. An {@code OTHER}
     * result is not established as legal.
     */
    public enum JType { BOOLEAN, BOOLEAN_BOX, NUMERIC, NUMERIC_BOX, BIGINTEGER, SMALL, STRING, OTHER, UNKNOWN }

    /**
     * The API methods the reference takes to return a primitive: any other it types as an integer it takes to be a
     * {@code BigInteger}, a reference, so comparing one is not established as legal. By the method javac bound.
     */
    private static final Set<String> PRIMITIVE_INTEGER_METHODS = Set.of(
            "signum", "compareTo", "intValue", "longValue", "intValueExact", "longValueExact", "bitLength", "bitCount",
            "getLowestSetBit", "size", "length", "hashCode", "at", "countOutputsAt", "countSetBits", "findFirstSetBit",
            "hexNibble", "constrTag", "indexByteString", "lengthOfByteString");

    private final JavacFacts facts;
    private final GuardConstants constants;

    GuardTypes(JavacFacts facts, GuardConstants constants) {
        this.facts = facts;
        this.constants = constants;
    }

    static boolean isBoolean(JType type) {
        return type == JType.BOOLEAN || type == JType.BOOLEAN_BOX;
    }

    private static boolean isNumeric(JType type) {
        return type == JType.NUMERIC || type == JType.NUMERIC_BOX;
    }

    /** An operand that is neither of the expected kinds: still open, unsupported, or not legal. */
    private static JType open(JType a, JType b) {
        if (a == JType.SMALL || b == JType.SMALL) return JType.SMALL;
        if (a == JType.UNKNOWN || b == JType.UNKNOWN) return JType.UNKNOWN;
        return JType.OTHER;
    }

    /** {@code tree}'s type, asking {@code leaf} of what no declaration says. */
    JType of(ExpressionTree tree, Function<ExpressionTree, JType> leaf) {
        return switch (tree) {
            case LiteralTree literal -> literal(literal);
            case ParenthesizedTree parenthesized -> of(parenthesized.getExpression(), leaf);
            case InstanceOfTree ignored -> JType.BOOLEAN;
            case UnaryTree unary -> unary(unary, leaf);
            case BinaryTree binary -> binary(binary, leaf);
            case ConditionalExpressionTree conditional -> conditional(conditional, leaf);
            case TypeCastTree cast -> cast(cast, leaf);
            case IdentifierTree name -> name(name, leaf);
            case MemberSelectTree select -> field(select, leaf);
            case MethodInvocationTree call -> call(call, leaf);
            case SwitchExpressionTree nested -> nested(nested, leaf);
            case NewClassTree creation -> declared(facts.type(creation.getIdentifier())) == JType.BOOLEAN_BOX ? JType.BOOLEAN_BOX : JType.OTHER;
            case LambdaExpressionTree ignored -> JType.OTHER;
            default -> leaf.apply(tree);
        };
    }

    private static JType literal(LiteralTree literal) {
        return switch (literal.getKind()) {
            case BOOLEAN_LITERAL -> JType.BOOLEAN;
            case INT_LITERAL, LONG_LITERAL -> JType.NUMERIC;
            case CHAR_LITERAL -> JType.SMALL;
            case STRING_LITERAL -> JType.STRING;
            default -> JType.OTHER;
        };
    }

    private JType unary(UnaryTree unary, Function<ExpressionTree, JType> leaf) {
        var t = of(unary.getExpression(), leaf);
        return switch (unary.getKind()) {
            case LOGICAL_COMPLEMENT -> isBoolean(t) ? JType.BOOLEAN : open(t, t);
            case UNARY_PLUS, UNARY_MINUS, BITWISE_COMPLEMENT -> isNumeric(t) ? JType.NUMERIC : open(t, t);
            default -> JType.OTHER;
        };
    }

    private JType conditional(ConditionalExpressionTree conditional, Function<ExpressionTree, JType> leaf) {
        var c = of(conditional.getCondition(), leaf);
        var a = of(conditional.getTrueExpression(), leaf);
        var b = of(conditional.getFalseExpression(), leaf);
        if (c == JType.SMALL || a == JType.SMALL || b == JType.SMALL) return JType.SMALL;
        if (!isBoolean(c) && c != JType.UNKNOWN) return JType.OTHER; // JLS 15.25: the condition must be boolean
        JType result;
        if (isBoolean(a) && isBoolean(b)) {
            result = a == JType.BOOLEAN_BOX && b == JType.BOOLEAN_BOX ? JType.BOOLEAN_BOX : JType.BOOLEAN;
        } else if (isNumeric(a) && isNumeric(b)) {
            result = JType.NUMERIC; // binary numeric promotion (JLS 15.25.2)
        } else if (a == JType.STRING && b == JType.STRING) {
            result = JType.STRING;
        } else if (a == JType.UNKNOWN || b == JType.UNKNOWN) {
            result = JType.UNKNOWN;
        } else {
            result = JType.OTHER; // boolean with another type is a reference type (JLS 15.25), never boolean
        }
        return c == JType.UNKNOWN && result != JType.OTHER ? JType.UNKNOWN : result;
    }

    private JType cast(TypeCastTree cast, Function<ExpressionTree, JType> leaf) {
        var target = declared(facts.type(cast.getType()));
        var operand = of(cast.getExpression(), leaf);
        if (isBoolean(target)) return isBoolean(operand) ? target : open(operand, operand);
        if (target == JType.NUMERIC) return isNumeric(operand) ? JType.NUMERIC : open(operand, operand);
        return target == JType.SMALL ? JType.SMALL : JType.OTHER; // a reference cast loses its run-time check on-chain (#288)
    }

    private JType binary(BinaryTree binary, Function<ExpressionTree, JType> leaf) {
        var a = of(binary.getLeftOperand(), leaf);
        var b = of(binary.getRightOperand(), leaf);
        if (a == JType.SMALL || b == JType.SMALL) return JType.SMALL;
        return switch (binary.getKind()) {
            case EQUAL_TO, NOT_EQUAL_TO -> {
                // A primitive comparison, or one operand unboxed (JLS 15.21); two references compare identity, which
                // JuLC does not model: rejected unless both are compile-time constant strings (#289).
                if (isBoolean(a) && isBoolean(b) && !(a == JType.BOOLEAN_BOX && b == JType.BOOLEAN_BOX)) yield JType.BOOLEAN;
                if (isNumeric(a) && isNumeric(b) && !(a == JType.NUMERIC_BOX && b == JType.NUMERIC_BOX)) yield JType.BOOLEAN;
                if (a == JType.STRING && b == JType.STRING && isConstant(binary.getLeftOperand()) && isConstant(binary.getRightOperand())) {
                    yield JType.BOOLEAN;
                }
                yield open(a, b);
            }
            case LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL -> isNumeric(a) && isNumeric(b) ? JType.BOOLEAN : open(a, b);
            case CONDITIONAL_AND, CONDITIONAL_OR -> isBoolean(a) && isBoolean(b) ? JType.BOOLEAN : open(a, b);
            case AND, OR, XOR -> isBoolean(a) && isBoolean(b) ? JType.BOOLEAN : isNumeric(a) && isNumeric(b) ? JType.NUMERIC : open(a, b);
            case PLUS -> a == JType.STRING || b == JType.STRING ? JType.STRING : isNumeric(a) && isNumeric(b) ? JType.NUMERIC : open(a, b);
            default -> isNumeric(a) && isNumeric(b) ? JType.NUMERIC : open(a, b);
        };
    }

    private boolean isConstant(ExpressionTree tree) {
        var result = constants.evaluate(tree);
        return result.constant() && !result.unclassifiable();
    }

    /** A local, a parameter or a field of a class around the name; a pattern variable has a record type. */
    private JType name(IdentifierTree name, Function<ExpressionTree, JType> leaf) {
        if (!(facts.elementOrNull(name) instanceof VariableElement variable)) return leaf.apply(name);
        return switch (variable.getKind()) {
            case PARAMETER -> declared(variable.asType());
            case LOCAL_VARIABLE -> {
                var declaration = facts.declaration(variable);
                if (declaration.getType() != null && !facts.isGenerated(declaration.getType())) yield declared(variable.asType());
                yield declaration.getInitializer() == null ? JType.UNKNOWN : of(declaration.getInitializer(), leaf);
            }
            case FIELD -> facts.encloses(variable.getEnclosingElement(), name) ? declared(variable.asType()) : leaf.apply(name);
            default -> JType.OTHER;
        };
    }

    private JType field(MemberSelectTree select, Function<ExpressionTree, JType> leaf) {
        if (select.getExpression() instanceof IdentifierTree qualifier && facts.elementOrNull(qualifier) instanceof TypeElement type
                && facts.unitOf(type) == facts.unitOfTree(select)
                && facts.elementOrNull(select) instanceof VariableElement field && type.equals(field.getEnclosingElement())) {
            return declared(field.asType());
        }
        return leaf.apply(select);
    }

    private JType call(MethodInvocationTree call, Function<ExpressionTree, JType> leaf) {
        var method = unitMethod(call);
        if (method != null) return declared(method.getReturnType());
        var select = call.getMethodSelect() instanceof MemberSelectTree member ? member : null;
        if (call.getArguments().isEmpty() && select != null) {
            var component = component(select.getExpression(), select.getIdentifier().toString());
            if (component != null) return declared(component.asType());
        }
        var t = leaf.apply(call);
        if (t == JType.BIGINTEGER && select != null && PRIMITIVE_INTEGER_METHODS.contains(select.getIdentifier().toString())) {
            return JType.NUMERIC;
        }
        return t;
    }

    /** The method of an unqualified or own-class-qualified call, where the class declares that name once; or null. */
    private ExecutableElement unitMethod(MethodInvocationTree call) {
        String name;
        TypeElement owner;
        if (call.getMethodSelect() instanceof MemberSelectTree select) {
            if (!(select.getExpression() instanceof IdentifierTree qualifier && facts.elementOrNull(qualifier) instanceof TypeElement type
                    && facts.unitOf(type) == facts.unitOfTree(call))) {
                return null;
            }
            name = select.getIdentifier().toString();
            owner = type;
        } else {
            name = ((IdentifierTree) call.getMethodSelect()).getName().toString();
            owner = (TypeElement) facts.element(call.getMethodSelect()).getEnclosingElement();
            if (!facts.encloses(owner, call)) return null;
        }
        var named = new ArrayList<ExecutableElement>();
        for (var method : ElementFilter.methodsIn(owner.getEnclosedElements())) {
            if (method.getSimpleName().contentEquals(name) && facts.declaredInSource(method)) named.add(method);
        }
        return named.size() == 1 ? named.getFirst() : null;
    }

    /**
     * The tree that declares the type of {@code tree}, a name or an accessor: what {@code instanceof} needs of its operand.
     * Only a parameter, a pattern variable, a local with a written type, a {@code var} of a {@code new}, a {@code new}, and a
     * component of a record of this unit say one; a field does not. Null otherwise.
     */
    Tree declaredTypeTree(ExpressionTree tree) {
        return switch (tree) {
            case ParenthesizedTree parenthesized -> declaredTypeTree(parenthesized.getExpression());
            case IdentifierTree name when facts.elementOrNull(name) instanceof VariableElement variable -> {
                if (variable.getKind() == ElementKind.FIELD) yield null;
                var declaration = facts.declaration(variable);
                if (declaration == null) yield null;
                if (declaration.getType() != null && !facts.isGenerated(declaration.getType())) yield declaration.getType();
                yield declaration.getInitializer() instanceof NewClassTree creation ? creation.getIdentifier() : null;
            }
            case MethodInvocationTree call when call.getArguments().isEmpty() && call.getMethodSelect() instanceof MemberSelectTree select -> {
                var component = component(select.getExpression(), select.getIdentifier().toString());
                yield component == null ? null : facts.declaration(component).getType();
            }
            case NewClassTree creation -> creation.getIdentifier();
            default -> null;
        };
    }

    /** The field of component {@code name} of the record of this unit, written by its simple name, that {@code receiver} declares; or null. */
    private VariableElement component(ExpressionTree receiver, String name) {
        var type = declaredTypeTree(receiver);
        if (type instanceof ParameterizedTypeTree parameterized) type = parameterized.getType();
        if (!(type instanceof IdentifierTree written && facts.elementOrNull(written) instanceof TypeElement record
                && record.getKind() == ElementKind.RECORD && facts.unitOf(record) == facts.unitOfTree(receiver))) {
            return null;
        }
        for (var field : ElementFilter.fieldsIn(record.getEnclosedElements())) {
            if (field.getSimpleName().contentEquals(name) && facts.declaration(field) != null) return field;
        }
        return null;
    }

    private JType nested(SwitchExpressionTree nested, Function<ExpressionTree, JType> leaf) {
        var results = new ArrayList<JType>();
        for (var result : SwitchResults.of(nested)) results.add(of(result, leaf));
        return ofResults(results);
    }

    private static JType ofResults(List<JType> results) {
        if (results.isEmpty()) return JType.UNKNOWN;
        if (results.stream().allMatch(GuardTypes::isBoolean)) return JType.BOOLEAN;
        if (results.contains(JType.SMALL)) return JType.SMALL;
        if (results.contains(JType.UNKNOWN)) return JType.UNKNOWN;
        return JType.OTHER;
    }

    /** The guard type of a declared Java type, by identity; a type variable is unknown. */
    JType declared(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> JType.BOOLEAN;
            case INT, LONG -> JType.NUMERIC;
            case CHAR, BYTE, SHORT -> JType.SMALL;
            case TYPEVAR -> JType.UNKNOWN;
            case DECLARED -> switch (facts.identity((DeclaredType) type)) {
                case JavacNames.BOOLEAN -> JType.BOOLEAN_BOX;
                case JavacNames.STRING -> JType.STRING;
                case JavacNames.BOXED_INTEGER, JavacNames.BOXED_LONG -> JType.NUMERIC_BOX;
                case "java.lang.Short", "java.lang.Byte", "java.lang.Character" -> JType.SMALL;
                case JavacNames.BIG_INTEGER -> JType.BIGINTEGER;
                default -> JType.OTHER;
            };
            default -> JType.OTHER;
        };
    }

    /** Whether {@code type} is an array, or anything else no class type names. */
    static boolean isClassType(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED;
    }
}
