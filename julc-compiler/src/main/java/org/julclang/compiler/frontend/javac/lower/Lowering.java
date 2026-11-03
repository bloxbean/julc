package org.julclang.compiler.frontend.javac.lower;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.NotPorted;
import org.julclang.compiler.frontend.javac.subset.FieldUpdates;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.compiler.pir.TypeMethodRegistry;

import java.util.Collection;
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
    private final Lambdas lambdas;
    private final Calls calls;
    private final Casts casts;
    private final Records records;
    private final Typing typing;
    private final CallTargets targets;
    private final Switches switches;
    private final FieldUpdates fieldUpdates;

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
        this.names = new Names(facts, terms, typing);
        var typeMethods = TypeMethodRegistry.defaultRegistry();
        this.targets = new CallTargets(facts, typing, typeMethods);
        this.typeTests = new TypeTests(facts, terms, typing, this);
        this.fieldUpdates = new FieldUpdates(facts);
        this.statements = new Statements(facts, terms, typing, this, targets, typeTests, context, fieldUpdates);
        this.operators = new Operators(facts, typing, targets, this);
        this.lambdas = new Lambdas(facts, terms, typing, targets, this);
        this.casts = new Casts(facts, typing, targets, this);
        this.calls = new Calls(facts, terms, typing, targets, lambdas, casts, this, context, stdlib, typeMethods);
        this.records = new Records(facts, typing, targets, this, names);
        this.switches = new Switches(facts, terms, typing, this, statements, targets, typeTests, typeMethods);
    }

    public Terms terms() {
        return terms;
    }

    /** A call of a library method's binder: the binder, and the class name the call is written with. */
    public record LibraryCall(String binder, String qualifier) {
    }

    /** The calls of library binders lowered since the last drain, in the order they lowered. */
    public List<LibraryCall> drainLibraryCalls() {
        return calls.drainLibraryCalls();
    }

    /** The warnings lowering found since the last drain, in the order it found them. */
    public List<CompilerDiagnostic> drainWarnings() {
        return calls.drainWarnings();
    }

    /** The lowering of another class begins: the static methods {@link #methodDeclared} names from here on are its own. */
    public void classBegins() {
        statements.classBegins();
    }

    /** A method: a lambda over its parameters, as the reference's {@code generateMethod} builds it. */
    public PirTerm method(MethodTree tree, Scope scope, boolean guardBoolean) {
        var term = statements.method(tree, scope, guardBoolean);
        fieldUpdates.lowered(tree);
        return term;
    }

    /** The methods this compilation runs once per evaluation, which alone may update a field in a loop ({@code JULC0056}). */
    public void entryMethods(Collection<MethodTree> methods) {
        fieldUpdates.entries(methods);
    }

    /** A static method of the class, whose binder the reference holds among its variables. */
    public void methodDeclared(String binder) {
        statements.methodDeclared(binder);
    }

    /** A field initializer. */
    public PirTerm initializer(ExpressionTree tree, Scope scope) {
        return expr(tree, Frame.initializer(scope));
    }

    /** A block, as a lambda's body is one. */
    PirTerm block(BlockTree tree, Frame frame) {
        return statements.block(tree, frame);
    }

    /** An expression, with a Data-encoding rejection that had no position located at it. */
    PirTerm expr(ExpressionTree tree, Frame frame) {
        try {
            var exact = frame.inGuard() ? switches.exact(tree) : null;
            return exact != null ? exact : dispatch(tree, frame);
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
            case LambdaExpressionTree t -> throw lambdas.outsideCall(t);
            case SwitchExpressionTree t -> terms.at(t, switches.expression(t, frame));
            // A guard casts to a primitive type or to Boolean, which is its operand (ADR-067's rules hold the rest).
            case TypeCastTree t when frame.inGuard() -> expr(t.getExpression(), frame);
            case TypeCastTree t -> casts.cast(t, frame);
            case AssignmentTree t -> throw reassignment(t.getVariable(), t, frame);
            case CompoundAssignmentTree t -> throw reassignment(t.getVariable(), t, frame);
            default -> throw NotPorted.at(facts, tree);
        };
    }

    /** What a loop stores for {@code assignment}, an assignment or a compound one, to {@code target}. */
    PirTerm assigned(ExpressionTree assignment, Scope.Binder target, Frame frame) {
        return operators.assigned(assignment, target, frame);
    }

    /**
     * An assignment no loop body bound as a statement. In a body with accumulators it would drop the update, so it is rejected
     * rather than lowered to its right-hand side (ADR-062 T20): only a loop updates a variable declared before it.
     */
    private CompilerException reassignment(ExpressionTree target, ExpressionTree assignment, Frame frame) {
        if (frame.bindsUpdates()) {
            var name = target instanceof IdentifierTree identifier ? "'" + identifier.getName() + "'" : "this target";
            return facts.locatedRejection("Assignment to " + name + " is not supported at this position",
                    "Inside a loop, assign an accumulator or a loop-body local as a statement directly in the loop body. "
                            + "In an if/else branch, assign an accumulator or a local declared within that branch; "
                            + "an assignment inside another expression or statement is not supported.", assignment);
        }
        if (facts.enclosing(assignment, Tree.Kind.ENHANCED_FOR_LOOP, Tree.Kind.WHILE_LOOP) == null) {
            return facts.rejection(DiagnosticCodes.REASSIGNMENT_OUTSIDE_LOOP, assignment, target.toString());
        }
        return facts.rejection("Unsupported expression: AssignExpr",
                "Variables are immutable on-chain. Use the accumulator pattern in for-each/while loops for mutable state.", assignment);
    }

    /**
     * {@code JULC0091} and {@code JULC0012} raised where a value is Data-encoded have no position: the innermost expression
     * being lowered gives it one, the argument of a call whose type holds the native array, or else the expression itself.
     */
    CompilerException located(CompilerException e, ExpressionTree tree) {
        if (e.diagnostics().size() != 1) return e;
        CompilerDiagnostic diagnostic = e.diagnostics().getFirst();
        if (diagnostic.line() > 0) return e;
        if (diagnostic.message() != null && diagnostic.message().contains(CompilerTypeDiagnostics.UNKNOWN_TYPE_ENCODED)) {
            return CompilerTypeDiagnostics.unknownTypeEncoded(facts.location(tree));
        }
        if (!DiagnosticCodes.NATIVE_ARRAY_NOT_DATA_ENCODABLE.code().equals(diagnostic.code())) return e;
        List<? extends ExpressionTree> arguments = tree instanceof MethodInvocationTree call ? call.getArguments()
                : tree instanceof NewClassTree creation ? creation.getArguments() : List.of();
        for (var argument : arguments) {
            var array = nativeArrayOf(argument);
            if (array != null) return nativeArray(argument, array);
        }
        // The reference does not know a stdlib call's type, so it names no value.
        var own = targets.isStdlibCall(tree) ? null : nativeArrayOf(tree);
        if (own != null) return nativeArray(tree, own);
        return facts.located(e, tree);
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
