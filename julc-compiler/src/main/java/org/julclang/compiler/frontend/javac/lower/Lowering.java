package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.compiler.pir.TypeMethodRegistry;

import java.util.List;

/**
 * The javac trees of a program to PIR (ADR-068 D2): the one dispatch over expressions and the construct families around
 * it. A family lowers its construct and comes back here for its parts, so this page is the map of what is covered. Reads
 * javac only through {@link JavacFacts}; assumes validated input (I12), so a tree kind not listed here is
 * {@code JULC0084}, naming the construct, and never falls back (I8).
 */
public final class Lowering {

    private final JavacFacts facts;
    private final Terms terms;
    private final Literals literals;
    private final Names names;
    private final TypeTests typeTests;
    private final Statements statements;
    private final Operators operators;
    private final Calls calls;
    private final Records records;
    private final Typing typing;
    private final CallTargets targets;

    /** A lowering with no stdlib: every API call but the intrinsics is rejected, as in the reference. */
    public Lowering(JavacFacts facts, CompilationContext context) {
        this(facts, context, null);
    }

    /** @param stdlib the lookup of the compilation, or null */
    public Lowering(JavacFacts facts, CompilationContext context, StdlibLookup stdlib) {
        this.facts = facts;
        this.terms = new Terms(facts, context);
        this.typing = new Typing(facts);
        this.literals = new Literals(facts, terms);
        this.names = new Names(facts, terms);
        var typeMethods = TypeMethodRegistry.defaultRegistry();
        this.targets = new CallTargets(facts, typing, typeMethods);
        this.typeTests = new TypeTests(facts, this);
        this.statements = new Statements(facts, terms, typing, this, targets, typeTests);
        this.operators = new Operators(facts, typing, this);
        this.calls = new Calls(facts, terms, typing, targets, this, context, stdlib, typeMethods);
        this.records = new Records(facts, typing, this, names);
    }

    public Terms terms() {
        return terms;
    }

    /** A method: a lambda over its parameters, as the reference's {@code generateMethod} builds it. */
    public PirTerm method(MethodTree tree, Scope scope, boolean guardBoolean) {
        return statements.method(tree, scope, guardBoolean);
    }

    /** A field initializer. */
    public PirTerm initializer(ExpressionTree tree, Scope scope) {
        return expr(tree, Frame.initializer(scope));
    }

    /** An expression, with a Data-encoding rejection that had no position located at it. */
    PirTerm expr(ExpressionTree tree, Frame frame) {
        try {
            return dispatch(tree, frame);
        } catch (CompilerException e) {
            throw located(e, tree);
        }
    }

    /** The one dispatch over expressions. */
    private PirTerm dispatch(ExpressionTree tree, Frame frame) {
        return switch (tree) {
            case LiteralTree t -> literals.literal(t);
            case NewArrayTree t -> literals.byteArray(t);
            case IdentifierTree t -> names.identifier(t, frame);
            case MemberSelectTree t -> terms.at(t, records.member(t, frame));
            case ParenthesizedTree t -> expr(t.getExpression(), frame);
            case BinaryTree t -> terms.at(t, operators.binary(t, frame));
            case UnaryTree t -> terms.at(t, operators.unary(t, frame));
            case ConditionalExpressionTree t -> terms.at(t, operators.conditional(t, frame));
            case MethodInvocationTree t -> terms.at(t, calls.call(t, frame));
            case NewClassTree t -> terms.at(t, records.creation(t, frame));
            case InstanceOfTree t -> terms.at(t, typeTests.instanceOf(t, frame));
            case AssignmentTree t -> throw reassignment(t.getVariable(), t);
            case CompoundAssignmentTree t -> throw reassignment(t.getVariable(), t);
            default -> throw NotPorted.at(facts, tree);
        };
    }

    /** ADR-062 T20: only a loop updates a variable declared before it, and loops are not ported, so none of these lowers. */
    private CompilerException reassignment(ExpressionTree target, ExpressionTree assignment) {
        var info = DiagnosticCodes.REASSIGNMENT_OUTSIDE_LOOP;
        return facts.rejection(info, assignment, target.toString());
    }

    /**
     * {@code JULC0091} raised where a value is Data-encoded has no position: the innermost expression being lowered gives
     * it one, the argument of a call whose type holds the native array, or else the expression itself.
     */
    private CompilerException located(CompilerException e, ExpressionTree tree) {
        if (e.diagnostics().size() != 1) return e;
        CompilerDiagnostic diagnostic = e.diagnostics().getFirst();
        if (diagnostic.line() > 0 || !DiagnosticCodes.NATIVE_ARRAY_NOT_DATA_ENCODABLE.code().equals(diagnostic.code())) return e;
        List<? extends ExpressionTree> arguments = tree instanceof MethodInvocationTree call ? call.getArguments()
                : tree instanceof NewClassTree creation ? creation.getArguments() : List.of();
        for (var argument : arguments) {
            var array = nativeArrayOf(argument);
            if (array != null) return nativeArray(argument, array);
        }
        var own = nativeArrayOf(tree);
        if (own != null) return nativeArray(tree, own);
        var at = facts.location(tree);
        return new CompilerException(diagnostic.message(), new CompilerDiagnostic(diagnostic.level(), diagnostic.message(),
                at.fileName(), at.line(), at.column(), diagnostic.suggestion(), diagnostic.code()));
    }

    private PirType.ArrayType nativeArrayOf(ExpressionTree tree) {
        try {
            return PirType.nativeArrayIn(typing.of(tree));
        } catch (CompilerException e) {
            return null;
        }
    }

    private CompilerException nativeArray(Tree tree, PirType.ArrayType array) {
        var at = facts.location(tree);
        return CompilerTypeDiagnostics.nativeArrayNotDataEncodable("The value of '" + at.fragment() + "'", array, at);
    }
}
