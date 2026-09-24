package org.julclang.compiler.pir;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.resolve.SymbolTable;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Compiles loop body statements to PIR terms for single-accumulator,
 * multi-accumulator, and break-aware loop patterns.
 * <p>
 * Delegates expression/statement compilation back to PirGenerator (same package).
 * Keeps entry points ({@code generateForEachStmt}, {@code generateWhileStmt})
 * in PirGenerator since they manage transient state.
 */
final class LoopBodyGenerator {

    private final PirGenerator gen;
    private final SymbolTable symbolTable;
    /** Numbers the fresh names of block locals (deterministic per compilation; names never reach the bytes). */
    private int blockLocalCounter;

    LoopBodyGenerator(PirGenerator gen, SymbolTable symbolTable) {
        this.gen = gen;
        this.symbolTable = symbolTable;
    }

    // ===== AST inspection =====

    /**
     * #155: an if joins only the loop's accumulators, not its body-local bindings.
     * Reject updates that would cross such a join, before any PIR is emitted. This is
     * deliberately conservative: even a dead update or an always-taken branch is rejected.
     * Bare blocks do not introduce a join. A nested loop owns its own locals; enclosing
     * locals are its accumulators, but still cannot escape an enclosing if's join.
     */
    void validateConditionalLocalUpdates(Statement body, Set<String> locals) {
        validateConditionalLocalUpdates(body, new HashSet<>(locals), new HashSet<>());
    }

    private void validateConditionalLocalUpdates(Node stmt, Set<String> locals,
                                                   Set<String> conditionalLocals) {
        if (stmt instanceof BlockStmt block) {
            var blockLocals = new HashSet<>(locals);
            var blockConditionalLocals = new HashSet<>(conditionalLocals);
            for (var child : block.getStatements()) {
                validateConditionalLocalUpdates(child, blockLocals, blockConditionalLocals);
            }
        } else if (stmt instanceof ExpressionStmt expression) {
            validateConditionalLocalUpdates(expression.getExpression(), locals, conditionalLocals);
        } else if (stmt instanceof VariableDeclarationExpr declaration) {
            for (var variable : declaration.getVariables()) {
                variable.getInitializer().ifPresent(initializer ->
                        validateConditionalLocalUpdates(initializer, locals, conditionalLocals));
                locals.add(variable.getNameAsString());
                conditionalLocals.remove(variable.getNameAsString());
            }
        } else if (stmt instanceof AssignExpr assignment
                && assignment.getTarget() instanceof NameExpr name
                && conditionalLocals.contains(name.getNameAsString())) {
            throw gen.enrichedError("Conditional update to loop-body local '"
                            + sourceName(name.getNameAsString()) + "' is not supported",
                    "An if/else branch carries only loop accumulators out of the branch. "
                            + "Use a conditional initializer (condition ? value : otherValue), "
                            + "or declare the variable before the loop so it is an accumulator "
                            + "(reset it each iteration if needed), or declare it inside the branch "
                            + "if its value is only needed there.", assignment);
        } else if (stmt instanceof IfStmt branch) {
            validateConditionalLocalUpdates(branch.getCondition(), locals, conditionalLocals);
            var crossingJoin = new HashSet<>(conditionalLocals);
            crossingJoin.addAll(locals);
            validateConditionalLocalUpdates(branch.getThenStmt(), new HashSet<>(locals), new HashSet<>(crossingJoin));
            branch.getElseStmt().ifPresent(other ->
                    validateConditionalLocalUpdates(other, new HashSet<>(locals), new HashSet<>(crossingJoin)));
        } else if (stmt instanceof ForEachStmt loop) {
            validateConditionalLocalUpdates(loop.getIterable(), locals, conditionalLocals);
            var innerLocals = new HashSet<String>();
            loop.getVariable().getVariables().forEach(v -> innerLocals.add(v.getNameAsString()));
            validateConditionalLocalUpdates(loop.getBody(), innerLocals, new HashSet<>(conditionalLocals));
        } else if (stmt instanceof WhileStmt loop) {
            validateConditionalLocalUpdates(loop.getCondition(), locals, conditionalLocals);
            validateConditionalLocalUpdates(loop.getBody(), new HashSet<>(), new HashSet<>(conditionalLocals));
        } else if (stmt instanceof SwitchExpr expression) {
            // Arms export only their yielded value, not enclosing bindings. Their ownership
            // guard runs in generateSwitchExpr; each nested loop validates its own body.
            // Arm-local ifs use normal continuation lowering, not this loop's joins.
            validateConditionalLocalUpdates(expression.getSelector(), locals, conditionalLocals);
        } else if (!(stmt instanceof LambdaExpr)) {
            // Preserve enclosing restrictions through expressions; siblings do not share locals.
            for (var child : stmt.getChildNodes()) {
                validateConditionalLocalUpdates(child, new HashSet<>(locals), new HashSet<>(conditionalLocals));
            }
        }
        // Lambdas have independent scope; their own loops are checked at their lowering entry.
    }

    /** A switch arm exports only its yielded value, never rebindings of enclosing variables. */
    void validateSwitchExpressionUpdates(SwitchExpr expression) {
        for (var entry : expression.getEntries()) {
            var declaredInArm = new HashSet<String>();
            for (var label : entry.getLabels()) {
                label.findAll(TypePatternExpr.class).forEach(pattern -> declaredInArm.add(pattern.getNameAsString()));
            }
            var caseBindings = Set.copyOf(declaredInArm);
            for (var statement : entry.getStatements()) {
                validateSwitchArmUpdates(statement, declaredInArm, caseBindings);
            }
        }
    }

    private void validateSwitchArmUpdates(Node node, Set<String> declaredInArm, Set<String> caseBindings) {
        if (node instanceof LambdaExpr) return; // Independent scope; captures are effectively final in Java.
        if (node instanceof BlockStmt block) {
            var blockLocals = new HashSet<>(declaredInArm);
            for (var statement : block.getStatements()) validateSwitchArmUpdates(statement, blockLocals, caseBindings);
        } else if (node instanceof ExpressionStmt expression) {
            validateSwitchArmUpdates(expression.getExpression(), declaredInArm, caseBindings);
        } else if (node instanceof VariableDeclarationExpr declaration) {
            for (var variable : declaration.getVariables()) {
                variable.getInitializer().ifPresent(initializer -> validateSwitchArmUpdates(initializer, declaredInArm, caseBindings));
                declaredInArm.add(variable.getNameAsString());
            }
        } else if (node instanceof AssignExpr assignment && assignment.getTarget() instanceof NameExpr name
                && caseBindings.contains(name.getNameAsString())) {
            // #162: cached case-field projections still refer to the original record.
            // Reject rebinding even if this particular use only yields the record itself.
            throw gen.enrichedError("Reassignment of switch case-pattern variable '"
                            + sourceName(name.getNameAsString()) + "' is not supported",
                    "Copy the pattern variable to a fresh local accumulator inside the arm, "
                            + "update that local, and yield its result instead.", assignment);
        } else if (node instanceof AssignExpr assignment && assignment.getTarget() instanceof NameExpr name
                && !declaredInArm.contains(name.getNameAsString())) {
            throw gen.enrichedError("Switch-expression arm cannot update enclosing variable '"
                            + sourceName(name.getNameAsString()) + "'",
                    "A switch expression exports only its yielded value. Declare an accumulator inside the arm, "
                            + "yield its result, and use the switch value outside the arm instead of mutating an enclosing variable.",
                    assignment);
        } else if (node instanceof IfStmt branch) {
            validateSwitchArmUpdates(branch.getCondition(), declaredInArm, caseBindings);
            var thenLocals = new HashSet<>(declaredInArm);
            if (branch.getCondition() instanceof InstanceOfExpr condition
                    && condition.getPattern().orElse(null) instanceof TypePatternExpr pattern) {
                thenLocals.add(pattern.getNameAsString());
            }
            validateSwitchArmUpdates(branch.getThenStmt(), thenLocals, caseBindings);
            branch.getElseStmt().ifPresent(other -> validateSwitchArmUpdates(other, new HashSet<>(declaredInArm), caseBindings));
        } else if (node instanceof ForEachStmt loop) {
            validateSwitchArmUpdates(loop.getIterable(), declaredInArm, caseBindings);
            var loopLocals = new HashSet<>(declaredInArm);
            loop.getVariable().getVariables().forEach(variable -> loopLocals.add(variable.getNameAsString()));
            validateSwitchArmUpdates(loop.getBody(), loopLocals, caseBindings);
        } else if (node instanceof SwitchExpr nested) {
            validateSwitchArmUpdates(nested.getSelector(), declaredInArm, caseBindings);
            validateSwitchExpressionUpdates(nested); // A second value boundary, including for outer-arm locals.
        } else {
            for (var child : node.getChildNodes()) {
                validateSwitchArmUpdates(child, new HashSet<>(declaredInArm), caseBindings);
            }
        }
    }

    boolean containsBreak(Statement stmt) {
        if (stmt instanceof BreakStmt) return true;
        if (stmt instanceof WhileStmt || stmt instanceof ForEachStmt) return false;
        if (stmt instanceof BlockStmt bs) {
            return bs.getStatements().stream().anyMatch(this::containsBreak);
        }
        if (stmt instanceof IfStmt is) {
            if (containsBreak(is.getThenStmt())) return true;
            return is.getElseStmt().map(this::containsBreak).orElse(false);
        }
        return false;
    }

    boolean containsReturn(Statement stmt) {
        return containsExit(stmt, ReturnStmt.class);
    }

    boolean containsYield(Statement stmt) {
        return containsExit(stmt, YieldStmt.class);
    }

    /**
     * True when the loop body directly contains an exit statement of the given kind.
     * Nested loops are not entered (their own lowering diagnoses their exits), and only
     * statement structure is inspected, so a {@code yield} inside a nested switch
     * expression is never visited.
     */
    private boolean containsExit(Statement stmt, Class<? extends Statement> exit) {
        if (exit.isInstance(stmt)) return true;
        if (stmt instanceof WhileStmt || stmt instanceof ForEachStmt) return false;
        if (stmt instanceof BlockStmt bs) {
            return bs.getStatements().stream().anyMatch(s -> containsExit(s, exit));
        }
        if (stmt instanceof IfStmt is) {
            if (containsExit(is.getThenStmt(), exit)) return true;
            return is.getElseStmt().map(e -> containsExit(e, exit)).orElse(false);
        }
        return false;
    }

    boolean needsForEachUnwrapTracking(PirType elemType) {
        return elemType instanceof PirType.ByteStringType
                || elemType instanceof PirType.IntegerType
                || elemType instanceof PirType.BoolType
                || elemType instanceof PirType.StringType;
    }

    // ===== Multi-accumulator pack/unpack =====

    PirTerm packAccumulators(List<String> names, List<PirType> types) {
        PirTerm result = new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        for (int i = names.size() - 1; i >= 0; i--) {
            var value = new PirTerm.Var(names.get(i), types.get(i));
            var encoded = PirHelpers.wrapEncode(value, types.get(i));
            result = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons), encoded),
                    result);
        }
        return result;
    }

    PirTerm unpackAccumulators(PirTerm tuple, List<String> names, List<PirType> types, PirTerm body) {
        // The body and every later accessor are in the tuple binder's scope, and an
        // accumulator of the same name would shadow the tuple for later accessors.
        var avoid = PirHelpers.freeVariables(body);
        avoid.addAll(names);
        var tupleVar = new PirTerm.Var(PirHelpers.hygienicName("__t", avoid),
                new PirType.ListType(new PirType.DataType()));
        PirTerm result = body;
        for (int i = names.size() - 1; i >= 0; i--) {
            PirTerm accessor = tupleVar;
            for (int j = 0; j < i; j++) {
                accessor = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), accessor);
            }
            accessor = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), accessor);
            var decoded = PirHelpers.wrapDecode(accessor, types.get(i));
            result = new PirTerm.Let(names.get(i), decoded, result);
        }
        return new PirTerm.Let(tupleVar.name(), tuple, result);
    }

    // ===== Single-accumulator body =====

    PirTerm generateSingleAccBody(Statement bodyStmt, String accName, PirType accType) {
        var stmts = PirHelpers.blockStmts(bodyStmt);
        return generateSingleAccStatements(stmts, 0, accName, accType);
    }

    PirTerm generateSingleAccStatements(List<Statement> stmts, int index,
                                         String accName, PirType accType) {
        if (index >= stmts.size()) {
            return new PirTerm.Var(accName, accType);
        }
        var stmt = stmts.get(index);
        if (stmt instanceof BlockStmt bs) {
            return withBlockSpliced(stmts, index, bs, body -> generateSingleAccStatements(body, 0, accName, accType));
        }

        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && ne.getNameAsString().equals(accName)) {
                var value = assigned(ae, accType);
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let(accName, value, rest);
            }
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var value = assigned(ae, symbolTable.lookup(ne.getNameAsString()).orElse(null));
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                return compileVarDeclThenContinue(vde, stmts, index,
                        (s, i) -> generateSingleAccStatements(s, i, accName, accType));
            }
            var expr = gen.generateExpression(es.getExpression());
            var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
            return new PirTerm.Let("_", expr, rest);
        }
        if (stmt instanceof IfStmt is) {
            var cond = gen.generateExpression(is.getCondition());
            var thenTerm = generateSingleAccBody(is.getThenStmt(), accName, accType);
            var elseTerm = is.getElseStmt()
                    .map(e -> generateSingleAccBody(e, accName, accType))
                    .orElse(new PirTerm.Var(accName, accType));
            var ifExpr = new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
            if (index + 1 < stmts.size()) {
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let(accName, ifExpr, rest);
            }
            return ifExpr;
        }
        if (stmt instanceof WhileStmt ws) {
            return handleNestedLoop(ws, stmts, index,
                    (s, i) -> generateSingleAccStatements(s, i, accName, accType));
        }
        if (stmt instanceof ForEachStmt fes) {
            return handleNestedForEach(fes, stmts, index,
                    (s, i) -> generateSingleAccStatements(s, i, accName, accType));
        }
        var term = gen.generateStatement(stmt);
        if (index + 1 < stmts.size()) {
            var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
            return new PirTerm.Let("_", term, rest);
        }
        return new PirTerm.Var(accName, accType);
    }

    // ===== Break-aware single-acc body =====

    PirTerm generateBreakAwareBody(Statement bodyStmt, String accName,
                                    PirType accType, Function<PirTerm, PirTerm> continueFn) {
        List<Statement> stmts;
        if (bodyStmt instanceof BlockStmt bs) stmts = bs.getStatements();
        else stmts = List.of(bodyStmt);
        return generateBreakAwareStatements(stmts, 0, accName, accType, continueFn);
    }

    private PirTerm generateBreakAwareStatements(List<Statement> stmts, int index,
                                                  String accName, PirType accType,
                                                  Function<PirTerm, PirTerm> continueFn) {
        if (index >= stmts.size()) {
            return continueFn.apply(new PirTerm.Var(accName, accType));
        }
        var stmt = stmts.get(index);
        if (stmt instanceof BlockStmt bs) {
            return withBlockSpliced(stmts, index, bs, body -> generateBreakAwareStatements(body, 0, accName, accType, continueFn));
        }

        if (stmt instanceof BreakStmt) {
            return new PirTerm.Var(accName, accType);
        }
        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && ne.getNameAsString().equals(accName)) {
                var value = assigned(ae, accType);
                if (index + 1 < stmts.size() && stmts.get(index + 1) instanceof BreakStmt) {
                    return value;
                }
                var rest = generateBreakAwareStatements(stmts, index + 1, accName, accType, continueFn);
                return new PirTerm.Let(accName, value, rest);
            }
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                return compileVarDeclThenContinue(vde, stmts, index,
                        (s, i) -> generateBreakAwareStatements(s, i, accName, accType, continueFn));
            }
            var expr = gen.generateExpression(es.getExpression());
            var rest = generateBreakAwareStatements(stmts, index + 1, accName, accType, continueFn);
            return new PirTerm.Let("_", expr, rest);
        }
        if (stmt instanceof IfStmt is) {
            return generateBreakAwareIf(is, stmts, index, accName, accType, continueFn);
        }
        throw gen.enrichedError("Unsupported statement in break-aware loop body: " + stmt.getClass().getSimpleName(),
                "Inside loops with break, only variable declarations, assignments, if/else, and break are supported.",
                stmt);
    }

    private PirTerm generateBreakAwareIf(IfStmt is, List<Statement> followingStmts, int followingIndex,
                                          String accName, PirType accType,
                                          Function<PirTerm, PirTerm> continueFn) {
        var cond = gen.generateExpression(is.getCondition());
        boolean thenBreaks = containsBreak(is.getThenStmt());
        boolean elseBreaks = is.getElseStmt().map(this::containsBreak).orElse(false);

        PirTerm thenTerm;
        PirTerm elseTerm;

        if (thenBreaks && elseBreaks) {
            thenTerm = generateBreakAwareBody(is.getThenStmt(), accName, accType, _ -> new PirTerm.Var(accName, accType));
            elseTerm = generateBreakAwareBody(is.getElseStmt().get(), accName, accType, _ -> new PirTerm.Var(accName, accType));
        } else if (thenBreaks) {
            thenTerm = generateBreakAwareBody(is.getThenStmt(), accName, accType, _ -> new PirTerm.Var(accName, accType));
            if (is.getElseStmt().isPresent()) {
                elseTerm = generateBreakAwareBody(is.getElseStmt().get(), accName, accType, continueFn);
            } else {
                elseTerm = generateBreakAwareStatements(followingStmts, followingIndex + 1, accName, accType, continueFn);
                return new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
            }
        } else if (elseBreaks) {
            thenTerm = generateBreakAwareBody(is.getThenStmt(), accName, accType, continueFn);
            elseTerm = generateBreakAwareBody(is.getElseStmt().get(), accName, accType, _ -> new PirTerm.Var(accName, accType));
        } else {
            thenTerm = generateSingleAccBody(is.getThenStmt(), accName, accType);
            elseTerm = is.getElseStmt()
                    .map(e -> generateSingleAccBody(e, accName, accType))
                    .orElse(new PirTerm.Var(accName, accType));
        }

        var ifExpr = new PirTerm.IfThenElse(cond, thenTerm, elseTerm);

        if (followingIndex + 1 < followingStmts.size()) {
            if (thenBreaks && !elseBreaks && !is.getElseStmt().isPresent()) {
                return ifExpr;
            }
            var rest = generateBreakAwareStatements(followingStmts, followingIndex + 1, accName, accType, continueFn);
            if (!thenBreaks && !elseBreaks) {
                return new PirTerm.Let(accName, ifExpr, rest);
            }
            return new PirTerm.Let(PirHelpers.hygienicName("_if", PirHelpers.freeVariables(rest)), ifExpr, rest);
        }

        if (!thenBreaks && !elseBreaks) {
            return continueFn.apply(ifExpr);
        }
        return ifExpr;
    }

    // ===== Multi-accumulator body =====

    PirTerm generateMultiAccBody(Statement bodyStmt, List<String> accNames, List<PirType> accTypes) {
        var stmts = PirHelpers.blockStmts(bodyStmt);
        return generateMultiAccStatements(stmts, 0, accNames, accTypes);
    }

    private PirTerm generateMultiAccStatements(List<Statement> stmts, int index,
                                                List<String> accNames, List<PirType> accTypes) {
        if (index >= stmts.size()) {
            return packAccumulators(accNames, accTypes);
        }
        var stmt = stmts.get(index);
        if (stmt instanceof BlockStmt bs) {
            return withBlockSpliced(stmts, index, bs, body -> generateMultiAccStatements(body, 0, accNames, accTypes));
        }

        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && accNames.contains(ne.getNameAsString())) {
                var value = assigned(ae, accTypes.get(accNames.indexOf(ne.getNameAsString())));
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var value = assigned(ae, symbolTable.lookup(ne.getNameAsString()).orElse(null));
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                return compileVarDeclThenContinue(vde, stmts, index,
                        (s, i) -> generateMultiAccStatements(s, i, accNames, accTypes));
            }
            var expr = gen.generateExpression(es.getExpression());
            var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
            return new PirTerm.Let("_", expr, rest);
        }
        if (stmt instanceof IfStmt is) {
            var cond = gen.generateExpression(is.getCondition());
            var thenTerm = generateMultiAccBody(is.getThenStmt(), accNames, accTypes);
            var elseTerm = is.getElseStmt()
                    .map(e -> generateMultiAccBody(e, accNames, accTypes))
                    .orElse(packAccumulators(accNames, accTypes));
            var ifExpr = new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
            if (index + 1 < stmts.size()) {
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return unpackAccumulators(ifExpr, accNames, accTypes.stream().toList(), rest);
            }
            return ifExpr;
        }
        if (stmt instanceof WhileStmt ws) {
            return handleNestedLoop(ws, stmts, index,
                    (s, i) -> generateMultiAccStatements(s, i, accNames, accTypes));
        }
        if (stmt instanceof ForEachStmt fes) {
            return handleNestedForEach(fes, stmts, index,
                    (s, i) -> generateMultiAccStatements(s, i, accNames, accTypes));
        }
        throw gen.enrichedError("Unsupported in multi-acc loop body: " + stmt.getClass().getSimpleName(),
                "Inside multi-accumulator loops, only variable declarations, assignments, and if/else are supported.",
                stmt);
    }

    // ===== Multi-accumulator break-aware body =====

    PirTerm generateMultiAccBreakAwareBody(Statement bodyStmt, List<String> accNames,
                                            List<PirType> accTypes,
                                            Function<PirTerm, PirTerm> continueFn) {
        var stmts = PirHelpers.blockStmts(bodyStmt);
        return generateMultiAccBreakAwareStmts(stmts, 0, accNames, accTypes, continueFn);
    }

    private PirTerm generateMultiAccBreakAwareStmts(List<Statement> stmts, int index,
                                                     List<String> accNames, List<PirType> accTypes,
                                                     Function<PirTerm, PirTerm> continueFn) {
        if (index >= stmts.size()) {
            return continueFn.apply(packAccumulators(accNames, accTypes));
        }
        var stmt = stmts.get(index);
        if (stmt instanceof BlockStmt bs) {
            return withBlockSpliced(stmts, index, bs, body -> generateMultiAccBreakAwareStmts(body, 0, accNames, accTypes, continueFn));
        }

        if (stmt instanceof BreakStmt) {
            return packAccumulators(accNames, accTypes);
        }
        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && accNames.contains(ne.getNameAsString())) {
                var value = assigned(ae, accTypes.get(accNames.indexOf(ne.getNameAsString())));
                if (index + 1 < stmts.size() && stmts.get(index + 1) instanceof BreakStmt) {
                    var rest = packAccumulators(accNames, accTypes);
                    return new PirTerm.Let(ne.getNameAsString(), value, rest);
                }
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var value = assigned(ae, symbolTable.lookup(ne.getNameAsString()).orElse(null));
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                return compileVarDeclThenContinue(vde, stmts, index,
                        (s, i) -> generateMultiAccBreakAwareStmts(s, i, accNames, accTypes, continueFn));
            }
            var expr = gen.generateExpression(es.getExpression());
            var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
            return new PirTerm.Let("_", expr, rest);
        }
        if (stmt instanceof IfStmt is) {
            var cond = gen.generateExpression(is.getCondition());
            boolean thenBreaks = containsBreak(is.getThenStmt());
            boolean elseBreaks = is.getElseStmt().map(this::containsBreak).orElse(false);

            PirTerm thenTerm;
            PirTerm elseTerm;

            if (thenBreaks) {
                thenTerm = generateMultiAccBreakAwareBody(is.getThenStmt(), accNames, accTypes, _ -> packAccumulators(accNames, accTypes));
            } else {
                thenTerm = generateMultiAccBreakAwareBody(is.getThenStmt(), accNames, accTypes, continueFn);
            }
            if (is.getElseStmt().isPresent()) {
                if (elseBreaks) {
                    elseTerm = generateMultiAccBreakAwareBody(is.getElseStmt().get(), accNames, accTypes, _ -> packAccumulators(accNames, accTypes));
                } else {
                    elseTerm = generateMultiAccBreakAwareBody(is.getElseStmt().get(), accNames, accTypes, continueFn);
                }
            } else {
                if (thenBreaks) {
                    elseTerm = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                    return new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
                }
                elseTerm = packAccumulators(accNames, accTypes);
            }

            var ifExpr = new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
            if (index + 1 < stmts.size() && !(thenBreaks && !is.getElseStmt().isPresent())) {
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return unpackAccumulators(ifExpr, accNames, accTypes, rest);
            }
            return ifExpr;
        }
        if (stmt instanceof WhileStmt ws) {
            return handleNestedLoop(ws, stmts, index,
                    (s, i) -> generateMultiAccBreakAwareStmts(s, i, accNames, accTypes, continueFn));
        }
        if (stmt instanceof ForEachStmt fes) {
            return handleNestedForEach(fes, stmts, index,
                    (s, i) -> generateMultiAccBreakAwareStmts(s, i, accNames, accTypes, continueFn));
        }
        throw gen.enrichedError("Unsupported in multi-acc break-aware body: " + stmt.getClass().getSimpleName(),
                "Inside multi-accumulator loops with break, only variable declarations, assignments, if/else, and break are supported.",
                stmt);
    }

    // ===== Static PIR utilities =====

    static Set<String> findReferencedVars(PirTerm term, Map<String, PirType> candidates) {
        var result = new LinkedHashSet<String>();
        collectReferencedVars(term, candidates, result);
        return result;
    }

    static void collectReferencedVars(PirTerm term, Map<String, PirType> candidates, Set<String> result) {
        switch (term) {
            case PirTerm.Var v -> { if (candidates.containsKey(v.name())) result.add(v.name()); }
            case PirTerm.Let l -> {
                collectReferencedVars(l.value(), candidates, result);
                collectReferencedVars(l.body(), candidates, result);
            }
            case PirTerm.LetRec lr -> {
                for (var b : lr.bindings()) collectReferencedVars(b.value(), candidates, result);
                collectReferencedVars(lr.body(), candidates, result);
            }
            case PirTerm.Lam lam -> collectReferencedVars(lam.body(), candidates, result);
            case PirTerm.App app -> {
                collectReferencedVars(app.function(), candidates, result);
                collectReferencedVars(app.argument(), candidates, result);
            }
            case PirTerm.IfThenElse ite -> {
                collectReferencedVars(ite.cond(), candidates, result);
                collectReferencedVars(ite.thenBranch(), candidates, result);
                collectReferencedVars(ite.elseBranch(), candidates, result);
            }
            case PirTerm.DataConstr dc -> { for (var f : dc.fields()) collectReferencedVars(f, candidates, result); }
            case PirTerm.IntegerCase c -> {
                collectReferencedVars(c.scrutinee(), candidates, result);
                for (var branch : c.branches()) collectReferencedVars(branch, candidates, result);
            }
            case PirTerm.PairMatch m -> {
                collectReferencedVars(m.scrutinee(), candidates, result);
                var innerCandidates = new LinkedHashMap<>(candidates);
                innerCandidates.remove(m.firstName());
                innerCandidates.remove(m.secondName());
                collectReferencedVars(m.body(), innerCandidates, result);
            }
            case PirTerm.ListMatch m -> {
                collectReferencedVars(m.scrutinee(), candidates, result);
                collectReferencedVars(m.nilBranch(), candidates, result);
                collectReferencedVars(m.consBranch(), candidates, result);
            }
            case PirTerm.DataMatch dm -> {
                collectReferencedVars(dm.scrutinee(), candidates, result);
                for (var b : dm.branches()) collectReferencedVars(b.body(), candidates, result);
            }
            case PirTerm.Trace t -> {
                collectReferencedVars(t.message(), candidates, result);
                collectReferencedVars(t.body(), candidates, result);
            }
            case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> {}
        }
    }

    static PirTerm rebindPreLoopVars(PirTerm rest, Map<String, PirType> preLoopVars, Set<String> accumulatorNames) {
        var candidates = new LinkedHashMap<>(preLoopVars);
        for (var accName : accumulatorNames) {
            candidates.remove(accName);
        }
        if (candidates.isEmpty()) return rest;

        var referenced = findReferencedVars(rest, candidates);
        if (referenced.isEmpty()) return rest;

        PirTerm result = rest;
        for (var name : referenced) {
            var type = candidates.get(name);
            result = new PirTerm.Let(name, new PirTerm.Var(name, type), result);
        }
        return result;
    }

    // ===== Shared helpers =====

    // ===== Bare nested blocks =====
    //
    // The only thing a bare block does in Java is end the scope of its own declarations; its
    // statements otherwise run in sequence with the statements after it, and every update it
    // makes to an enclosing variable (an accumulator, a loop-body local) and a break inside it
    // behave as if the braces were absent. It is therefore lowered as its statements spliced
    // into the enclosing list, with its own declarations renamed apart first: a fresh name can
    // neither capture a later reference to a name the block shadowed (a class constant
    // redeclared as a block local) nor be referenced after the block. The lowering is that of
    // the braceless body, byte for byte (PR #150 review: delegating the block to the generic
    // statement generator dropped the updates it contained; splicing without renaming leaked
    // its locals; lowering it as a value carried only the accumulator out).

    /** Generate the enclosing list with the block's statements in the block's place. */
    private PirTerm withBlockSpliced(List<Statement> stmts, int index, BlockStmt block,
                                     Function<List<Statement>, PirTerm> generate) {
        return withLocalsRenamed(block, renamed -> generate.apply(spliced(stmts, index, renamed)));
    }

    /** Keep block locals from capturing a continuation embedded after the block's work. */
    PirTerm withLocalsRenamed(BlockStmt block, Function<BlockStmt, PirTerm> generate) {
        var renamed = renamedApart(block);
        if (renamed == block) {
            return generate.apply(block);
        }
        // The copy stands where the block stands while it is generated, so that lookups of an
        // enclosing node (the method of a nested while loop) still succeed, and leaves after.
        block.getParentNode().ifPresent(renamed::setParentNode);
        try {
            return generate.apply(renamed);
        } finally {
            renamed.setParentNode(null);
        }
    }

    /** Rename a pattern binding's references, but not method names or the continuation. */
    PirTerm withBindingRenamed(Statement branch, String name,
                              BiFunction<String, Statement, PirTerm> generate) {
        String fresh = name + BLOCK_LOCAL_MARK + (++blockLocalCounter);
        var renamed = branch.clone();
        rename(renamed, Map.of(name, fresh));
        branch.getParentNode().ifPresent(renamed::setParentNode);
        try {
            return generate.apply(fresh, renamed);
        } finally {
            renamed.setParentNode(null);
        }
    }

    /** The statements of a bare nested block followed by the statements after it. */
    private static List<Statement> spliced(List<Statement> stmts, int index, BlockStmt block) {
        var out = new ArrayList<Statement>(block.getStatements());
        out.addAll(stmts.subList(index + 1, stmts.size()));
        return out;
    }

    /**
     * A copy of the block in which each variable the block itself declares, and every
     * reference to it after its declaration, carries a fresh name; the block itself when it
     * declares nothing. Declarations deeper inside (an if branch, a nested block or loop) are
     * left alone: those are lowered as terms of their own, or renamed when their block is
     * spliced in turn. An initializer is renamed before its own variable is added, and a
     * lambda whose parameter has the name is skipped, as Java scoping has it.
     */
    private BlockStmt renamedApart(BlockStmt block) {
        boolean declares = block.getStatements().stream().anyMatch(
                s -> s instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr);
        if (!declares) return block;
        var copy = block.clone();
        var renames = new HashMap<String, String>();
        for (var stmt : copy.getStatements()) {
            if (stmt instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr vde) {
                for (var declarator : vde.getVariables()) {
                    declarator.getInitializer().ifPresent(init -> rename(init, renames));
                    String fresh = declarator.getNameAsString() + BLOCK_LOCAL_MARK + (++blockLocalCounter);
                    renames.put(declarator.getNameAsString(), fresh);
                    declarator.setName(fresh);
                }
            } else {
                rename(stmt, renames);
            }
        }
        return copy;
    }

    private static void rename(Node node, Map<String, String> renames) {
        if (renames.isEmpty()) return;
        if (node instanceof NameExpr ne) {
            var fresh = renames.get(ne.getNameAsString());
            if (fresh != null) ne.setName(fresh);
            return;
        }
        var visible = renames;
        if (node instanceof LambdaExpr le
                && le.getParameters().stream().anyMatch(p -> renames.containsKey(p.getNameAsString()))) {
            visible = new HashMap<>(renames);
            for (var parameter : le.getParameters()) visible.remove(parameter.getNameAsString());
        }
        for (var child : List.copyOf(node.getChildNodes())) rename(child, visible);
    }

    /**
     * Marks the fresh name of a block local: legal in a UPLC name, impossible in a Java
     * identifier, so no source name can collide with one.
     */
    private static final String BLOCK_LOCAL_MARK = "'";

    /** The name as the source wrote it, for diagnostics. */
    static String sourceName(String name) {
        int mark = name.indexOf(BLOCK_LOCAL_MARK);
        return mark < 0 ? name : name.substring(0, mark);
    }

    /**
     * Lower an assignment's value and check it against the target's type: an accumulator or a
     * loop-body local keeps the type it was declared with (ADR-047 isolation, PR #150 review).
     * A target with no declaration in scope is an error, not a fresh binding.
     */
    private PirTerm assigned(AssignExpr ae, PirType target) {
        var name = ((NameExpr) ae.getTarget()).getNameAsString();
        if (target == null) {
            throw gen.enrichedError("Assignment to undeclared variable '" + sourceName(name) + "'",
                    "Declare the variable before assigning it: before the loop for an accumulator, in the loop body for a local.", ae);
        }
        var value = gen.generateExpression(ae.getValue());
        gen.checkNativeAssignment(name, ae.getValue(), value, target);
        return value;
    }

    /** Compile a variable declaration and continue with the provided continuation. */
    private PirTerm compileVarDeclThenContinue(VariableDeclarationExpr vde,
                                                List<Statement> stmts, int index,
                                                StmtContinuation continuation) {
        var decl = vde.getVariable(0);
        var name = decl.getNameAsString();
        var initExpr = decl.getInitializer().orElseThrow(
                () -> new CompilerException("Variable must be initialized: " + sourceName(name)
                        + ". Hint: On-chain variables need initial values, e.g. var " + sourceName(name) + " = BigInteger.ZERO;"));
        var value = gen.generateExpression(initExpr);
        var pirType = gen.inferType(decl.getType(), value, initExpr);
        gen.checkNativeInitializer(name, initExpr, value, pirType);
        symbolTable.define(name, pirType);
        var rest = continuation.apply(stmts, index + 1);
        return new PirTerm.Let(name, value, rest);
    }

    @FunctionalInterface
    interface StmtContinuation {
        PirTerm apply(List<Statement> stmts, int index);
    }

    /** Handle a nested WhileStmt inside a loop body. */
    private PirTerm handleNestedLoop(WhileStmt ws, List<Statement> stmts, int index,
                                      StmtContinuation continuation) {
        var innerAccs = gen.detectForEachAccumulators(ws.getBody());
        var innerResult = gen.generateWhileStmt(ws, List.of(ws), 0, null);
        return bindNestedLoopResult(innerAccs, innerResult, stmts, index, continuation);
    }

    /** Handle a nested ForEachStmt inside a loop body. */
    private PirTerm handleNestedForEach(ForEachStmt fes, List<Statement> stmts, int index,
                                         StmtContinuation continuation) {
        var innerAccs = gen.detectForEachAccumulators(fes.getBody());
        var innerResult = gen.generateForEachStmt(fes, List.of(fes), 0, null);
        return bindNestedLoopResult(innerAccs, innerResult, stmts, index, continuation);
    }

    /** Bind the result of a nested loop (single/multi/no-acc) and continue. */
    private PirTerm bindNestedLoopResult(List<String> innerAccs, PirTerm innerResult,
                                          List<Statement> stmts, int index,
                                          StmtContinuation continuation) {
        if (innerAccs.size() == 1) {
            var rest = continuation.apply(stmts, index + 1);
            return new PirTerm.Let(innerAccs.get(0), innerResult, rest);
        } else if (innerAccs.size() > 1) {
            var innerTypes = innerAccs.stream()
                    .map(n -> symbolTable.lookup(n).orElse(new PirType.DataType()))
                    .toList();
            var rest = continuation.apply(stmts, index + 1);
            return unpackAccumulators(innerResult, innerAccs, innerTypes, rest);
        } else {
            var rest = continuation.apply(stmts, index + 1);
            return new PirTerm.Let(PirHelpers.hygienicName("_nested", PirHelpers.freeVariables(rest)),
                    innerResult, rest);
        }
    }
}
