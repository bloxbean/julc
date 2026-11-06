package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * A lambda, passed to the list method that applies it (ADR-068 M3). That is the one place a function exists on-chain, since
 * D6 adds no functional local. {@code PirHofBuilders} applies it to each element as Data, so the parameter is decoded by the
 * type javac gave it, however it is written, and the result of {@code map} is encoded for the list it joins. {@link Calls}
 * lowers the call around it.
 * <p>
 * <b>Evaluation order.</b> Java evaluates the receiver, then makes the lambda, which evaluates nothing, then calls it once
 * per element, from the first. A term here is a {@code Lam}, so its body runs at each application and not where it stands,
 * and what it captures is the value bound around it, which javac has shown to be effectively final. {@code map} and
 * {@code filter} apply it to every element, as Java does. {@code find} stops at the first match, as Java does. {@code any}
 * and {@code all} are folds over the whole list and do not stop, where Java does: a later element that fails or traces
 * shows, and only there (T7). {@code Adr068LambdasHarnessTest} pins it.
 * <p>
 * The parameter is bound twice when it decodes, by a {@code Lam} that holds Data and a {@code let} that holds the value,
 * under one name, as the reference does: the {@code let} reads the {@code Lam}. What the body sees of the parameter is the
 * reference's reading, the type written or the type of the elements of the receiver as it sees it, so a member a reference
 * read cannot find stays {@code JULC0055} (I2). What it holds is javac's.
 * <p>
 * An inferred parameter has that type wherever the lambda stands. The reference lowers a lambda at a simple-name receiver
 * once more first, untyped, and keeps a throw from that pass; that pass is not ported, and what it alone rejects is
 * ADR-068's one deliberate widening.
 */
final class Lambdas {

    private final JavacFacts facts;
    private final Terms terms;
    private final Typing typing;
    private final CallTargets targets;
    private final Lowering lowering;

    Lambdas(JavacFacts facts, Terms terms, Typing typing, CallTargets targets, Lowering lowering) {
        this.facts = facts;
        this.terms = terms;
        this.typing = typing;
        this.targets = targets;
        this.lowering = lowering;
    }

    /**
     * The function of {@code call}, a list method: a lambda written at the call, in parentheses or not, or a conditional of
     * such functions, which is the function its condition picks (ADR-068 M5: the reference takes such a parameter as Data
     * whatever javac says; here it has javac's type, as Java). A parameter written {@code var} is inferred, and one javac
     * types {@code Object}, of a raw list, holds the element as the list has it: Data. Any other function, a call or a name,
     * is {@code JULC0125}: the reference has no such value either.
     *
     * @param receiver      the receiver as the reference sees it
     * @param encodesResult whether the handler conses the result into a list, which is {@code map}'s
     */
    PirTerm function(MethodInvocationTree call, PirType.ListType receiver, boolean encodesResult, Frame frame) {
        return function(call, call.getArguments().getFirst(), receiver, encodesResult, frame);
    }

    private PirTerm function(MethodInvocationTree call, ExpressionTree argument, PirType.ListType receiver, boolean encodesResult,
                             Frame frame) {
        if (argument instanceof ParenthesizedTree parenthesized) {
            return function(call, parenthesized.getExpression(), receiver, encodesResult, frame);
        }
        if (argument instanceof ConditionalExpressionTree conditional) {
            return lowering.conditions().choice(conditional.getCondition(), frame).choose(
                    inner -> function(call, conditional.getTrueExpression(), receiver, encodesResult, inner),
                    inner -> function(call, conditional.getFalseExpression(), receiver, encodesResult, inner));
        }
        if (!(argument instanceof LambdaExpressionTree lambda)) {
            throw facts.rejection(DiagnosticCodes.EXPRESSION_UNSUPPORTED, argument,
                    "a function that is not a lambda written at the call, " + facts.location(argument).fragment());
        }
        var parameter = lambda.getParameters().getFirst();
        var element = facts.element(parameter);
        boolean written = !facts.isInferred(parameter);
        PirType held = held(parameter, written, receiver);
        PirType seen = written ? held : receiver.elemType();
        boolean decodes = Typing.isDecoded(held);
        var data = frame.scope().bind(element, decodes ? new PirType.DataType() : seen);
        var scope = decodes ? data.rebind(element, Typing.holding(held, seen), seen) : data;
        var body = body(lambda, frame.lambda(scope));
        // A block's returns are one choice: one representation, as a conditional's arms (review 2, F2).
        if (lambda.getBody() instanceof BlockTree block) {
            typing.requireOneRepresentation(lambda, "a lambda", typing.held(Typing.returns(block)));
        }
        if (encodesResult) body = encoded(body, call, lambda);
        if (decodes) body = terms.decoded(element, data.lookup(element), scope.lookup(element), held, body);
        var function = terms.lam(data.lookup(element), body);
        // A parameter decoded from Data is the decode's binder, not the function's, and that is not proven: it is not shown,
        // with the reference's reason.
        if (decodes) terms.undeclared(element, "generated HOF parameter decoding is not identity-proven");
        else terms.declared(element, function, data.lookup(element).type());
        return function;
    }

    /**
     * What the parameter holds: the type javac gave it, as a declaration writes it or as javac inferred it. An inferred
     * {@code Object}, the element of a raw list, holds what the list holds, Data; any other type with no representation is
     * {@code JULC0012}.
     */
    private PirType held(VariableTree parameter, boolean written, PirType.ListType receiver) {
        var type = facts.element(parameter).asType();
        if (written) return facts.registry().resolveNamed(facts.pirType(type, parameter.getType()));
        try {
            return facts.registry().resolveNamed(facts.valueType(type, parameter));
        } catch (CompilerException e) {
            if (isObject(type) && receiver.elemType() instanceof PirType.DataType) return new PirType.DataType();
            throw facts.rejection(DiagnosticCodes.TYPE_RESOLUTION_FAILED, parameter,
                    "a lambda parameter of type " + ArrayIdentityEquality.text(type));
        }
    }

    private boolean isObject(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && facts.identity(facts.typeElement(type)).equals(JavacNames.OBJECT);
    }

    private PirTerm body(LambdaExpressionTree lambda, Frame frame) {
        return lambda.getBody() instanceof BlockTree block ? lowering.block(block, frame)
                : lowering.expr((ExpressionTree) lambda.getBody(), frame);
    }

    /**
     * {@code body} as the Data the list holds, by javac's element type of the result, or what the term says where that says
     * only Data, since a builtin can leave a list under {@code PlutusData}.
     */
    private PirTerm encoded(PirTerm body, MethodInvocationTree call, LambdaExpressionTree lambda) {
        var type = ((PirType.ListType) typing.of(call)).elemType();
        if (type instanceof PirType.DataType) type = typing.ofTerm(body);
        var where = lambda.getBody() instanceof ExpressionTree expression ? expression : lambda;
        try {
            return type instanceof PirType.DataType ? body : PirHelpers.wrapEncode(body, facts.registry().resolveNamed(type));
        } catch (CompilerException e) {
            throw lowering.located(e, where);
        }
    }

    /**
     * Whether what {@code find}, which is {@code call}, returns is read as Java reads it, the element. {@code PirHofBuilders}
     * leaves an {@code Optional}, so a read takes its field, and fails where Java returns null. {@code PlutusData.cast(...,
     * Optional.class)} is the view of that value D6 allows, and a value nothing reads is not read.
     */
    boolean readsElement(MethodInvocationTree call) {
        var parent = facts.parent(call);
        if (parent instanceof ExpressionStatementTree) return false;
        return !(parent instanceof MethodInvocationTree cast && targets.classify(cast) == CallTarget.Intrinsic.CAST
                && cast.getArguments().getFirst() == call && typing.of(cast) instanceof PirType.OptionalType);
    }

    /**
     * A lambda anywhere else. To a declared local, the reference's rejection is of the local's type, since no functional
     * interface is a type of the subset. Nothing else reaches a term here.
     */
    CompilerException outsideCall(LambdaExpressionTree tree) {
        if (facts.parent(tree) instanceof VariableTree variable && !facts.isGenerated(variable.getType())) {
            facts.pirType(facts.element(variable).asType(), variable.getType());
        }
        // Held for later, through a conditional or parentheses, it is stored: the reference's own rule (JULC0022). Elsewhere it
        // is the reference's unsupported expression (I2a).
        Tree at = facts.parent(tree);
        while (at instanceof ConditionalExpressionTree || at instanceof ParenthesizedTree) at = facts.parent(at);
        if (at instanceof VariableTree) return facts.rejection(DiagnosticCodes.LAMBDA_STORED_IN_VARIABLE_UNSUPPORTED, tree);
        return facts.rejection(DiagnosticCodes.EXPRESSION_UNSUPPORTED, tree, "a lambda outside a call that takes it, " + facts.location(tree).fragment());
    }
}
