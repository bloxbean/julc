package com.bloxbean.cardano.julc.compiler.pir;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;

import java.util.*;
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

    LoopBodyGenerator(PirGenerator gen, SymbolTable symbolTable) {
        this.gen = gen;
        this.symbolTable = symbolTable;
    }

    // ===== AST inspection =====

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
        if (stmt instanceof ReturnStmt) return true;
        if (stmt instanceof WhileStmt || stmt instanceof ForEachStmt) return false;
        if (stmt instanceof BlockStmt bs) {
            return bs.getStatements().stream().anyMatch(this::containsReturn);
        }
        if (stmt instanceof IfStmt is) {
            if (containsReturn(is.getThenStmt())) return true;
            return is.getElseStmt().map(this::containsReturn).orElse(false);
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
        var tupleVar = new PirTerm.Var("__t", new PirType.ListType(new PirType.DataType()));
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
        return new PirTerm.Let("__t", tuple, result);
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

        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && ne.getNameAsString().equals(accName)) {
                var value = gen.generateExpression(ae.getValue());
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let(accName, value, rest);
            }
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var value = gen.generateExpression(ae.getValue());
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

        if (stmt instanceof BreakStmt) {
            return new PirTerm.Var(accName, accType);
        }
        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && ne.getNameAsString().equals(accName)) {
                var value = gen.generateExpression(ae.getValue());
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
            return new PirTerm.Let("_if", ifExpr, rest);
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

        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && accNames.contains(ne.getNameAsString())) {
                var value = gen.generateExpression(ae.getValue());
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var value = gen.generateExpression(ae.getValue());
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

        if (stmt instanceof BreakStmt) {
            return packAccumulators(accNames, accTypes);
        }
        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && accNames.contains(ne.getNameAsString())) {
                var value = gen.generateExpression(ae.getValue());
                if (index + 1 < stmts.size() && stmts.get(index + 1) instanceof BreakStmt) {
                    var rest = packAccumulators(accNames, accTypes);
                    return new PirTerm.Let(ne.getNameAsString(), value, rest);
                }
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var value = gen.generateExpression(ae.getValue());
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

    /** Compile a variable declaration and continue with the provided continuation. */
    private PirTerm compileVarDeclThenContinue(VariableDeclarationExpr vde,
                                                List<Statement> stmts, int index,
                                                StmtContinuation continuation) {
        var decl = vde.getVariable(0);
        var name = decl.getNameAsString();
        var initExpr = decl.getInitializer().orElseThrow(
                () -> new CompilerException("Variable must be initialized: " + name
                        + ". Hint: On-chain variables need initial values, e.g. var " + name + " = BigInteger.ZERO;"));
        var value = gen.generateExpression(initExpr);
        var pirType = gen.inferType(decl.getType(), value, initExpr);
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
        var innerResult = gen.generateWhileStmt(ws, List.of(ws), 0);
        return bindNestedLoopResult(innerAccs, innerResult, stmts, index, continuation);
    }

    /** Handle a nested ForEachStmt inside a loop body. */
    private PirTerm handleNestedForEach(ForEachStmt fes, List<Statement> stmts, int index,
                                         StmtContinuation continuation) {
        var innerAccs = gen.detectForEachAccumulators(fes.getBody());
        var innerResult = gen.generateForEachStmt(fes, List.of(fes), 0);
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
            return new PirTerm.Let("_nested", innerResult, rest);
        }
    }
}
