package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

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
     * The function of {@code call}, a list method. In parentheses, in a conditional or under {@code var} the reference has no
     * element type and takes the parameter as Data whatever javac says, so those do not lower.
     *
     * @param receiver      the receiver as the reference sees it
     * @param encodesResult whether the handler conses the result into a list, which is {@code map}'s
     */
    PirTerm function(MethodInvocationTree call, PirType.ListType receiver, boolean encodesResult, Frame frame) {
        var argument = call.getArguments().getFirst();
        if (!(argument instanceof LambdaExpressionTree lambda)) {
            throw NotPorted.at(facts, argument, "a function that is no lambda at the call");
        }
        var parameter = lambda.getParameters().getFirst();
        var element = facts.element(parameter);
        boolean written = !facts.isGenerated(parameter.getType());
        if (!written && !facts.location(parameter).fragment().equals(parameter.getName().toString())) {
            throw NotPorted.at(facts, parameter, "a lambda parameter written var");
        }
        PirType held = held(parameter, written);
        PirType seen = written ? held : elementSeen(call, receiver);
        boolean decodes = isDecoded(held);
        var data = frame.scope().bind(element, decodes ? new PirType.DataType() : seen);
        var scope = decodes ? data.rebind(element, seen) : data;
        var body = body(lambda, frame.lambda(scope));
        if (encodesResult) body = encoded(body, call, lambda);
        if (decodes) body = terms.decoded(element, data.lookup(element), scope.lookup(element), held, body);
        return terms.lam(data.lookup(element), body);
    }

    /**
     * What an inferred parameter reads as to the reference. Where a simple name is the receiver it lowers the lambda once more
     * first, untyped ({@link CallTargets#hasFirstPass}), and keeps a throw from that pass, so a {@code switch} on an element of
     * a sealed interface is {@code JULC0007} there, whatever javac knows (I2). What that pass rejects on any other element
     * is not ported: the second pass types the parameter, and javac's type is the one Java runs, so the rule is ADR-068's
     * one deliberate widening.
     */
    private PirType elementSeen(MethodInvocationTree call, PirType.ListType receiver) {
        return targets.hasFirstPass(call) && facts.registry().resolveNamed(receiver.elemType()) instanceof PirType.SumType
                ? new PirType.DataType() : receiver.elemType();
    }

    /** What the parameter holds: the type javac gave it, as a declaration writes it or as javac inferred it. */
    private PirType held(VariableTree parameter, boolean written) {
        var type = facts.element(parameter).asType();
        if (written) return facts.registry().resolveNamed(facts.pirType(type, parameter.getType()));
        try {
            return facts.registry().resolveNamed(facts.valueType(type, parameter));
        } catch (CompilerException e) {
            throw NotPorted.at(facts, parameter, "a lambda parameter of type " + ArrayIdentityEquality.text(type));
        }
    }

    /** Whether an element of this type is not what the body reads. A record, a sum and {@code Optional} are Data already. */
    private static boolean isDecoded(PirType type) {
        return type instanceof PirType.IntegerType || type instanceof PirType.ByteStringType || type instanceof PirType.BoolType
                || type instanceof PirType.StringType || type instanceof PirType.ListType || type instanceof PirType.MapType;
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
        return NotPorted.at(facts, tree);
    }
}
