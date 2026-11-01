package org.julclang.compiler.validate;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.type.PrimitiveType;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.IntegerLiterals;
import org.julclang.compiler.pir.JavaConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A subset rule ({@code JULC0095}): what is not lowered holds only a constant. A static field of the compiled class is
 * lowered, and so is a static field of a library class. Any other field initializer (a {@code @Param} field, an instance
 * field, a field of another type) holds only a literal, a {@code new byte[]{...}} of literals, an API constant or a
 * constant variable, and an initializer block is empty.
 * <p>
 * The JavaParser frontend's side of the rule: this frontend has names, not elements, so an API constant and
 * {@code Boolean.valueOf} are matched by their spelling. The javac frontend's side matches by identity.
 */
public final class UnloweredInitializers {

    private static final Set<String> INTEGER_CONSTANTS = Set.of("ZERO", "ONE", "TWO", "TEN");

    private UnloweredInitializers() {
    }

    /** The rejections of {@code unit}, where {@code lowered} says which fields are lowered. */
    public static List<CompilerDiagnostic> check(CompilationUnit unit, Predicate<FieldDeclaration> lowered) {
        var file = unit.getStorage().map(storage -> storage.getFileName()).orElse("<unknown>");
        var rejected = new ArrayList<CompilerDiagnostic>();
        for (var field : unit.findAll(FieldDeclaration.class)) {
            if (lowered.test(field)) continue;
            for (var variable : field.getVariables()) {
                var initializer = variable.getInitializer();
                if (initializer.isEmpty() || holdsAConstant(field, initializer.get())) continue;
                rejected.add(rejection("The initializer of field '" + variable.getNameAsString() + "'", initializer.get(), file));
            }
        }
        for (var block : unit.findAll(InitializerDeclaration.class)) {
            if (!block.getBody().getStatements().isEmpty()) rejected.add(rejection("An initializer block", block, file));
        }
        return rejected;
    }

    private static CompilerDiagnostic rejection(String what, Node node, String file) {
        var info = DiagnosticCodes.INITIALIZER_NOT_LOWERED;
        int line = node.getBegin().map(position -> position.line).orElse(0);
        int column = node.getBegin().map(position -> position.column).orElse(0);
        return new CompilerDiagnostic(info.level(), info.format(what), file, line, column, info.fix(), info.code());
    }

    private static boolean holdsAConstant(FieldDeclaration field, Expression initializer) {
        return isConstantVariable(field, initializer) || isAllowed(initializer);
    }

    /** A field Java treats as a constant: {@code final}, of a primitive type or {@code String}, with a constant initializer. */
    private static boolean isConstantVariable(FieldDeclaration field, Expression initializer) {
        var type = field.getCommonType();
        return field.isFinal() && (type instanceof PrimitiveType || type.asString().equals("String")
                || type.asString().equals("java.lang.String")) && JavaConstants.isConstant(initializer);
    }

    private static boolean isAllowed(Expression expression) {
        return switch (expression) {
            case EnclosedExpr enclosed -> isAllowed(enclosed.getInner());
            case LiteralExpr ignored -> true;
            case UnaryExpr unary -> unary.getOperator() == UnaryExpr.Operator.MINUS && unary.getExpression() instanceof LiteralExpr;
            case ArrayCreationExpr array -> isByteLiteral(array);
            case NameExpr name -> JavaConstants.isConstant(name);
            case FieldAccessExpr access -> isApiConstant(access) || JavaConstants.isConstant(access);
            case MethodCallExpr call -> isBooleanValueOf(call);
            default -> false;
        };
    }

    private static boolean isByteLiteral(ArrayCreationExpr array) {
        return array.getElementType().isPrimitiveType()
                && array.getElementType().asPrimitiveType().getType() == PrimitiveType.Primitive.BYTE
                && array.getInitializer().isPresent()
                && array.getInitializer().get().getValues().stream().allMatch(value -> IntegerLiterals.byteElement(value) != null);
    }

    private static boolean isApiConstant(FieldAccessExpr access) {
        if (!(access.getScope() instanceof NameExpr type)) return false;
        return switch (type.getNameAsString()) {
            case "BigInteger" -> INTEGER_CONSTANTS.contains(access.getNameAsString());
            case "PlutusData" -> access.getNameAsString().equals("UNIT");
            default -> false;
        };
    }

    /** {@code Boolean.valueOf(true)}: how a {@code @Param boolean} is written so that it is no constant variable (D14). */
    private static boolean isBooleanValueOf(MethodCallExpr call) {
        return call.getNameAsString().equals("valueOf") && call.getArguments().size() == 1
                && call.getArgument(0) instanceof BooleanLiteralExpr
                && call.getScope().orElse(null) instanceof NameExpr type && type.getNameAsString().equals("Boolean");
    }
}
