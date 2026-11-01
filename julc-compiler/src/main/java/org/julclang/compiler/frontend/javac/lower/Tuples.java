package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.LibraryMethodRegistry;

import javax.lang.model.element.ExecutableElement;
import java.util.ArrayList;
import java.util.List;

/**
 * The type of a {@code new Tuple2} or {@code new Tuple3}, which says how each component is encoded. An explicit one is
 * what it writes. A diamond ({@code #299}) takes each component from its destination where one is written, else from its
 * argument. A component read back as Data must already be Data, so a raw tuple takes only that, as does a raw destination
 * (review F711): javac types those components {@code Object}, and nothing can encode one.
 * <p>
 * This reads types and builds no term, so it has no evaluation order; {@link Records} states the order of a {@code new}.
 */
final class Tuples {

    private final JavacFacts facts;
    private final Typing typing;

    Tuples(JavacFacts facts, Typing typing) {
        this.facts = facts;
        this.typing = typing;
    }

    static boolean isTuple(String identity) {
        return identity.equals(JavacNames.TUPLE2) || identity.equals(JavacNames.TUPLE3);
    }

    /** The type as written: a diamond's or a raw tuple's components are Data here, as nothing writes them. */
    PirType.RecordType written(NewClassTree tree) {
        var type = isExplicit(tree) ? facts.type(tree) : facts.types().erasure(facts.type(tree));
        return (PirType.RecordType) facts.pirType(type, tree.getIdentifier());
    }

    private static boolean isExplicit(NewClassTree tree) {
        return tree.getIdentifier() instanceof ParameterizedTypeTree type && !type.getTypeArguments().isEmpty();
    }

    /** The tuple's type, given its lowered arguments. */
    PirType.RecordType type(String name, PirType.RecordType written, NewClassTree tree, List<PirTerm> fields, Frame frame) {
        var arguments = tree.getArguments();
        if (isExplicit(tree)) return written;
        if (!(tree.getIdentifier() instanceof ParameterizedTypeTree)) {
            for (int i = 0; i < fields.size(); i++) requireData(name, arguments.get(i), fields.get(i));
            return written;
        }
        var target = destination(tree, frame);
        var components = new ArrayList<PirType.Field>();
        for (int i = 0; i < written.fields().size(); i++) {
            PirType type;
            if (target != null && target.fields().size() == written.fields().size()) {
                type = target.fields().get(i).type();
                if (type instanceof PirType.DataType) requireData(name, arguments.get(i), fields.get(i));
            } else {
                type = component(name, tree, arguments.get(i), fields.get(i));
            }
            components.add(new PirType.Field(written.fields().get(i).name(), type));
        }
        return new PirType.RecordType(written.name(), components);
    }

    /** A diamond's component from its argument. */
    private PirType component(String name, NewClassTree tree, ExpressionTree argument, PirTerm field) {
        try {
            return typing.of(argument, field);
        } catch (NotPorted e) {
            throw CompilerTypeDiagnostics.diamondTypeArgumentsUnknown(name, facts.location(tree));
        }
    }

    /** {@code JULC0012} unless a component read back as Data already is Data: a record, sum or {@code Optional} is. */
    private void requireData(String name, ExpressionTree argument, PirTerm field) {
        PirType type;
        try {
            type = typing.of(argument, field);
        } catch (NotPorted e) {
            return;
        }
        boolean data = type instanceof PirType.DataType || type instanceof PirType.RecordType
                || type instanceof PirType.SumType || type instanceof PirType.OptionalType
                || type instanceof PirType.NamedTypeRef;
        if (!data) throw CompilerTypeDiagnostics.rawTupleUnsupported(name, facts.location(argument));
    }

    /**
     * The written tuple type the value of {@code tree} goes to, through parentheses and conditional branches: a declared
     * local or field, a {@code return}, an argument of a method of this class. Null for anything else, a {@code var} for one.
     * The reference takes an assignment's target too: assignments lower with loops, which bring that case.
     */
    private PirType.RecordType destination(NewClassTree tree, Frame frame) {
        Tree child = tree;
        Tree parent = facts.parent(tree);
        while (parent instanceof ParenthesizedTree
                || parent instanceof ConditionalExpressionTree conditional && conditional.getCondition() != child) {
            child = parent;
            parent = facts.parent(parent);
        }
        var target = writtenAt(parent, child, frame);
        if (target != null) target = facts.registry().resolveNamed(target);
        return target instanceof PirType.RecordType record ? record : null;
    }

    private PirType writtenAt(Tree parent, Tree value, Frame frame) {
        return switch (parent) {
            case VariableTree variable when variable.getInitializer() == value && !facts.isGenerated(variable.getType()) ->
                    facts.pirType(facts.element(variable).asType(), variable.getType());
            case ReturnTree ignored -> frame.exit().returnType();
            case MethodInvocationTree call when call.getMethodSelect() instanceof IdentifierTree -> parameterType(call, value);
            default -> null;
        };
    }

    private PirType parameterType(MethodInvocationTree call, Tree argument) {
        int index = call.getArguments().indexOf(argument);
        if (index < 0) return null;
        var type = (ExecutableElement) facts.element(call.getMethodSelect());
        return LibraryMethodRegistry.getParamTypes(facts.methodType(type)).get(index);
    }
}
