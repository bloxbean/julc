package com.bloxbean.cardano.julc.compiler.pir;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;

import java.util.*;
import java.util.function.Function;

/**
 * Analyzes AST patterns to determine whether while-loop accumulators hold
 * plain lists, pair lists (MapType), or raw Data.
 * <p>
 * This is pure AST analysis — no PIR terms are generated, and no PirGenerator state is mutated.
 */
final class AccumulatorTypeAnalyzer {

    private AccumulatorTypeAnalyzer() {}

    /**
     * Refine accumulator types by analyzing loop body and preceding statements
     * for evidence of pair-list (MapType) usage.
     */
    static List<PirType> refineAccumulatorTypes(WhileStmt ws, List<String> accNames,
                                                 List<PirType> initialTypes,
                                                 List<Statement> precedingStmts) {
        var pairListNames = collectPairListNames(ws, precedingStmts);

        var result = new ArrayList<>(initialTypes);
        // Pass 1: Detect MapType from direct evidence
        for (int i = 0; i < accNames.size(); i++) {
            if (!(result.get(i) instanceof PirType.DataType)) continue;
            String accName = accNames.get(i);
            if (isInitializedFromPairListSource(accName, precedingStmts, pairListNames)) {
                result.set(i, new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                continue;
            }
            boolean hasMkNilPairData = hasBuiltinAssignment(ws.getBody(), accName, "mkNilPairData");
            if (hasMkNilPairData) {
                result.set(i, new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                continue;
            }
            boolean hasTailList = hasBuiltinAssignment(ws.getBody(), accName, "tailList");
            boolean hasMkNilData = hasBuiltinAssignment(ws.getBody(), accName, "mkNilData");
            boolean hasMkCons = hasBuiltinAssignment(ws.getBody(), accName, "mkCons");
            if (hasTailList || hasMkNilData || hasMkCons) {
                if (hasTailList && !hasMkNilData && !hasMkCons
                        && isInitializedFromPairListSource(accName, precedingStmts, pairListNames)) {
                    result.set(i, new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                } else if (hasMkCons && isInitializedFromPairListSource(accName, precedingStmts, pairListNames)) {
                    result.set(i, new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                } else if (hasMkCons && bodyUsesMkPairDataForCons(ws.getBody(), accName)) {
                    result.set(i, new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                } else if (hasTailList && !hasMkNilData && !hasMkCons
                        && bodyUsesPairOpsOnCursor(ws.getBody(), accName)) {
                    result.set(i, new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                } else {
                    result.set(i, new PirType.ListType(new PirType.DataType()));
                }
            }
        }
        // Pass 2: Propagate MapType to sibling cursors
        boolean changed = true;
        while (changed) {
            changed = false;
            boolean anyMapType = result.stream().anyMatch(t -> t instanceof PirType.MapType);
            if (!anyMapType) break;
            for (int i = 0; i < accNames.size(); i++) {
                if (result.get(i) instanceof PirType.ListType) {
                    String accName = accNames.get(i);
                    boolean hasTailList = hasBuiltinAssignment(ws.getBody(), accName, "tailList");
                    boolean hasMkCons = hasBuiltinAssignment(ws.getBody(), accName, "mkCons");
                    if (hasTailList && !hasMkCons) {
                        result.set(i, new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                        changed = true;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Collect variable/parameter names known to hold pair lists (MapType).
     */
    static Set<String> collectPairListNames(WhileStmt ws, List<Statement> precedingStmts) {
        var names = new HashSet<String>();
        var methodDecl = ws.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class);
        if (methodDecl.isPresent()) {
            for (var param : methodDecl.get().getParameters()) {
                if (isDeclaredAsMapData(param.getType())) {
                    names.add(param.getNameAsString());
                }
            }
        }
        for (var stmt : precedingStmts) {
            if (stmt instanceof ExpressionStmt es
                    && es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                if (decl.getInitializer().isPresent()) {
                    var init = decl.getInitializer().get();
                    if (exprIsPairListSource(init)) {
                        names.add(decl.getNameAsString());
                    } else if (isDeclaredAsMapData(decl.getType())) {
                        names.add(decl.getNameAsString());
                    } else if (init instanceof NameExpr ne && names.contains(ne.getNameAsString())) {
                        names.add(decl.getNameAsString());
                    }
                }
            }
        }
        return names;
    }

    /** Check if a Java type declaration is MapData. */
    static boolean isDeclaredAsMapData(com.github.javaparser.ast.type.Type type) {
        if (type instanceof com.github.javaparser.ast.type.ClassOrInterfaceType ct) {
            return ct.getNameAsString().equals("MapData");
        }
        return false;
    }

    /** Check if an expression is a direct pair-list source: mkNilPairData() or unMapData(). */
    static boolean exprIsPairListSource(Expression expr) {
        if (expr instanceof MethodCallExpr mce) {
            var name = mce.getNameAsString();
            return name.equals("mkNilPairData") || name.equals("unMapData");
        }
        return false;
    }

    /**
     * Check if accumulator is initialized from a pair-list source.
     */
    static boolean isInitializedFromPairListSource(String varName, List<Statement> precedingStmts,
                                                    Set<String> pairListNames) {
        if (isInitializedFromPairList(varName, precedingStmts)) return true;
        if (isInitializedFromMapType(varName, precedingStmts)) return true;
        for (int i = precedingStmts.size() - 1; i >= 0; i--) {
            var stmt = precedingStmts.get(i);
            if (stmt instanceof ExpressionStmt es
                    && es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                if (decl.getNameAsString().equals(varName) && decl.getInitializer().isPresent()) {
                    var init = decl.getInitializer().get();
                    if (init instanceof NameExpr ne && pairListNames.contains(ne.getNameAsString())) {
                        return true;
                    }
                }
            }
        }
        return pairListNames.contains(varName);
    }

    /**
     * Check if a variable's initial declaration is assigned from a MapType source.
     */
    static boolean isInitializedFromMapType(String varName, List<Statement> precedingStmts) {
        for (int i = precedingStmts.size() - 1; i >= 0; i--) {
            var stmt = precedingStmts.get(i);
            if (stmt instanceof ExpressionStmt es
                    && es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                if (decl.getNameAsString().equals(varName) && decl.getInitializer().isPresent()) {
                    return exprResolvesToMapType(decl.getInitializer().get(), precedingStmts);
                }
            }
        }
        return false;
    }

    /**
     * Check if a variable's initial declaration is assigned from a pair list source (mkNilPairData).
     */
    static boolean isInitializedFromPairList(String varName, List<Statement> precedingStmts) {
        for (int i = precedingStmts.size() - 1; i >= 0; i--) {
            var stmt = precedingStmts.get(i);
            if (stmt instanceof ExpressionStmt es
                    && es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                if (decl.getNameAsString().equals(varName) && decl.getInitializer().isPresent()) {
                    return exprResolvesToPairList(decl.getInitializer().get(), precedingStmts);
                }
            }
        }
        return false;
    }

    /**
     * Check if an expression resolves to a pair list type.
     */
    static boolean exprResolvesToPairList(Expression expr, List<Statement> precedingStmts) {
        if (expr instanceof MethodCallExpr mce) {
            String name = mce.getNameAsString();
            if (name.equals("mkNilPairData") || name.equals("unMapData")) {
                return true;
            }
        }
        if (expr instanceof NameExpr ne) {
            return isInitializedFromPairList(ne.getNameAsString(), precedingStmts);
        }
        return false;
    }

    /**
     * Check if an expression resolves to a MapType value.
     */
    static boolean exprResolvesToMapType(Expression expr, List<Statement> precedingStmts) {
        if (expr instanceof MethodCallExpr mce && mce.getNameAsString().equals("unMapData")) {
            return true;
        }
        if (expr instanceof NameExpr ne) {
            return isInitializedFromMapType(ne.getNameAsString(), precedingStmts);
        }
        return false;
    }

    /** Check if any assignment to varName in the statement tree uses a specific Builtins method. */
    static boolean hasBuiltinAssignment(Statement body, String varName, String builtinMethod) {
        var stmts = PirHelpers.blockStmts(body);
        for (var stmt : stmts) {
            if (stmt instanceof ExpressionStmt es
                    && es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && ne.getNameAsString().equals(varName)
                    && ae.getValue() instanceof MethodCallExpr mce
                    && mce.getNameAsString().equals(builtinMethod)) {
                return true;
            }
            if (stmt instanceof IfStmt is) {
                if (hasBuiltinAssignment(is.getThenStmt(), varName, builtinMethod)) return true;
                if (is.getElseStmt().isPresent()
                        && hasBuiltinAssignment(is.getElseStmt().get(), varName, builtinMethod)) return true;
            }
            if (stmt instanceof BlockStmt bs) {
                if (hasBuiltinAssignment(bs, varName, builtinMethod)) return true;
            }
        }
        return false;
    }

    /** Check if mkCons assignments to accName use mkPairData items. */
    static boolean bodyUsesMkPairDataForCons(Statement body, String accName) {
        return bodyContainsMethodCall(body, "mkPairData");
    }

    /**
     * Check if a tailList cursor's elements are treated as pairs in the loop body.
     */
    static boolean bodyUsesPairOpsOnCursor(Statement body, String cursorName) {
        String headVar = findHeadListVar(body, cursorName);
        if (headVar == null) return false;
        return bodyCallsPairOpOn(body, headVar);
    }

    /** Find the variable name assigned from headList(cursorName). */
    static String findHeadListVar(Statement body, String cursorName) {
        var stmts = PirHelpers.blockStmts(body);
        for (var stmt : stmts) {
            var found = findHeadListVarInStmt(stmt, cursorName);
            if (found != null) return found;
        }
        return null;
    }

    static String findHeadListVarInStmt(Statement stmt, String cursorName) {
        if (stmt instanceof ExpressionStmt es
                && es.getExpression() instanceof VariableDeclarationExpr vde) {
            var decl = vde.getVariable(0);
            if (decl.getInitializer().isPresent()) {
                var init = decl.getInitializer().get();
                if (init instanceof MethodCallExpr mce
                        && mce.getNameAsString().equals("headList")
                        && !mce.getArguments().isEmpty()
                        && mce.getArgument(0) instanceof NameExpr ne
                        && ne.getNameAsString().equals(cursorName)) {
                    return decl.getNameAsString();
                }
            }
        }
        if (stmt instanceof IfStmt is) {
            var found = findHeadListVar(is.getThenStmt(), cursorName);
            if (found != null) return found;
            if (is.getElseStmt().isPresent()) {
                found = findHeadListVar(is.getElseStmt().get(), cursorName);
                if (found != null) return found;
            }
        }
        if (stmt instanceof BlockStmt bs) {
            for (var s : bs.getStatements()) {
                var found = findHeadListVarInStmt(s, cursorName);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Check if fstPair(varName) or sndPair(varName) appears in the body. */
    static boolean bodyCallsPairOpOn(Statement body, String varName) {
        var stmts = PirHelpers.blockStmts(body);
        for (var stmt : stmts) {
            if (containsPairOpOnVar(stmt, varName)) return true;
        }
        return false;
    }

    static boolean containsPairOpOnVar(com.github.javaparser.ast.Node node, String varName) {
        if (node instanceof MethodCallExpr mce) {
            var name = mce.getNameAsString();
            if ((name.equals("fstPair") || name.equals("sndPair"))
                    && !mce.getArguments().isEmpty()
                    && mce.getArgument(0) instanceof NameExpr ne
                    && ne.getNameAsString().equals(varName)) {
                return true;
            }
        }
        for (var child : node.getChildNodes()) {
            if (containsPairOpOnVar(child, varName)) return true;
        }
        return false;
    }

    /** Check if a statement tree contains any call to the given method name. */
    static boolean bodyContainsMethodCall(Statement body, String methodName) {
        var stmts = PirHelpers.blockStmts(body);
        for (var stmt : stmts) {
            if (containsMethodCallExpr(stmt, methodName)) return true;
        }
        return false;
    }

    /** Recursively check if a node tree contains a MethodCallExpr with the given name. */
    static boolean containsMethodCallExpr(com.github.javaparser.ast.Node node, String methodName) {
        if (node instanceof MethodCallExpr mce && mce.getNameAsString().equals(methodName)) {
            return true;
        }
        for (var child : node.getChildNodes()) {
            if (containsMethodCallExpr(child, methodName)) return true;
        }
        return false;
    }

    /**
     * Find accumulator name from break patterns.
     *
     * @param typeLookup function to check if a variable name is defined in scope
     */
    static String findAccumulatorInBreakPattern(List<Statement> stmts,
                                                 Function<String, Optional<PirType>> typeLookup) {
        for (var stmt : stmts) {
            if (stmt instanceof ExpressionStmt es
                    && es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var name = ne.getNameAsString();
                if (typeLookup.apply(name).isPresent()) return name;
            }
        }
        for (var stmt : stmts) {
            if (stmt instanceof IfStmt is) {
                var accName = findAccumulatorInBlock(is.getThenStmt(), typeLookup);
                if (accName != null) return accName;
                if (is.getElseStmt().isPresent()) {
                    accName = findAccumulatorInBlock(is.getElseStmt().get(), typeLookup);
                    if (accName != null) return accName;
                }
            }
        }
        return null;
    }

    static String findAccumulatorInBlock(Statement stmt,
                                          Function<String, Optional<PirType>> typeLookup) {
        List<Statement> inner;
        if (stmt instanceof BlockStmt bs) inner = bs.getStatements();
        else inner = List.of(stmt);
        for (int i = 0; i < inner.size() - 1; i++) {
            if (inner.get(i) instanceof ExpressionStmt es
                    && es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && inner.get(i + 1) instanceof BreakStmt) {
                var name = ne.getNameAsString();
                if (typeLookup.apply(name).isPresent()) return name;
            }
        }
        return null;
    }

    /**
     * Detect accumulator variables in a for-each or while loop body.
     *
     * @param typeLookup function to check if a variable name is defined in scope
     */
    static List<String> detectForEachAccumulators(Statement bodyStmt,
                                                   Function<String, Optional<PirType>> typeLookup) {
        List<Statement> stmts;
        if (bodyStmt instanceof BlockStmt bs) stmts = bs.getStatements();
        else stmts = List.of(bodyStmt);
        if (stmts.isEmpty()) return List.of();
        var accNames = new LinkedHashSet<String>();
        collectAccumulatorAssignments(stmts, accNames, typeLookup);
        return new ArrayList<>(accNames);
    }

    /**
     * Collect accumulator assignments from statement list.
     *
     * @param typeLookup function to check if a variable name is defined in scope
     */
    static void collectAccumulatorAssignments(List<Statement> stmts, LinkedHashSet<String> accNames,
                                               Function<String, Optional<PirType>> typeLookup) {
        for (var stmt : stmts) {
            if (stmt instanceof ExpressionStmt es && es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var name = ne.getNameAsString();
                if (typeLookup.apply(name).isPresent()) accNames.add(name);
            }
            if (stmt instanceof IfStmt is) {
                collectAccumulatorAssignments(PirHelpers.blockStmts(is.getThenStmt()), accNames, typeLookup);
                is.getElseStmt().ifPresent(e ->
                        collectAccumulatorAssignments(PirHelpers.blockStmts(e), accNames, typeLookup));
            }
            if (stmt instanceof BlockStmt bs) {
                collectAccumulatorAssignments(bs.getStatements(), accNames, typeLookup);
            }
            if (stmt instanceof WhileStmt ws) {
                collectAccumulatorAssignments(PirHelpers.blockStmts(ws.getBody()), accNames, typeLookup);
            }
            if (stmt instanceof ForEachStmt fes) {
                collectAccumulatorAssignments(PirHelpers.blockStmts(fes.getBody()), accNames, typeLookup);
            }
        }
    }
}
