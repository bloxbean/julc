package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.subset.GuardConstants;
import org.julclang.compiler.frontend.javac.subset.GuardTypes.JType;
import org.julclang.compiler.frontend.javac.subset.SwitchRules;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.TypeMethodRegistry;
import org.julclang.core.Constant;

import java.math.BigInteger;

/**
 * A {@code switch} guard's term (ADR-067 D1, ADR-068 I9). {@link SwitchRules} says what a guard may be; this says what the
 * lowering answers it, and lowers it.
 * <p>
 * <b>Evaluation order.</b> A guard is an expression, with the order of its parts: its pattern variable and the arm's fields
 * are bound before it. A constant has no effect. {@code instanceof} evaluates its operand once.
 */
final class Guards {

    private final JavacFacts facts;
    private final Typing typing;
    private final Lowering lowering;
    private final CallTargets targets;
    private final TypeMethodRegistry typeMethods;
    private final SwitchRules rules;
    private final GuardConstants constants;

    Guards(JavacFacts facts, Typing typing, Lowering lowering, CallTargets targets, TypeMethodRegistry typeMethods, SwitchRules rules) {
        this.facts = facts;
        this.typing = typing;
        this.lowering = lowering;
        this.targets = targets;
        this.typeMethods = typeMethods;
        this.rules = rules;
        this.constants = rules.constants();
    }

    /**
     * A guard's term (ADR-067 D1). Inside it, {@code instanceof} on a sealed type is a total dispatch, and a constant has
     * Java's width. Its Java type is established from the source, as {@code SwitchRules} says, not by javac alone (I2).
     */
    PirTerm guard(SwitchRules.Arm arm, Frame frame) {
        var type = rules.guardType(arm, leaf -> leafType(leaf, frame));
        rules.beforeGuard(arm, leaf -> leafType(leaf, frame));
        PirTerm guard;
        try {
            guard = lowering.expr(arm.guard(), frame.guard());
        } catch (CompilerException e) {
            // The reference collects an unresolved member and goes on, so what it says of the guard's type comes first.
            if (isUnresolvedMember(e)) rules.afterGuard(arm, type, false);
            throw e;
        }
        rules.afterGuard(arm, type, facts.registry().resolveNamed(typing.ofTerm(guard)) instanceof PirType.BoolType);
        return guard;
    }

    private static boolean isUnresolvedMember(CompilerException e) {
        return e.diagnostics().size() == 1 && DiagnosticCodes.UNRESOLVED_MEMBER_ACCESS.code().equals(e.diagnostics().getFirst().code());
    }

    /** What the reference types a guard's leaf by. An integer whose Java type it does not know it takes to be a {@code BigInteger}. */
    private JType leafType(ExpressionTree leaf, Frame frame) {
        PirType type;
        try {
            type = facts.registry().resolveNamed(referenceType(leaf, frame));
        } catch (CompilerException e) {
            return JType.UNKNOWN;
        }
        return switch (type) {
            case PirType.BoolType ignored -> JType.BOOLEAN;
            case PirType.IntegerType ignored -> JType.BIGINTEGER;
            case PirType.StringType ignored -> JType.STRING;
            case PirType.DataType ignored -> JType.UNKNOWN;
            default -> JType.OTHER;
        };
    }

    /**
     * The reference's type of an expression, read before it is lowered, which knows less than javac and must not know more
     * (I2): a variable's binder, a {@code new}, a helper's, a library method's and an accessor's result, what the registry
     * declares on the receiver it saw, and a literal. Of anything else it says Data.
     */
    PirType referenceType(ExpressionTree tree, Frame frame) {
        return switch (tree) {
            case IdentifierTree name -> {
                var binder = frame.scope().lookup(facts.element(name));
                yield binder == null ? new PirType.DataType() : facts.registry().resolveNamed(binder.type());
            }
            case MethodInvocationTree call -> callType(call, frame);
            case NewClassTree creation -> typing.of(creation);
            case LiteralTree literal -> literal.getValue() == null ? new PirType.DataType() : typing.of(literal);
            case TypeCastTree cast -> {
                var written = typing.of(cast);
                yield written instanceof PirType.DataType ? referenceType(cast.getExpression(), frame) : written;
            }
            default -> new PirType.DataType();
        };
    }

    private PirType callType(MethodInvocationTree call, Frame frame) {
        return switch (targets.classify(call)) {
            case CallTarget.Accessor ignored -> typing.of(call);
            case CallTarget.Wrapper ignored -> typing.of(call);
            case CallTarget.Local ignored -> typing.of(call);
            case CallTarget.Library ignored -> typing.of(call);
            case CallTarget.Intrinsic intrinsic -> intrinsic == CallTarget.Intrinsic.CAST ? typing.of(call) : new PirType.DataType();
            case CallTarget.TypeMethod target -> declaredReturn(call, target.method(), frame);
            case CallTarget.Equals ignored -> declaredReturn(call, "equals", frame);
            case CallTarget.Hof target -> declaredReturn(call, target.method(), frame);
            default -> new PirType.DataType();
        };
    }

    private PirType declaredReturn(MethodInvocationTree call, String method, Frame frame) {
        var receiver = ((MemberSelectTree) call.getMethodSelect()).getExpression();
        return typeMethods.resolveReturnType(facts.registry().resolveNamed(referenceType(receiver, frame)), method)
                .orElseGet(PirType.DataType::new);
    }

    /**
     * Inside a guard, an {@code int} or {@code long} constant expression of the verified subset is its Java value, so its
     * overflow, narrowing casts and constant variables keep Java's width even when the guard as a whole is not constant
     * (I9): the ordinary lowering evaluates them without it (T6). Null when {@code tree} is not such a constant.
     */
    PirTerm exact(ExpressionTree tree) {
        boolean shape = tree instanceof LiteralTree || tree instanceof UnaryTree || tree instanceof BinaryTree
                || tree instanceof TypeCastTree || tree instanceof IdentifierTree || tree instanceof MemberSelectTree
                || tree instanceof ConditionalExpressionTree || tree instanceof ParenthesizedTree;
        if (!shape) return null;
        var constant = constants.evaluate(tree);
        if (!constant.constant() || constant.unclassifiable()) return null;
        var kind = constant.value().kind();
        if (kind != GuardConstants.Kind.INT && kind != GuardConstants.Kind.LONG) return null;
        return new PirTerm.Const(Constant.integer(BigInteger.valueOf(constant.value().asLong())));
    }
}
