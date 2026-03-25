package com.bloxbean.cardano.julc.compiler.pir;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.desugar.LoopDesugarer;
import com.bloxbean.cardano.julc.compiler.desugar.PatternMatchDesugarer;
import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.julc.compiler.resolve.TypeResolver;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;

import com.bloxbean.cardano.julc.compiler.CompilerOptions;
import com.bloxbean.cardano.julc.compiler.resolve.LibraryMethodRegistry;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.compiler.util.StringUtils;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/**
 * Compiles JavaParser AST to PIR terms.
 * Handles expressions, statements, and method bodies.
 */
public class PirGenerator {

    private final TypeResolver typeResolver;
    private final SymbolTable symbolTable;
    private final StdlibLookup stdlibLookup;
    private final TypeMethodRegistry typeMethodRegistry;
    private final String libraryClassName; // non-null when compiling library class methods
    private final CompilerOptions options;
    private final List<CompilerDiagnostic> collectedErrors = new ArrayList<>();
    private final LoopDesugarer loopDesugarer = new LoopDesugarer();

    /** PIR term → Java source location. Only populated when source maps are enabled. */
    private final IdentityHashMap<PirTerm, SourceLocation> pirPositions = new IdentityHashMap<>();
    private String forEachAccumulatorVar; // non-null when compiling fold body
    private Function<PirTerm, PirTerm> breakContinueFn; // non-null when compiling break-capable fold body
    private Set<String> multiAccVars = Set.of(); // non-empty when compiling multi-acc fold body
    private Set<String> hofUnwrappedVars = Set.of(); // param names unwrapped by HOF lambda Let-binding (prevents double-unwrap)
    /**
     * When true, each {@code return <boolExpr>} is compiled as
     * {@code IfThenElse(expr, True, Error)} with the Error mapped to the return statement.
     * This allows source maps to pinpoint which return statement caused a validator to fail.
     * Set by the compiler for boolean entrypoint methods when source maps are enabled.
     */
    private boolean booleanReturnGuard = false;
    private boolean booleanReturnGuardActive = false; // true when inside a boolean method with guard enabled
    /**
     * Execute a body-generation action within a loop body context.
     * Previously used to reject nested loops; now a simple pass-through since nested loops are supported.
     */
    private <T> T withLoopBodyFlag(java.util.function.Supplier<T> action) {
        return action.get();
    }

    public PirGenerator(TypeResolver typeResolver, SymbolTable symbolTable) {
        this(typeResolver, symbolTable, null, TypeMethodRegistry.defaultRegistry(), null, new CompilerOptions());
    }

    public PirGenerator(TypeResolver typeResolver, SymbolTable symbolTable, StdlibLookup stdlibLookup) {
        this(typeResolver, symbolTable, stdlibLookup, TypeMethodRegistry.defaultRegistry(), null, new CompilerOptions());
    }

    public PirGenerator(TypeResolver typeResolver, SymbolTable symbolTable,
                        StdlibLookup stdlibLookup, TypeMethodRegistry typeMethodRegistry) {
        this(typeResolver, symbolTable, stdlibLookup, typeMethodRegistry, null, new CompilerOptions());
    }

    public PirGenerator(TypeResolver typeResolver, SymbolTable symbolTable,
                        StdlibLookup stdlibLookup, TypeMethodRegistry typeMethodRegistry,
                        String libraryClassName) {
        this(typeResolver, symbolTable, stdlibLookup, typeMethodRegistry, libraryClassName, new CompilerOptions());
    }

    public PirGenerator(TypeResolver typeResolver, SymbolTable symbolTable,
                        StdlibLookup stdlibLookup, TypeMethodRegistry typeMethodRegistry,
                        String libraryClassName, CompilerOptions options) {
        this.typeResolver = typeResolver;
        this.symbolTable = symbolTable;
        this.stdlibLookup = stdlibLookup;
        this.typeMethodRegistry = typeMethodRegistry;
        this.libraryClassName = libraryClassName;
        this.options = options != null ? options : new CompilerOptions();
    }

    /**
     * Return collected (non-fatal) errors from PIR generation.
     * These are errors where generation could continue with an Error placeholder.
     */
    public List<CompilerDiagnostic> getCollectedErrors() {
        return List.copyOf(collectedErrors);
    }

    /**
     * Get the PIR term → source location map.
     * Only populated when {@link CompilerOptions#isSourceMapEnabled()} is true.
     */
    public IdentityHashMap<PirTerm, SourceLocation> getPirPositions() {
        return pirPositions;
    }

    /**
     * Enable boolean return guard: each {@code return <boolExpr>} becomes
     * {@code IfThenElse(expr, True, Error)} with the Error source-mapped to the return statement.
     * Call this before generating a boolean entrypoint method when source maps are enabled.
     */
    public void setBooleanReturnGuard(boolean enabled) {
        this.booleanReturnGuard = enabled;
    }

    /**
     * If booleanReturnGuard is active, wrap the return value as:
     * {@code IfThenElse(expr, True, Error)} where Error is source-mapped to the return statement.
     * Otherwise returns expr unchanged.
     */
    private PirTerm applyBooleanReturnGuard(PirTerm expr, com.github.javaparser.ast.Node sourceNode) {
        if (!booleanReturnGuardActive) return expr;
        var errorTerm = new PirTerm.Error(new PirType.BoolType());
        recordPosition(errorTerm, sourceNode);
        var guarded = new PirTerm.IfThenElse(expr, new PirTerm.Const(Constant.bool(true)), errorTerm);
        recordPosition(guarded, sourceNode);
        return guarded;
    }

    /**
     * Record the Java source position for a PIR term (only when source maps are enabled).
     */
    private void recordPosition(PirTerm term, Node javaNode) {
        if (!options.isSourceMapEnabled() || term == null || javaNode == null) return;
        javaNode.getRange().ifPresent(range -> {
            var fileName = javaNode.findCompilationUnit()
                    .flatMap(cu -> cu.getStorage().map(s -> s.getFileName()))
                    .orElse(null);
            // Use a short fragment of the Java expression for display
            var fragment = javaNode.toString();
            if (fragment.length() > 100) {
                fragment = fragment.substring(0, 97) + "...";
            }
            pirPositions.put(term, new SourceLocation(
                    fileName, range.begin.line, range.begin.column, fragment));
        });
    }

    /**
     * Record a non-fatal error and return a PirTerm.Error placeholder
     * so compilation can continue and report multiple errors.
     */
    private PirTerm collectError(String msg, String suggestion, Node node) {
        var location = extractLocation(node);
        String fileName = "<source>";
        int line = 0, col = 0;
        if (node != null) {
            var range = node.getRange();
            if (range.isPresent()) {
                line = range.get().begin.line;
                col = range.get().begin.column;
            }
            var cu = node.findCompilationUnit();
            fileName = cu.flatMap(c -> c.getStorage())
                    .map(s -> s.getFileName())
                    .orElse("<source>");
        }
        collectedErrors.add(new CompilerDiagnostic(
                CompilerDiagnostic.Level.ERROR, msg, fileName, line, col, suggestion));
        return new PirTerm.Error(new PirType.DataType());
    }

    /**
     * Record a non-fatal warning diagnostic. Does not produce a placeholder term.
     */
    private void collectWarning(String msg, String suggestion, Node node) {
        String fileName = "<source>";
        int line = 0, col = 0;
        if (node != null) {
            var range = node.getRange();
            if (range.isPresent()) {
                line = range.get().begin.line;
                col = range.get().begin.column;
            }
            var cu = node.findCompilationUnit();
            fileName = cu.flatMap(c -> c.getStorage())
                    .map(s -> s.getFileName())
                    .orElse("<source>");
        }
        collectedErrors.add(new CompilerDiagnostic(
                CompilerDiagnostic.Level.WARNING, msg, fileName, line, col, suggestion));
    }

    /**
     * Check for type mismatches when calling a library method where the caller
     * passes a specific primitive type but the library expects DataType.
     * This produces a WARNING (not an error) since it doesn't block compilation.
     */
    private void checkCrossLibraryTypeWarnings(String className, String methodName,
                                                MethodCallExpr mce, List<PirType> argPirTypes) {
        // Find the LibraryMethodRegistry from the stdlibLookup chain
        LibraryMethodRegistry registry = findLibraryRegistry(stdlibLookup);
        if (registry == null) return;

        var key = className + "." + methodName;
        var method = registry.lookupMethod(key);
        if (method.isEmpty()) return;

        var expectedTypes = LibraryMethodRegistry.getParamTypes(method.get().type());
        var args = mce.getArguments();
        for (int i = 0; i < argPirTypes.size() && i < expectedTypes.size(); i++) {
            var callerType = argPirTypes.get(i);
            var calleeType = expectedTypes.get(i);
            if (isSpecificPrimitiveType(callerType) && calleeType instanceof PirType.DataType) {
                var argNode = i < args.size() ? args.get(i) : mce;
                collectWarning(
                        "Argument " + (i + 1) + " to " + key + "() has type "
                                + LibraryMethodRegistry.pirTypeName(callerType)
                                + " but the library method expects Data. "
                                + "The library will receive a decoded primitive instead of raw Data, "
                                + "which may cause runtime errors.",
                        "Use PlutusData instead of "
                                + LibraryMethodRegistry.pirTypeName(callerType)
                                + " for this argument, or change the library method parameter type.",
                        argNode);
            }
        }
    }

    private static LibraryMethodRegistry findLibraryRegistry(StdlibLookup lookup) {
        if (lookup instanceof LibraryMethodRegistry r) return r;
        if (lookup instanceof CompositeStdlibLookup composite) {
            for (var inner : composite.getLookups()) {
                if (inner instanceof LibraryMethodRegistry r) return r;
            }
        }
        return null;
    }

    private static boolean isSpecificPrimitiveType(PirType type) {
        return type instanceof PirType.ByteStringType
                || type instanceof PirType.IntegerType
                || type instanceof PirType.BoolType
                || type instanceof PirType.StringType
                || type instanceof PirType.ListType
                || type instanceof PirType.MapType;
    }

    /**
     * Generate PIR for a method body. Returns a lambda term wrapping the body.
     */
    public PirTerm generateMethod(MethodDeclaration method) {
        var params = method.getParameters();
        var body = method.getBody().orElseThrow(
                () -> new CompilerException("Method must have a body: " + method.getNameAsString()));

        // Check for missing return paths in non-void methods
        var returnType = method.getTypeAsString();
        boolean isVoid = returnType.equals("void");
        if (!isVoid && !allPathsReturn(body.getStatements())) {
            collectError("Method '" + method.getNameAsString() + "' may not return a value on all execution paths",
                    "Ensure all code paths end with a 'return' statement.", method);
        }

        // Activate boolean return guard for boolean methods when source maps enabled
        boolean prevGuard = this.booleanReturnGuardActive;
        if (booleanReturnGuard && returnType.equals("boolean")) {
            this.booleanReturnGuardActive = true;
        }

        // Register parameters in scope
        symbolTable.pushScope();
        for (var param : params) {
            var pirType = typeResolver.resolve(param.getType());
            symbolTable.define(param.getNameAsString(), pirType);
        }

        PirTerm bodyTerm = generateBlock(body);
        this.booleanReturnGuardActive = prevGuard;
        symbolTable.popScope();

        // Wrap body in lambda chain: \p1 -> \p2 -> ... -> body
        PirTerm result = bodyTerm;
        for (int i = params.size() - 1; i >= 0; i--) {
            var param = params.get(i);
            var pirType = typeResolver.resolve(param.getType());
            result = new PirTerm.Lam(param.getNameAsString(), pirType, result);
        }
        recordPosition(result, method);
        return result;
    }

    PirTerm generateBlock(BlockStmt block) {
        var stmts = block.getStatements();
        if (stmts.isEmpty()) {
            return new PirTerm.Const(Constant.unit());
        }
        return generateStatements(stmts, 0);
    }

    private PirTerm generateStatements(List<Statement> stmts, int index) {
        if (index >= stmts.size()) {
            return new PirTerm.Const(Constant.unit());
        }
        var stmt = stmts.get(index);
        if (stmt instanceof ReturnStmt rs) {
            var result = rs.getExpression().map(this::generateExpression)
                    .orElse(new PirTerm.Const(Constant.unit()));
            recordPosition(result, rs);
            return applyBooleanReturnGuard(result, rs);
        }
        if (stmt instanceof YieldStmt ys) {
            return generateExpression(ys.getExpression());
        }
        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                var name = decl.getNameAsString();
                var initExpr = decl.getInitializer().orElseThrow(
                        () -> new CompilerException("Variable must be initialized: " + name
                                + ". Hint: On-chain variables need initial values, e.g. var " + name + " = BigInteger.ZERO;"));
                var value = generateExpression(initExpr);
                var pirType = inferType(decl.getType(), value, initExpr);
                symbolTable.define(name, pirType);
                var body = generateStatements(stmts, index + 1);
                return new PirTerm.Let(name, value, body);
            }
            // Accumulator assignment in for-each fold: return value as the new accumulator
            if (forEachAccumulatorVar != null
                    && es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && ne.getNameAsString().equals(forEachAccumulatorVar)
                    && index + 1 >= stmts.size()) {
                return generateExpression(ae.getValue());
            }
            // Non-declaration expression statement: evaluate and continue
            var expr = generateExpression(es.getExpression());
            var rest = generateStatements(stmts, index + 1);
            return new PirTerm.Let("_", expr, rest);
        }
        if (stmt instanceof IfStmt is) {
            return generateIfStmt(is, stmts, index);
        }
        if (stmt instanceof ForEachStmt fes) {
            return generateForEachStmt(fes, stmts, index);
        }
        if (stmt instanceof WhileStmt ws) {
            return generateWhileStmt(ws, stmts, index);
        }
        if (stmt instanceof BreakStmt) {
            // break outside break-aware context: return current accumulator
            if (forEachAccumulatorVar != null) {
                var accType = symbolTable.lookup(forEachAccumulatorVar).orElse(new PirType.BoolType());
                return new PirTerm.Var(forEachAccumulatorVar, accType);
            }
            throw enrichedError("break statement outside of a loop",
                    "break can only be used inside for-each or while loops.", stmt);
        }
        collectError("Unsupported statement: " + stmt.getClass().getSimpleName(),
                "Only variable declarations, if/else, for-each, while, return, and expression statements are supported on-chain.",
                stmt);
        // Skip unsupported statement and continue with remaining statements
        return generateStatements(stmts, index + 1);
    }

    private PirTerm generateIfStmt(IfStmt is, List<Statement> followingStmts, int followingIndex) {
        boolean hasFollowing = followingIndex + 1 < followingStmts.size();
        boolean noElse = is.getElseStmt().isEmpty();
        boolean thenReturns = thenBranchReturns(is.getThenStmt());

        // If-fallthrough optimization: when the then-branch returns, there is no else,
        // and there are following statements, use the following statements as the else-branch
        // of the IfThenElse instead of wrapping in a Let (where rest would always execute).
        // This makes `if (cond) { return X; } return Y;` work correctly.
        if (hasFollowing && noElse && thenReturns) {
            var rest = generateStatements(followingStmts, followingIndex + 1);
            if (is.getCondition() instanceof InstanceOfExpr ioe && ioe.getPattern().isPresent()
                    && ioe.getPattern().get() instanceof TypePatternExpr tpe) {
                // For instanceof pattern, generate with rest as the else-branch
                return generateInstanceOfIf(ioe, tpe, is.getThenStmt(), rest);
            } else {
                var cond = generateExpression(is.getCondition());
                var thenTerm = generateStatement(is.getThenStmt());
                var result = new PirTerm.IfThenElse(cond, thenTerm, rest);
                recordPosition(result, is);
                return result;
            }
        }

        PirTerm ifExpr;
        if (is.getCondition() instanceof InstanceOfExpr ioe && ioe.getPattern().isPresent()
                && ioe.getPattern().get() instanceof TypePatternExpr tpe) {
            ifExpr = generateInstanceOfIf(ioe, tpe, is.getThenStmt(), is.getElseStmt());
        } else {
            var cond = generateExpression(is.getCondition());
            var thenTerm = generateStatement(is.getThenStmt());
            var elseTerm = is.getElseStmt()
                    .map(this::generateStatement)
                    .orElse(new PirTerm.Const(Constant.unit()));
            ifExpr = new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
        }
        recordPosition(ifExpr, is);

        // If there are statements following the if, wrap in a let
        if (hasFollowing) {
            var rest = generateStatements(followingStmts, followingIndex + 1);
            return new PirTerm.Let("_if", ifExpr, rest);
        }
        return ifExpr;
    }

    /**
     * Check if a then-branch contains a return statement, meaning execution should not
     * fall through to subsequent statements when the condition is true.
     */
    private boolean thenBranchReturns(Statement thenStmt) {
        if (thenStmt instanceof ReturnStmt) {
            return true;
        }
        if (thenStmt instanceof BlockStmt block) {
            var stmts = block.getStatements();
            if (!stmts.isEmpty()) {
                var last = stmts.get(stmts.size() - 1);
                if (last instanceof ReturnStmt) {
                    return true;
                }
                // Also check for nested if with return (e.g., if (a) { if (b) { return X; } return Y; })
                if (last instanceof IfStmt nestedIf) {
                    return thenBranchReturns(nestedIf.getThenStmt())
                            && nestedIf.getElseStmt().map(this::thenBranchReturns).orElse(false);
                }
            }
        }
        return false;
    }

    /**
     * Check if all execution paths through a list of statements end with a return.
     * Used to detect methods with missing return paths.
     */
    private static boolean allPathsReturn(List<Statement> stmts) {
        if (stmts.isEmpty()) return false;

        // Check from the end of the list for terminal statements
        for (int i = 0; i < stmts.size(); i++) {
            var stmt = stmts.get(i);
            if (stmt instanceof ReturnStmt) return true;

            if (stmt instanceof IfStmt ifStmt) {
                boolean thenReturns = allPathsReturn(blockStmts(ifStmt.getThenStmt()));
                boolean elseReturns = ifStmt.getElseStmt().isPresent()
                        && allPathsReturn(blockStmts(ifStmt.getElseStmt().get()));
                if (thenReturns && elseReturns) return true;
                // if-without-else or partial: check if remaining stmts after this provide a return
                if (thenReturns && i + 1 < stmts.size()) {
                    // Fallthrough case: if (cond) return X; ... return Y;
                    if (allPathsReturn(stmts.subList(i + 1, stmts.size()))) return true;
                }
            }

            // While and for-each loops with accumulators are desugared into fold results.
            // They always produce a value, but they are not "return" statements themselves.
            // A while loop as the last statement is fine as a loop-accumulator return pattern,
            // but we conservatively consider it a return path since the desugaring handles it.
            if (stmt instanceof WhileStmt || stmt instanceof ForEachStmt) {
                // The loop produces a value via accumulator desugaring — treat as return path
                // only when it's the last statement (the accumulator IS the return value)
                if (i == stmts.size() - 1) return true;
            }

            // SwitchExpr used as a statement — if all branches return
            if (stmt instanceof ExpressionStmt es && es.getExpression() instanceof SwitchExpr) {
                // Switch expressions always produce a value, but not necessarily a "return"
                // They only count as terminal if used as a return value
                continue;
            }
        }

        // Check if the last statement itself is terminal
        var last = stmts.get(stmts.size() - 1);
        if (last instanceof ExpressionStmt es) {
            // Variable declarations, assignments, etc. are not returns
            return false;
        }

        return false;
    }

    /**
     * Generate an if-statement where the condition is instanceof with pattern binding.
     * For {@code if (x instanceof Foo f) { ... } else { ... }}, binds {@code f} to {@code x}
     * with the variant's RecordType in the then-block scope.
     */
    private PirTerm generateInstanceOfIf(InstanceOfExpr ioe, TypePatternExpr tpe,
            Statement thenStmt, Optional<Statement> elseStmt) {
        var elseTerm = elseStmt
                .map(this::generateStatement)
                .orElse(new PirTerm.Const(Constant.unit()));
        return generateInstanceOfIf(ioe, tpe, thenStmt, elseTerm);
    }

    /**
     * Generate an instanceof if-statement with a pre-computed else term.
     * Used by the fallthrough optimization to pass following statements as the else-branch.
     */
    private PirTerm generateInstanceOfIf(InstanceOfExpr ioe, TypePatternExpr tpe,
            Statement thenStmt, PirTerm elseTerm) {
        var condTerm = generateInstanceOf(ioe);

        var varName = tpe.getName().asString();
        var varType = typeResolver.resolve(tpe.getType());

        // Generate then-block with pattern variable bound to scrutinee
        symbolTable.pushScope();
        symbolTable.define(varName, varType);
        var scrutineeTerm = generateExpression(ioe.getExpression());
        var thenBody = generateStatement(thenStmt);
        var thenTerm = new PirTerm.Let(varName, scrutineeTerm, thenBody);
        symbolTable.popScope();

        return new PirTerm.IfThenElse(condTerm, thenTerm, elseTerm);
    }

    private PirTerm generateStatement(Statement stmt) {
        if (stmt instanceof BlockStmt block) {
            symbolTable.pushScope();
            var result = generateBlock(block);
            symbolTable.popScope();
            return result;
        }
        if (stmt instanceof ReturnStmt rs) {
            var result = rs.getExpression().map(this::generateExpression)
                    .orElse(new PirTerm.Const(Constant.unit()));
            recordPosition(result, rs);
            return applyBooleanReturnGuard(result, rs);
        }
        if (stmt instanceof YieldStmt ys) {
            return generateExpression(ys.getExpression());
        }
        if (stmt instanceof ExpressionStmt es) {
            return generateExpression(es.getExpression());
        }
        if (stmt instanceof IfStmt is) {
            if (is.getCondition() instanceof InstanceOfExpr ioe && ioe.getPattern().isPresent()
                    && ioe.getPattern().get() instanceof TypePatternExpr tpe) {
                return generateInstanceOfIf(ioe, tpe, is.getThenStmt(), is.getElseStmt());
            }
            var cond = generateExpression(is.getCondition());
            var thenTerm = generateStatement(is.getThenStmt());
            var elseTerm = is.getElseStmt()
                    .map(this::generateStatement)
                    .orElse(new PirTerm.Const(Constant.unit()));
            return new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
        }
        if (stmt instanceof ForEachStmt fes) {
            return generateForEachStmt(fes, List.of(stmt), 0);
        }
        if (stmt instanceof WhileStmt ws) {
            return generateWhileStmt(ws, List.of(stmt), 0);
        }
        throw enrichedError("Unsupported statement: " + stmt.getClass().getSimpleName(),
                "Only variable declarations, if/else, for-each, while, return, and expression statements are supported on-chain.",
                stmt);
    }

    public PirTerm generateExpression(Expression expr) {
        if (expr instanceof IntegerLiteralExpr ile) {
            var val = ile.getValue();
            BigInteger intVal;
            if (val.startsWith("0x") || val.startsWith("0X")) {
                intVal = new BigInteger(val.substring(2), 16);
            } else if (val.startsWith("0b") || val.startsWith("0B")) {
                intVal = new BigInteger(val.substring(2), 2);
            } else if (val.length() > 1 && val.startsWith("0") && !val.equals("0")) {
                intVal = new BigInteger(val.substring(1), 8);
            } else {
                intVal = new BigInteger(val);
            }
            return new PirTerm.Const(Constant.integer(intVal));
        }
        if (expr instanceof LongLiteralExpr lle) {
            var val = lle.getValue();
            if (val.endsWith("L") || val.endsWith("l")) val = val.substring(0, val.length() - 1);
            return new PirTerm.Const(Constant.integer(new BigInteger(val)));
        }
        if (expr instanceof BooleanLiteralExpr ble) {
            return new PirTerm.Const(Constant.bool(ble.getValue()));
        }
        if (expr instanceof StringLiteralExpr sle) {
            return new PirTerm.Const(Constant.string(sle.getValue()));
        }
        if (expr instanceof NameExpr ne) {
            var name = ne.getNameAsString();
            var optType = symbolTable.lookup(name);
            if (optType.isEmpty()) {
                collectError("Undefined variable: " + name, "Check spelling, or add an import if '" + name + "' is a type", ne);
                throw new CompilerException("Undefined variable: " + name);
            }
            return new PirTerm.Var(name, optType.get());
        }
        if (expr instanceof BinaryExpr be) {
            var result = generateBinaryExpr(be);
            recordPosition(result, be);
            return result;
        }
        if (expr instanceof UnaryExpr ue) {
            var result = generateUnaryExpr(ue);
            recordPosition(result, ue);
            return result;
        }
        if (expr instanceof EnclosedExpr ee) {
            return generateExpression(ee.getInner());
        }
        if (expr instanceof MethodCallExpr mce) {
            var result = generateMethodCall(mce);
            recordPosition(result, mce);
            return result;
        }
        if (expr instanceof FieldAccessExpr fae) {
            var result = generateFieldAccess(fae);
            recordPosition(result, fae);
            return result;
        }
        if (expr instanceof ObjectCreationExpr oce) {
            var result = generateObjectCreation(oce);
            recordPosition(result, oce);
            return result;
        }
        if (expr instanceof ConditionalExpr ce) {
            // Ternary: cond ? then : else
            var result = new PirTerm.IfThenElse(
                    generateExpression(ce.getCondition()),
                    generateExpression(ce.getThenExpr()),
                    generateExpression(ce.getElseExpr()));
            recordPosition(result, ce);
            return result;
        }
        if (expr instanceof LambdaExpr le) {
            return generateLambda(le);
        }
        if (expr instanceof SwitchExpr se) {
            var result = generateSwitchExpr(se);
            recordPosition(result, se);
            return result;
        }
        if (expr instanceof InstanceOfExpr ioe) {
            var result = generateInstanceOf(ioe);
            recordPosition(result, ioe);
            return result;
        }
        if (expr instanceof CastExpr ce) {
            var inner = generateExpression(ce.getExpression());
            // Most casts are no-ops at UPLC level. But casting to MapType needs UnMapData
            // so that MapType variables always hold pair lists (consistent with field access).
            // Exception: if the inner expression is already a MapType (pair list), skip UnMapData
            // to avoid double-unwrap (e.g., (JulcMap)(Object) MapLib.empty() where empty() already
            // returns a pair list via MkNilPairData).
            try {
                var castTargetType = typeResolver.resolve(ce.getType());
                if (castTargetType instanceof PirType.MapType) {
                    var innerType = inferPirType(inner);
                    if (!(innerType instanceof PirType.MapType)) {
                        return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), inner);
                    }
                }
            } catch (IllegalArgumentException | CompilerException _) {
                // Unknown cast target type (e.g., Object) — treat as no-op
            }
            return inner;
        }
        if (expr instanceof ArrayCreationExpr ace) {
            // byte[] literal initializer → ByteString constant
            var values = ace.getInitializer().get().getValues();
            byte[] bytes = new byte[values.size()];
            for (int i = 0; i < values.size(); i++) {
                bytes[i] = (byte) values.get(i).asIntegerLiteralExpr().asNumber().intValue();
            }
            return new PirTerm.Const(Constant.byteString(bytes));
        }
        if (expr instanceof AssignExpr ae && ae.getTarget() instanceof NameExpr ne) {
            var name = ne.getNameAsString();
            if ((forEachAccumulatorVar != null && name.equals(forEachAccumulatorVar))
                    || multiAccVars.contains(name)) {
                return generateExpression(ae.getValue());
            }
        }
        String suggestion;
        if (expr instanceof AssignExpr) {
            suggestion = "Variables are immutable on-chain. Use the accumulator pattern in for-each/while loops for mutable state.";
        } else if (expr instanceof ArrayAccessExpr) {
            suggestion = "Array access is not supported on-chain. Use ByteStringLib.at() for byte arrays or ListsLib methods for lists.";
        } else {
            suggestion = "This expression type is not supported in on-chain code.";
        }
        return collectError("Unsupported expression: " + expr.getClass().getSimpleName(), suggestion, expr);
    }

    private PirTerm generateBinaryExpr(BinaryExpr be) {
        var left = generateExpression(be.getLeft());
        var right = generateExpression(be.getRight());

        // Infer operand type for type-aware dispatching
        var leftType = resolveExpressionType(be.getLeft());
        if (leftType instanceof PirType.DataType) leftType = inferPirType(left);
        // If left is still DataType, try the right operand for better type inference
        if (leftType instanceof PirType.DataType) {
            var rightType = resolveExpressionType(be.getRight());
            if (rightType instanceof PirType.DataType) rightType = inferPirType(right);
            if (!(rightType instanceof PirType.DataType)) leftType = rightType;
        }

        return switch (be.getOperator()) {
            case PLUS -> {
                if (leftType instanceof PirType.StringType)
                    yield builtinApp2(DefaultFun.AppendString, left, right);
                if (leftType instanceof PirType.ByteStringType)
                    yield builtinApp2(DefaultFun.AppendByteString, left, right);
                yield builtinApp2(DefaultFun.AddInteger, left, right);
            }
            case MINUS -> builtinApp2(DefaultFun.SubtractInteger, left, right);
            case MULTIPLY -> builtinApp2(DefaultFun.MultiplyInteger, left, right);
            case DIVIDE -> builtinApp2(DefaultFun.DivideInteger, left, right);
            case REMAINDER -> builtinApp2(DefaultFun.RemainderInteger, left, right);
            case EQUALS -> generateEquality(left, right, leftType, false);
            case NOT_EQUALS -> generateEquality(left, right, leftType, true);
            case LESS -> builtinApp2(DefaultFun.LessThanInteger, left, right);
            case LESS_EQUALS -> builtinApp2(DefaultFun.LessThanEqualsInteger, left, right);
            case GREATER -> builtinApp2(DefaultFun.LessThanInteger, right, left); // swap
            case GREATER_EQUALS -> builtinApp2(DefaultFun.LessThanEqualsInteger, right, left); // swap
            case AND -> new PirTerm.IfThenElse(left, right, new PirTerm.Const(Constant.bool(false)));
            case OR -> new PirTerm.IfThenElse(left, new PirTerm.Const(Constant.bool(true)), right);
            default -> collectError("Unsupported operator: " + be.getOperator(),
                    "Supported operators: +, -, *, /, %, ==, !=, <, <=, >, >=, &&, ||", be);
        };
    }

    /**
     * Generate type-aware equality comparison.
     * Dispatches to the correct builtin based on operand type.
     */
    private PirTerm generateEquality(PirTerm left, PirTerm right, PirType leftType, boolean negate) {
        DefaultFun eqFun;
        if (leftType instanceof PirType.ByteStringType) {
            eqFun = DefaultFun.EqualsByteString;
        } else if (leftType instanceof PirType.StringType) {
            eqFun = DefaultFun.EqualsString;
        } else if (leftType instanceof PirType.BoolType) {
            // Bool equality: IfThenElse(a, b, !b)
            var eq = new PirTerm.IfThenElse(left, right,
                    new PirTerm.IfThenElse(right,
                            new PirTerm.Const(Constant.bool(false)),
                            new PirTerm.Const(Constant.bool(true))));
            if (negate) return new PirTerm.IfThenElse(eq,
                    new PirTerm.Const(Constant.bool(false)),
                    new PirTerm.Const(Constant.bool(true)));
            return eq;
        } else if (leftType instanceof PirType.DataType
                || leftType instanceof PirType.RecordType
                || leftType instanceof PirType.SumType) {
            eqFun = DefaultFun.EqualsData;
        } else {
            eqFun = DefaultFun.EqualsInteger; // default: integer
        }
        var result = builtinApp2(eqFun, left, right);
        if (negate) return new PirTerm.IfThenElse(result,
                new PirTerm.Const(Constant.bool(false)),
                new PirTerm.Const(Constant.bool(true)));
        return result;
    }

    private PirTerm generateUnaryExpr(UnaryExpr ue) {
        var operand = generateExpression(ue.getExpression());
        return switch (ue.getOperator()) {
            case LOGICAL_COMPLEMENT -> new PirTerm.IfThenElse(
                    operand,
                    new PirTerm.Const(Constant.bool(false)),
                    new PirTerm.Const(Constant.bool(true)));
            case MINUS -> builtinApp2(DefaultFun.SubtractInteger,
                    new PirTerm.Const(Constant.integer(BigInteger.ZERO)), operand);
            default -> collectError("Unsupported unary operator: " + ue.getOperator(),
                    "Supported unary operators: ! (logical NOT) and - (negation)", ue);
        };
    }

    private PirTerm generateMethodCall(MethodCallExpr mce) {
        var methodName = mce.getNameAsString();
        var args = mce.getArguments();

        // Handle scope.method() -> check stdlib first, then field access
        if (mce.getScope().isPresent()) {
            var scopeExpr = mce.getScope().get();

            // Handle BigInteger.valueOf(n) → integer constant
            if (scopeExpr instanceof NameExpr ne && ne.getNameAsString().equals("BigInteger")
                    && methodName.equals("valueOf") && args.size() == 1) {
                return generateExpression(args.get(0));
            }

            // Auto-recognize fromPlutusData() on known Plutus data types as identity (no stdlib needed)
            if (scopeExpr instanceof NameExpr ne && methodName.equals("fromPlutusData") && args.size() == 1) {
                var resolvedClassName = typeResolver.resolveClassName(ne.getNameAsString());
                var targetType = typeResolver.resolveNameToType(resolvedClassName);
                if (targetType.isPresent()) {
                    options.logf("Resolved fromPlutusData identity: %s.fromPlutusData", ne.getNameAsString());
                    return generateExpression(args.get(0));
                }
            }

            // PlutusData.cast(data, TargetType.class) → identity (same as double-cast)
            if (scopeExpr instanceof NameExpr ne
                    && ne.getNameAsString().equals("PlutusData")
                    && methodName.equals("cast") && args.size() == 2) {
                if (!(args.get(1) instanceof ClassExpr)) {
                    return collectError(
                        "PlutusData.cast() second argument must be a literal ClassName.class expression",
                        "Use PlutusData.cast(data, MyType.class) — a variable holding a Class<?> is not supported on-chain.",
                        mce);
                }
                var classExpr = (ClassExpr) args.get(1);
                options.logf("Resolved PlutusData.cast: %s", classExpr.getType());
                var inner = generateExpression(args.get(0));
                // MapType needs UnMapData (same logic as CastExpr at line 652)
                try {
                    var castTargetType = typeResolver.resolve(classExpr.getType());
                    if (castTargetType instanceof PirType.MapType) {
                        var innerType = inferPirType(inner);
                        if (!(innerType instanceof PirType.MapType)) {
                            return new PirTerm.App(
                                new PirTerm.Builtin(DefaultFun.UnMapData), inner);
                        }
                    }
                } catch (CompilerException _) { }
                return inner;
            }

            // Check if scope is a class name for static stdlib call (e.g., ContextsLib.signedBy)
            if (scopeExpr instanceof NameExpr ne && stdlibLookup != null) {
                var className = ne.getNameAsString();
                // Resolve to FQCN for library class lookup
                var resolvedClassName = typeResolver.resolveClassName(className);
                var compiledArgs = new ArrayList<PirTerm>();
                var argPirTypes = new ArrayList<PirType>();

                // For static HOF methods (ListsLib.map/filter/any/all/find), compile list arg first
                // to infer element type for lambda parameter type inference
                boolean isStaticHof = (className.equals("ListsLib") || resolvedClassName.endsWith(".ListsLib"))
                        && STATIC_HOF_METHODS.contains(methodName) && args.size() >= 2;

                if (isStaticHof) {
                    // Compile list arg (first) to determine element type
                    var listArg = generateExpression(args.get(0));
                    compiledArgs.add(listArg);
                    var listType = resolveExpressionType(args.get(0));
                    if (listType instanceof PirType.DataType) listType = inferPirType(listArg);
                    argPirTypes.add(listType);

                    // Compile remaining args with lambda type inference
                    for (int i = 1; i < args.size(); i++) {
                        var arg = args.get(i);
                        PirTerm argPir;
                        if (arg.isLambdaExpr() && listType instanceof PirType.ListType lt) {
                            var expectedTypes = inferHofLambdaParamTypes(methodName, lt);
                            boolean wrapResult = methodName.equals("map");
                            // foldl lambda has 2 params (acc, elem) — only infer elem type
                            if (methodName.equals("foldl") && i == 1) {
                                // foldl's function lambda — don't infer types (2-param)
                                argPir = generateExpression(arg);
                            } else {
                                argPir = generateLambda(arg.asLambdaExpr(), expectedTypes, wrapResult);
                            }
                        } else {
                            argPir = generateExpression(arg);
                        }
                        compiledArgs.add(argPir);
                        var argType = resolveExpressionType(arg);
                        if (argType instanceof PirType.DataType) argType = inferPirType(argPir);
                        argPirTypes.add(argType);
                    }
                } else {
                    for (var arg : args) {
                        var argPir = generateExpression(arg);
                        compiledArgs.add(argPir);
                        var argType = resolveExpressionType(arg);
                        if (argType instanceof PirType.DataType) argType = inferPirType(argPir);
                        argPirTypes.add(argType);
                    }
                }

                var result = stdlibLookup.lookup(resolvedClassName, methodName, compiledArgs, argPirTypes);
                if (result.isPresent()) {
                    options.logf("Resolved stdlib: %s.%s", className, methodName);
                    checkCrossLibraryTypeWarnings(className, methodName, mce, argPirTypes);
                    return result.get();
                }

            }

            var scope = generateExpression(scopeExpr);

            // Check if scope is a Data-typed parameter with a known record type for field access
            if (args.isEmpty() && scopeExpr instanceof NameExpr ne) {
                var recordField = resolveRecordFieldAccess(ne.getNameAsString(), methodName);
                if (recordField.isPresent()) {
                    return recordField.get().apply(scope);
                }
            }

            // Handle chained record field access: scope.innerRecord().field()
            if (args.isEmpty() && scopeExpr instanceof MethodCallExpr innerMce) {
                var scopeType = resolveMethodCallReturnType(innerMce);
                if (scopeType instanceof PirType.RecordType rt) {
                    for (int i = 0; i < rt.fields().size(); i++) {
                        if (rt.fields().get(i).name().equals(methodName)) {
                            return generateFieldExtraction(scope, i, rt.fields().get(i).type());
                        }
                    }
                }
                // Chained .hash() on a ledger hash / @NewType that already resolved to
                // ByteStringType.  The parent accessor already applied UnBData, so
                // the .hash() accessor is an identity — skip the TypeMethodRegistry
                // dispatch which would apply a redundant UnBData.
                if (scopeType instanceof PirType.ByteStringType
                        && methodName.equals("hash")) {
                    return scope;
                }
            }

            // Dispatch instance methods via TypeMethodRegistry
            {
                var scopeType = resolveExpressionType(scopeExpr);
                if (scopeType instanceof PirType.DataType) scopeType = inferPirType(scope);

                // Compile args — with lambda type inference for HOF methods on ListType
                var compiledArgs = new ArrayList<PirTerm>();
                var argPirTypes = new ArrayList<PirType>();
                boolean isListHof = scopeType instanceof PirType.ListType && LIST_HOF_METHODS.contains(methodName);
                for (var arg : args) {
                    PirTerm argPir;
                    if (isListHof && arg.isLambdaExpr()) {
                        var lt = (PirType.ListType) scopeType;
                        var expectedTypes = inferHofLambdaParamTypes(methodName, lt);
                        boolean wrapResult = methodName.equals("map");
                        argPir = generateLambda(arg.asLambdaExpr(), expectedTypes, wrapResult);
                    } else {
                        argPir = generateExpression(arg);
                    }
                    compiledArgs.add(argPir);
                    var argType = isListHof && arg.isLambdaExpr()
                            ? inferPirType(argPir) : resolveExpressionType(arg);
                    if (argType instanceof PirType.DataType) argType = inferPirType(argPir);
                    argPirTypes.add(argType);
                }

                // 3A: Skip .hash() on HOF-unwrapped ByteStringType vars (prevents double UnBData)
                if (scopeType instanceof PirType.ByteStringType
                        && methodName.equals("hash") && args.isEmpty()
                        && scopeExpr instanceof NameExpr ne
                        && hofUnwrappedVars.contains(ne.getNameAsString())) {
                    return scope; // Already unwrapped by Let-binding — identity
                }

                var registryResult = typeMethodRegistry.dispatch(scope, methodName, compiledArgs, scopeType, argPirTypes);
                if (registryResult.isPresent()) return registryResult.get();

                // Auto-recognize toPlutusData() — encode value as Data
                if (methodName.equals("toPlutusData") && args.isEmpty()) {
                    return PirHelpers.wrapEncode(scope, scopeType);
                }

                // 3B: Handle unregistered no-arg methods on ByteStringType (e.g. TokenName.name(), @NewType fields)
                if (scopeType instanceof PirType.ByteStringType && args.isEmpty()) {
                    if (scopeExpr instanceof NameExpr ne
                            && hofUnwrappedVars.contains(ne.getNameAsString())) {
                        return scope; // HOF-unwrapped — identity
                    }
                    // Chained accessor: parent MethodCallExpr already produced a raw
                    // bytestring (via field extraction / wrapDecode), so another
                    // UnBData would be a double-unwrap.
                    if (scopeExpr instanceof MethodCallExpr) {
                        return scope;
                    }
                    // Standard field extraction: UnBData (same semantics as .hash() for non-HOF context)
                    return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), scope);
                }
            }

            return generateFieldAccessFromMethod(scope, methodName, args);
        }

        // Static method call — try simple name first, then qualified name for library methods
        var resolvedName = methodName;
        var funType = symbolTable.lookup(methodName);
        if (funType.isEmpty() && libraryClassName != null) {
            resolvedName = libraryClassName + "." + methodName;
            funType = symbolTable.lookup(resolvedName);
        }
        if (funType.isPresent()) {
            PirTerm fn = new PirTerm.Var(resolvedName, funType.get());
            for (var arg : args) {
                fn = new PirTerm.App(fn, generateExpression(arg));
            }
            return fn;
        }
        String fuzzyHint = suggestSimilarMethod(methodName);
        String suggestion = !fuzzyHint.isEmpty() ? fuzzyHint
                : "Library methods require class qualification (e.g., MathLib." + methodName + "(...)).";
        return collectError("Unknown method: " + methodName, suggestion, mce);
    }

    /**
     * Resolve a record field accessor call on a Data-typed parameter.
     * If the variable has a RecordType or OptionalType(RecordType), returns a function
     * that generates the field extraction PIR from a scope term.
     */
    private java.util.Optional<java.util.function.Function<PirTerm, PirTerm>> resolveRecordFieldAccess(
            String varName, String fieldName) {
        var varType = symbolTable.lookup(varName);
        if (varType.isEmpty()) return java.util.Optional.empty();

        PirType type = varType.get();
        // Unwrap OptionalType if present
        if (type instanceof PirType.OptionalType opt) {
            type = opt.elemType();
        }
        if (!(type instanceof PirType.RecordType rt)) {
            return java.util.Optional.empty();
        }

        // Find the field index
        int fieldIndex = -1;
        PirType fieldType = null;
        for (int i = 0; i < rt.fields().size(); i++) {
            if (rt.fields().get(i).name().equals(fieldName)) {
                fieldIndex = i;
                fieldType = rt.fields().get(i).type();
                break;
            }
        }
        if (fieldIndex < 0) return java.util.Optional.empty();

        // If the field is already directly in the current scope (e.g. switch pattern destructuring),
        // return a direct variable reference instead of generating field extraction PIR.
        // Use lookupCurrentScope to avoid collisions with outer-scope variables of the same name
        // (e.g. an entrypoint parameter named "datum" conflicting with TxOut.datum() field access).
        var fieldInScope = symbolTable.lookupCurrentScope(fieldName);
        if (fieldInScope.isPresent()) {
            final PirType fType = fieldType;
            return java.util.Optional.of(scope -> new PirTerm.Var(fieldName, fType));
        }

        final int idx = fieldIndex;
        final PirType fType = fieldType;
        return java.util.Optional.of(scope -> generateFieldExtraction(scope, idx, fType));
    }

    /**
     * Generate PIR for extracting a field from a Constr-encoded Data value.
     * UnConstrData(data) → SndPair → TailList^n → HeadList → decode
     */
    private PirTerm generateFieldExtraction(PirTerm data, int fieldIndex, PirType fieldType) {
        // SndPair(UnConstrData(data)) to get fields list
        var fields = new PirTerm.App(
                new PirTerm.Builtin(DefaultFun.SndPair),
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), data));

        // TailList^fieldIndex then HeadList
        PirTerm current = fields;
        for (int i = 0; i < fieldIndex; i++) {
            current = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), current);
        }
        PirTerm rawField = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), current);

        // Decode based on field type
        return wrapDecode(rawField, fieldType);
    }

    private PirTerm wrapDecode(PirTerm data, PirType targetType) {
        return PirHelpers.wrapDecode(data, targetType);
    }

    /**
     * Resolve the PirType of a JavaParser expression without generating PIR.
     * Used for determining if a scope expression is a ListType for method dispatch.
     */
    private PirType resolveExpressionType(Expression expr) {
        if (expr instanceof NameExpr ne) {
            return symbolTable.lookup(ne.getNameAsString()).orElse(new PirType.DataType());
        }
        if (expr instanceof MethodCallExpr mce && mce.getScope().isPresent()) {
            return resolveMethodCallReturnType(mce);
        }
        // Handle constructor calls (e.g., new Tuple2<BigInteger, BigInteger>(...))
        if (expr instanceof ObjectCreationExpr oce) {
            var resolved = typeResolver.resolve(oce.getType());
            if (!(resolved instanceof PirType.DataType)) return resolved;
        }
        // Handle scopeless method calls (static helper methods)
        if (expr instanceof MethodCallExpr mce && mce.getScope().isEmpty()) {
            var methodType = symbolTable.lookup(mce.getNameAsString());
            if (methodType.isPresent()) {
                return extractReturnType(methodType.get());
            }
        }
        // Handle literal expressions for type inference
        if (expr instanceof IntegerLiteralExpr || expr instanceof LongLiteralExpr)
            return new PirType.IntegerType();
        if (expr instanceof StringLiteralExpr) return new PirType.StringType();
        if (expr instanceof BooleanLiteralExpr) return new PirType.BoolType();
        // Handle cast expressions: (RecordType)(Object) data → resolve the target type
        // This enables `var sd = (StateDatum)(Object) rawDatum;` to infer RecordType
        if (expr instanceof CastExpr ce) {
            try {
                var castType = typeResolver.resolve(ce.getType());
                if (!(castType instanceof PirType.DataType)) return castType;
            } catch (IllegalArgumentException | CompilerException _) {
                // Unknown target (e.g., Object) — fall through
            }
            return resolveExpressionType(ce.getExpression());
        }
        return new PirType.DataType();
    }

    /**
     * Infer the return type of a method call expression (for chained access).
     * Handles record field access and known list methods.
     */
    private PirType resolveMethodCallReturnType(MethodCallExpr mce) {
        var methodName = mce.getNameAsString();
        if (mce.getScope().isEmpty()) return new PirType.DataType();
        var scopeExpr = mce.getScope().get();

        // Static fromPlutusData() returns the target type
        if (scopeExpr instanceof NameExpr ne
                && methodName.equals("fromPlutusData") && mce.getArguments().size() == 1) {
            var resolvedClassName = typeResolver.resolveClassName(ne.getNameAsString());
            var targetType = typeResolver.resolveNameToType(resolvedClassName);
            if (targetType.isPresent()) return targetType.get();
        }

        // PlutusData.cast(data, TargetType.class) → resolve type from ClassExpr
        if (scopeExpr instanceof NameExpr ne
                && ne.getNameAsString().equals("PlutusData")
                && methodName.equals("cast") && mce.getArguments().size() == 2
                && mce.getArguments().get(1) instanceof ClassExpr classExpr) {
            try {
                var castType = typeResolver.resolve(classExpr.getType());
                if (!(castType instanceof PirType.DataType)) return castType;
            } catch (IllegalArgumentException | CompilerException _) { }
            var typeName = classExpr.getType().asString();
            var resolvedClassName = typeResolver.resolveClassName(typeName);
            var targetType = typeResolver.resolveNameToType(resolvedClassName);
            if (targetType.isPresent()) return targetType.get();
        }

        // toPlutusData() always returns DataType
        if (methodName.equals("toPlutusData") && mce.getArguments().isEmpty()) {
            return new PirType.DataType();
        }

        // If scope is a variable with RecordType, return the field type
        if (scopeExpr instanceof NameExpr ne && mce.getArguments().isEmpty()) {
            var fieldType = resolveRecordFieldType(ne.getNameAsString(), methodName);
            if (fieldType.isPresent()) return fieldType.get();
        }

        // If scope is itself a method call, resolve recursively for chained access
        if (scopeExpr instanceof MethodCallExpr innerMce && mce.getArguments().isEmpty()) {
            var innerType = resolveMethodCallReturnType(innerMce);
            if (innerType instanceof PirType.RecordType rt) {
                for (var field : rt.fields()) {
                    if (field.name().equals(methodName)) return field.type();
                }
            }
            // List methods that return a list
            if (innerType instanceof PirType.ListType && methodName.equals("tail")) {
                return innerType; // tail returns same list type
            }
        }

        // Static library method call (e.g., OutputLib.outputsAt(...)): look up return type from registry
        if (scopeExpr instanceof NameExpr ne) {
            var registry = findLibraryRegistry(stdlibLookup);
            if (registry != null) {
                var resolvedClassName = typeResolver.resolveClassName(ne.getNameAsString());
                var qualifiedKey = resolvedClassName + "." + methodName;
                var libMethod = registry.lookupMethod(qualifiedKey);
                if (libMethod.isPresent()) {
                    return extractReturnType(libMethod.get().type());
                }
                // Also try simple name lookup (e.g., "OutputLib.outputsAt")
                var simpleKey = ne.getNameAsString() + "." + methodName;
                if (!simpleKey.equals(qualifiedKey)) {
                    libMethod = registry.lookupMethod(simpleKey);
                    if (libMethod.isPresent()) {
                        return extractReturnType(libMethod.get().type());
                    }
                }
            }
        }

        // Delegate to TypeMethodRegistry for return type resolution
        var scopeType = resolveExpressionType(scopeExpr);
        var returnType = typeMethodRegistry.resolveReturnType(scopeType, methodName);
        if (returnType.isPresent()) return returnType.get();

        return new PirType.DataType();
    }

    /**
     * Extract the return type from a FunType chain by walking all parameter types.
     * E.g., FunType(A, FunType(B, C)) -> C
     */
    private static PirType extractReturnType(PirType type) {
        while (type instanceof PirType.FunType ft) {
            type = ft.returnType();
        }
        return type;
    }

    /**
     * Resolve the PirType of a record field without generating extraction PIR.
     * Returns the field type if the variable is a RecordType with the named field.
     */
    private java.util.Optional<PirType> resolveRecordFieldType(String varName, String fieldName) {
        var varType = symbolTable.lookup(varName);
        if (varType.isEmpty()) return java.util.Optional.empty();

        PirType type = varType.get();
        if (type instanceof PirType.OptionalType opt) type = opt.elemType();
        if (!(type instanceof PirType.RecordType rt)) return java.util.Optional.empty();

        for (var field : rt.fields()) {
            if (field.name().equals(fieldName)) return java.util.Optional.of(field.type());
        }
        return java.util.Optional.empty();
    }

    private PirTerm generateFieldAccessFromMethod(PirTerm scope, String methodName,
                                                   com.github.javaparser.ast.NodeList<Expression> args) {
        // For record accessor methods (no args), treat as field access
        if (args.isEmpty()) {
            // This will be compiled to field extraction in UplcGenerator
            return new PirTerm.App(
                    new PirTerm.Var("." + methodName, new PirType.DataType()),
                    scope);
        }
        // Method with args: apply scope + args
        PirTerm fn = new PirTerm.Var(methodName, new PirType.DataType());
        fn = new PirTerm.App(fn, scope);
        for (var arg : args) {
            fn = new PirTerm.App(fn, generateExpression(arg));
        }
        return fn;
    }

    private PirTerm generateFieldAccess(FieldAccessExpr fae) {
        // Handle BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, BigInteger.TEN
        if (fae.getScope() instanceof NameExpr ne && ne.getNameAsString().equals("BigInteger")) {
            return switch (fae.getNameAsString()) {
                case "ZERO" -> new PirTerm.Const(Constant.integer(BigInteger.ZERO));
                case "ONE" -> new PirTerm.Const(Constant.integer(BigInteger.ONE));
                case "TWO" -> new PirTerm.Const(Constant.integer(BigInteger.TWO));
                case "TEN" -> new PirTerm.Const(Constant.integer(BigInteger.TEN));
                default -> throw enrichedError("Unsupported BigInteger field: " + fae.getNameAsString(),
                        "Supported BigInteger fields: ZERO, ONE, TWO, TEN. Use new BigInteger(\"value\") for other constants.", fae);
            };
        }

        // Handle PlutusData.UNIT
        if (fae.getScope() instanceof NameExpr ne && ne.getNameAsString().equals("PlutusData")) {
            if (fae.getNameAsString().equals("UNIT")) {
                // PlutusData.UNIT = Constr(0, [])
                return new PirTerm.App(
                        new PirTerm.App(
                                new PirTerm.Builtin(DefaultFun.ConstrData),
                                new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkNilData),
                                new PirTerm.Const(Constant.unit())));
            }
        }

        var scope = generateExpression(fae.getScope());
        var fieldName = fae.getNameAsString();

        // Handle byte[].length field access
        if (fieldName.equals("length")) {
            var scopeType = resolveExpressionType(fae.getScope());
            if (scopeType instanceof PirType.DataType) scopeType = inferPirType(scope);
            if (scopeType instanceof PirType.ByteStringType) {
                return new PirTerm.App(new PirTerm.Builtin(DefaultFun.LengthOfByteString), scope);
            }
        }

        return new PirTerm.App(
                new PirTerm.Var("." + fieldName, new PirType.DataType()),
                scope);
    }

    private PirTerm generateObjectCreation(ObjectCreationExpr oce) {
        var ct = oce.getType();
        var typeName = ct.getNameAsString(); // simple name for constructor matching
        var resolvedTypeName = typeResolver.resolveTypeWithScope(ct); // FQCN for lookups

        // @NewType constructor is identity — no ConstrData wrapping
        if (typeResolver.isNewType(resolvedTypeName)) {
            if (oce.getArguments().size() != 1) {
                throw enrichedError("@NewType constructor must have exactly 1 argument",
                        "@NewType records have a single field, so the constructor takes 1 argument.", oce);
            }
            return generateExpression(oce.getArguments().get(0));
        }

        // Ledger hash types (ScriptHash, PubKeyHash, etc.) resolve to ByteStringType.
        // Their constructors are identity — the single field IS the underlying byte[].
        try {
            var resolvedType = typeResolver.resolve(ct);
            if (resolvedType instanceof PirType.ByteStringType && oce.getArguments().size() == 1) {
                return generateExpression(oce.getArguments().get(0));
            }
        } catch (IllegalArgumentException | CompilerException _) {
            // Not a known type — continue to other checks
        }

        // Handle new BigInteger("12345") → integer constant
        if (typeName.equals("BigInteger") && oce.getArguments().size() == 1) {
            var arg = oce.getArguments().get(0);
            if (arg instanceof StringLiteralExpr sle) {
                return new PirTerm.Const(Constant.integer(new BigInteger(sle.getValue())));
            }
            throw enrichedError("new BigInteger() requires a string literal argument",
                    "Use new BigInteger(\"12345\") or BigInteger.valueOf(n) for integer constants.", oce);
        }

        // Check if this is a variant of a sealed interface (sum type)
        var sumType = typeResolver.lookupSumTypeForVariant(resolvedTypeName);
        if (sumType.isPresent()) {
            for (var ctor : sumType.get().constructors()) {
                if (ctor.name().equals(typeName)) {
                    var fields = new ArrayList<PirTerm>();
                    for (var arg : oce.getArguments()) {
                        fields.add(generateExpression(arg));
                    }
                    return new PirTerm.DataConstr(ctor.tag(), sumType.get(), fields);
                }
            }
        }

        // For generic Tuple2/Tuple3, resolve type from the ObjectCreationExpr's type args
        // so that DataConstr uses typed fields (e.g., IntegerType) for proper wrapDataEncode
        if (typeName.equals("Tuple2") || typeName.equals("Tuple3")) {
            var resolvedType = typeResolver.resolve(ct);
            if (resolvedType instanceof PirType.RecordType rt) {
                var fields = new ArrayList<PirTerm>();
                for (var arg : oce.getArguments()) {
                    fields.add(generateExpression(arg));
                }
                return new PirTerm.DataConstr(0, rt, fields);
            }
        }

        // Check standalone record type
        var recordType = typeResolver.lookupRecord(resolvedTypeName);
        if (recordType.isPresent()) {
            var fields = new ArrayList<PirTerm>();
            for (var arg : oce.getArguments()) {
                fields.add(generateExpression(arg));
            }
            return new PirTerm.DataConstr(0, recordType.get(), fields);
        }
        throw enrichedError("Cannot construct non-record type: " + typeName,
                "Only record types can be constructed on-chain. Define " + typeName + " as a record.",
                oce);
    }

    private PirTerm generateForEachStmt(ForEachStmt fes, List<Statement> followingStmts, int followingIndex) {
        if (containsReturn(fes.getBody())) {
            throw enrichedError(
                    "'return' is not supported inside for-each loop body",
                    "Use 'break' to exit the loop early, then return after the loop. "
                            + "Or use the accumulator pattern: result = value; (continue iterating) "
                            + "then return result; after the loop.",
                    fes);
        }
        var iterableExpr = generateExpression(fes.getIterable());
        var itemName = fes.getVariable().getVariables().get(0).getNameAsString();

        // Infer element type from the iterable expression
        PirType elemType = new PirType.DataType(); // default
        var iterableJavaExpr = fes.getIterable();
        var iterableType = resolveExpressionType(iterableJavaExpr);
        if (iterableType instanceof PirType.MapType mt) {
            // For-each on map: scope is already a pair list (UnMapData applied at extraction/cast time)
            elemType = new PirType.PairType(mt.keyType(), mt.valueType());
        } else if (iterableType instanceof PirType.ListType lt) {
            elemType = lt.elemType();
        }

        var desugarer = loopDesugarer;
        boolean hasBreak = containsBreak(fes.getBody());

        // Detect all accumulator assignments in the loop body
        var accumulators = detectForEachAccumulators(fes.getBody());

        if (accumulators.size() == 1) {
            // --- Single-accumulator path ---
            // Snapshot pre-loop variables for re-binding after the loop (fixes LetRec scope corruption)
            var preLoopVars = symbolTable.allVisibleVariables();

            var accName = accumulators.get(0);
            var accType = symbolTable.lookup(accName).orElse(new PirType.BoolType());
            var accInit = new PirTerm.Var(accName, accType);

            PirTerm foldResult;
            if (hasBreak) {
                // Break-aware fold: body controls recursion
                final PirType finalElemType = elemType;
                foldResult = desugarer.desugarForEachWithBreak(
                        iterableExpr, itemName, accName, accInit, accType,
                        (continueFn, accVar) -> {
                            symbolTable.pushScope();
                            symbolTable.define(itemName, finalElemType);
                            symbolTable.define(accName, accType);

                            // Track auto-unwrapped loop var to prevent double-unwrap in .hash()/.name() etc.
                            var prevHofUnwrapped = hofUnwrappedVars;
                            if (needsForEachUnwrapTracking(finalElemType)) {
                                var unwrapped = new LinkedHashSet<>(hofUnwrappedVars);
                                unwrapped.add(itemName);
                                hofUnwrappedVars = unwrapped;
                            }

                            String prevAcc = forEachAccumulatorVar;
                            Function<PirTerm, PirTerm> prevBreak = breakContinueFn;
                            forEachAccumulatorVar = accName;
                            breakContinueFn = continueFn;

                            var bodyTerm = withLoopBodyFlag(() ->
                                    generateBreakAwareBody(fes.getBody(), accName, accType, continueFn));

                            forEachAccumulatorVar = prevAcc;
                            breakContinueFn = prevBreak;
                            hofUnwrappedVars = prevHofUnwrapped;
                            symbolTable.popScope();
                            return bodyTerm;
                        }, finalElemType);
            } else {
                // Normal fold: no break
                symbolTable.pushScope();
                symbolTable.define(itemName, elemType);
                symbolTable.define(accName, accType);

                // Track auto-unwrapped loop var to prevent double-unwrap in .hash()/.name() etc.
                var prevHofUnwrapped = hofUnwrappedVars;
                if (needsForEachUnwrapTracking(elemType)) {
                    var unwrapped = new LinkedHashSet<>(hofUnwrappedVars);
                    unwrapped.add(itemName);
                    hofUnwrappedVars = unwrapped;
                }

                String prev = forEachAccumulatorVar;
                forEachAccumulatorVar = accName;
                var bodyTerm = withLoopBodyFlag(() -> generateSingleAccBody(fes.getBody(), accName, accType));
                forEachAccumulatorVar = prev;

                hofUnwrappedVars = prevHofUnwrapped;
                symbolTable.popScope();

                foldResult = desugarer.desugarForEach(
                        iterableExpr, itemName, accName, accInit, accType, bodyTerm, elemType);
            }

            // Rebind accumulator with fold result for following statements
            if (followingIndex + 1 < followingStmts.size()) {
                symbolTable.define(accName, accType);
                var rest = generateStatements(followingStmts, followingIndex + 1);
                // Re-bind pre-loop variables to fix scope corruption from LetRec boundary
                rest = rebindPreLoopVars(rest, preLoopVars, Set.of(accName));
                return new PirTerm.Let(accName, foldResult, rest);
            }
            return foldResult;

        } else if (accumulators.size() > 1) {
            // --- Multi-accumulator path: pack into a Data list tuple ---
            // Snapshot pre-loop variables for re-binding after the loop (fixes LetRec scope corruption)
            var preLoopVars = symbolTable.allVisibleVariables();

            var accTypes = accumulators.stream()
                    .map(n -> symbolTable.lookup(n).orElse(new PirType.DataType()))
                    .toList();
            var accInit = packAccumulators(accumulators, accTypes);
            var tupleAccName = "__acc_tuple";
            var tupleAccType = new PirType.ListType(new PirType.DataType()); // Data list

            PirTerm foldResult;
            if (hasBreak) {
                final PirType finalElemType = elemType;
                final List<String> accNames = accumulators;
                final List<PirType> accTypesFinal = accTypes;
                foldResult = desugarer.desugarForEachWithBreak(
                        iterableExpr, itemName, tupleAccName, accInit, tupleAccType,
                        (continueFn, tupleVar) -> {
                            symbolTable.pushScope();
                            symbolTable.define(itemName, finalElemType);

                            // Track auto-unwrapped loop var to prevent double-unwrap
                            var prevHofUnwrapped = hofUnwrappedVars;
                            if (needsForEachUnwrapTracking(finalElemType)) {
                                var unwrapped = new LinkedHashSet<>(hofUnwrappedVars);
                                unwrapped.add(itemName);
                                hofUnwrappedVars = unwrapped;
                            }

                            var prevMultiAcc = multiAccVars;
                            multiAccVars = new LinkedHashSet<>(accNames);

                            var bodyTerm = unpackAccumulators(tupleVar, accNames, accTypesFinal,
                                    withLoopBodyFlag(() ->
                                            generateMultiAccBreakAwareBody(fes.getBody(), accNames, accTypesFinal, continueFn)));

                            multiAccVars = prevMultiAcc;
                            hofUnwrappedVars = prevHofUnwrapped;
                            symbolTable.popScope();
                            return bodyTerm;
                        }, finalElemType);
            } else {
                symbolTable.pushScope();
                symbolTable.define(itemName, elemType);

                // Track auto-unwrapped loop var to prevent double-unwrap
                var prevHofUnwrapped = hofUnwrappedVars;
                if (needsForEachUnwrapTracking(elemType)) {
                    var unwrapped = new LinkedHashSet<>(hofUnwrappedVars);
                    unwrapped.add(itemName);
                    hofUnwrappedVars = unwrapped;
                }

                var prevMultiAcc = multiAccVars;
                multiAccVars = new LinkedHashSet<>(accumulators);

                var tupleVar = new PirTerm.Var(tupleAccName, tupleAccType);
                var bodyTerm = unpackAccumulators(tupleVar, accumulators, accTypes,
                        withLoopBodyFlag(() -> generateMultiAccBody(fes.getBody(), accumulators, accTypes)));

                multiAccVars = prevMultiAcc;
                hofUnwrappedVars = prevHofUnwrapped;
                symbolTable.popScope();

                foldResult = desugarer.desugarForEach(
                        iterableExpr, itemName, tupleAccName, accInit, tupleAccType, bodyTerm, elemType);
            }

            // After loop: unpack final tuple into individual vars for following statements
            if (followingIndex + 1 < followingStmts.size()) {
                for (int i = 0; i < accumulators.size(); i++) {
                    symbolTable.define(accumulators.get(i), accTypes.get(i));
                }
                var rest = generateStatements(followingStmts, followingIndex + 1);
                // Re-bind pre-loop variables to fix scope corruption from LetRec boundary
                rest = rebindPreLoopVars(rest, preLoopVars, new LinkedHashSet<>(accumulators));
                return unpackAccumulators(foldResult, accumulators, accTypes, rest);
            }
            return foldResult;

        } else {
            // --- Unit-accumulator fallback: for-each with no accumulator ---
            symbolTable.pushScope();
            symbolTable.define(itemName, elemType);

            // Track auto-unwrapped loop var to prevent double-unwrap
            var prevHofUnwrapped = hofUnwrappedVars;
            if (needsForEachUnwrapTracking(elemType)) {
                var unwrapped = new LinkedHashSet<>(hofUnwrappedVars);
                unwrapped.add(itemName);
                hofUnwrappedVars = unwrapped;
            }

            var bodyTerm = withLoopBodyFlag(() -> generateStatement(fes.getBody()));
            hofUnwrappedVars = prevHofUnwrapped;
            symbolTable.popScope();

            var forEachResult = desugarer.desugarForEach(
                    iterableExpr, itemName, "acc__forEach",
                    new PirTerm.Const(Constant.unit()), new PirType.UnitType(),
                    bodyTerm, elemType);

            if (followingIndex + 1 < followingStmts.size()) {
                var rest = generateStatements(followingStmts, followingIndex + 1);
                return new PirTerm.Let("_forEach", forEachResult, rest);
            }
            return forEachResult;
        }
    }

    /**
     * Generate PIR for a for-each loop body that contains break.
     * The body decides whether to recurse (continue) or return the accumulator (break).
     *
     * Pattern: if (cond) { acc = val; break; } → IfThenElse(cond, val, continueFn(acc))
     */
    private PirTerm generateBreakAwareBody(Statement bodyStmt, String accName,
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
            // End of body without break: continue with current acc
            return continueFn.apply(new PirTerm.Var(accName, accType));
        }

        var stmt = stmts.get(index);

        if (stmt instanceof BreakStmt) {
            // break; → return current accumulator (no recursion)
            return new PirTerm.Var(accName, accType);
        }

        if (stmt instanceof ExpressionStmt es) {
            // Accumulator assignment: acc = val;
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && ne.getNameAsString().equals(accName)) {
                var value = generateExpression(ae.getValue());
                // Check if next statement is break
                if (index + 1 < stmts.size() && stmts.get(index + 1) instanceof BreakStmt) {
                    // acc = val; break; → return val (no recursion)
                    return value;
                }
                // acc = val; ... more stmts → shadow acc with new value, continue
                var rest = generateBreakAwareStatements(stmts, index + 1, accName, accType, continueFn);
                return new PirTerm.Let(accName, value, rest);
            }

            // Variable declaration
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                var name = decl.getNameAsString();
                var initExpr = decl.getInitializer().orElseThrow(
                        () -> new CompilerException("Variable must be initialized: " + name
                                + ". Hint: On-chain variables need initial values, e.g. var " + name + " = BigInteger.ZERO;"));
                var value = generateExpression(initExpr);
                var pirType = inferType(decl.getType(), value, initExpr);
                symbolTable.define(name, pirType);
                var rest = generateBreakAwareStatements(stmts, index + 1, accName, accType, continueFn);
                return new PirTerm.Let(name, value, rest);
            }

            // Other expression statement
            var expr = generateExpression(es.getExpression());
            var rest = generateBreakAwareStatements(stmts, index + 1, accName, accType, continueFn);
            return new PirTerm.Let("_", expr, rest);
        }

        if (stmt instanceof IfStmt is) {
            return generateBreakAwareIf(is, stmts, index, accName, accType, continueFn);
        }

        throw enrichedError("Unsupported statement in break-aware loop body: " + stmt.getClass().getSimpleName(),
                "Inside loops with break, only variable declarations, assignments, if/else, and break are supported.",
                stmt);
    }

    private PirTerm generateBreakAwareIf(IfStmt is, List<Statement> followingStmts, int followingIndex,
                                          String accName, PirType accType,
                                          Function<PirTerm, PirTerm> continueFn) {
        var cond = generateExpression(is.getCondition());
        boolean thenBreaks = containsBreak(is.getThenStmt());
        boolean elseBreaks = is.getElseStmt().map(this::containsBreak).orElse(false);

        PirTerm thenTerm;
        PirTerm elseTerm;

        if (thenBreaks && elseBreaks) {
            // Both branches break: neither recurses
            thenTerm = generateBreakAwareBody(is.getThenStmt(), accName, accType, _ -> new PirTerm.Var(accName, accType));
            elseTerm = generateBreakAwareBody(is.getElseStmt().get(), accName, accType, _ -> new PirTerm.Var(accName, accType));
        } else if (thenBreaks) {
            // Then breaks, else continues (or falls through to remaining stmts)
            thenTerm = generateBreakAwareBody(is.getThenStmt(), accName, accType, _ -> new PirTerm.Var(accName, accType));
            if (is.getElseStmt().isPresent()) {
                elseTerm = generateBreakAwareBody(is.getElseStmt().get(), accName, accType, continueFn);
            } else {
                // No else: fall through to remaining statements
                elseTerm = generateBreakAwareStatements(followingStmts, followingIndex + 1, accName, accType, continueFn);
                // Remaining stmts already handled in else branch, so return directly
                return new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
            }
        } else if (elseBreaks) {
            // Else breaks, then continues
            thenTerm = generateBreakAwareBody(is.getThenStmt(), accName, accType, continueFn);
            elseTerm = generateBreakAwareBody(is.getElseStmt().get(), accName, accType, _ -> new PirTerm.Var(accName, accType));
        } else {
            // Neither breaks: use single-acc body to properly thread accumulator
            thenTerm = generateSingleAccBody(is.getThenStmt(), accName, accType);
            elseTerm = is.getElseStmt()
                    .map(e -> generateSingleAccBody(e, accName, accType))
                    .orElse(new PirTerm.Var(accName, accType));
        }

        var ifExpr = new PirTerm.IfThenElse(cond, thenTerm, elseTerm);

        // If there are following statements and neither branch fully terminated
        if (followingIndex + 1 < followingStmts.size()) {
            if (thenBreaks && !elseBreaks && !is.getElseStmt().isPresent()) {
                // Already handled: else path includes remaining stmts
                return ifExpr;
            }
            var rest = generateBreakAwareStatements(followingStmts, followingIndex + 1, accName, accType, continueFn);
            if (!thenBreaks && !elseBreaks) {
                // Neither breaks: rebind acc with if result so following stmts see updated value
                return new PirTerm.Let(accName, ifExpr, rest);
            }
            return new PirTerm.Let("_if", ifExpr, rest);
        }

        // Last statement: if neither breaks, wrap with continue
        if (!thenBreaks && !elseBreaks) {
            return continueFn.apply(ifExpr);
        }

        return ifExpr;
    }

    private boolean containsBreak(Statement stmt) {
        if (stmt instanceof BreakStmt) return true;
        // Do not walk into nested loops — a break inside a nested loop belongs to that loop
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

    private boolean containsReturn(Statement stmt) {
        if (stmt instanceof ReturnStmt) return true;
        // Do not walk into nested loops — a return inside a nested loop belongs to that loop
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

    /**
     * Check if a for-each element type needs unwrap tracking (to prevent double-unwrap
     * in .hash()/.name() etc. when auto-unwrap is applied in the desugarer).
     */
    private boolean needsForEachUnwrapTracking(PirType elemType) {
        return elemType instanceof PirType.ByteStringType
                || elemType instanceof PirType.IntegerType
                || elemType instanceof PirType.BoolType
                || elemType instanceof PirType.StringType;
    }

    /**
     * Detect ALL accumulator assignments in a for-each loop body.
     * Returns the list of pre-loop variable names that are assigned in the body.
     */
    private List<String> detectForEachAccumulators(Statement bodyStmt) {
        List<Statement> stmts;
        if (bodyStmt instanceof BlockStmt bs) stmts = bs.getStatements();
        else stmts = List.of(bodyStmt);
        if (stmts.isEmpty()) return List.of();
        var accNames = new LinkedHashSet<String>();
        collectAccumulatorAssignments(stmts, accNames);
        return new ArrayList<>(accNames);
    }

    private void collectAccumulatorAssignments(List<Statement> stmts, LinkedHashSet<String> accNames) {
        for (var stmt : stmts) {
            if (stmt instanceof ExpressionStmt es && es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var name = ne.getNameAsString();
                if (symbolTable.lookup(name).isPresent()) accNames.add(name);
            }
            if (stmt instanceof IfStmt is) {
                collectAccumulatorAssignments(blockStmts(is.getThenStmt()), accNames);
                is.getElseStmt().ifPresent(e -> collectAccumulatorAssignments(blockStmts(e), accNames));
            }
            if (stmt instanceof BlockStmt bs) {
                collectAccumulatorAssignments(bs.getStatements(), accNames);
            }
            // Recurse into nested loop bodies to find shared accumulators
            if (stmt instanceof WhileStmt ws) {
                collectAccumulatorAssignments(blockStmts(ws.getBody()), accNames);
            }
            if (stmt instanceof ForEachStmt fes) {
                collectAccumulatorAssignments(blockStmts(fes.getBody()), accNames);
            }
        }
    }

    private static List<Statement> blockStmts(Statement stmt) {
        if (stmt instanceof BlockStmt bs) return bs.getStatements();
        return List.of(stmt);
    }

    /** Compile single-acc loop body: process stmts, acc assignments become Let shadows, return acc at end */
    private PirTerm generateSingleAccBody(Statement bodyStmt, String accName, PirType accType) {
        var stmts = blockStmts(bodyStmt);
        return generateSingleAccStatements(stmts, 0, accName, accType);
    }

    private PirTerm generateSingleAccStatements(List<Statement> stmts, int index,
                                                  String accName, PirType accType) {
        if (index >= stmts.size()) {
            return new PirTerm.Var(accName, accType); // return current acc at body end
        }
        var stmt = stmts.get(index);

        if (stmt instanceof ExpressionStmt es) {
            // Accumulator assignment: acc = val;
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && ne.getNameAsString().equals(accName)) {
                var value = generateExpression(ae.getValue());
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let(accName, value, rest);
            }
            // Non-accumulator assignment: shadow with Let binding
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var value = generateExpression(ae.getValue());
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            // Variable declaration
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                var name = decl.getNameAsString();
                var initExpr = decl.getInitializer().orElseThrow(
                        () -> new CompilerException("Variable must be initialized: " + name
                                + ". Hint: On-chain variables need initial values, e.g. var " + name + " = BigInteger.ZERO;"));
                var value = generateExpression(initExpr);
                var pirType = inferType(decl.getType(), value, initExpr);
                symbolTable.define(name, pirType);
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let(name, value, rest);
            }
            // Other expression: evaluate, discard, continue
            var expr = generateExpression(es.getExpression());
            var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
            return new PirTerm.Let("_", expr, rest);
        }
        if (stmt instanceof IfStmt is) {
            var cond = generateExpression(is.getCondition());
            var thenTerm = generateSingleAccBody(is.getThenStmt(), accName, accType);
            var elseTerm = is.getElseStmt()
                    .map(e -> generateSingleAccBody(e, accName, accType))
                    .orElse(new PirTerm.Var(accName, accType));
            var ifExpr = new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
            if (index + 1 < stmts.size()) {
                // After if, rebind acc with if result, then continue
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let(accName, ifExpr, rest);
            }
            return ifExpr;
        }
        if (stmt instanceof WhileStmt ws) {
            // Nested while inside single-acc body
            var innerAccs = detectForEachAccumulators(ws.getBody());
            var innerResult = generateWhileStmt(ws, List.of(ws), 0);
            if (innerAccs.size() == 1) {
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let(innerAccs.get(0), innerResult, rest);
            } else if (innerAccs.size() > 1) {
                var innerTypes = innerAccs.stream()
                        .map(n -> symbolTable.lookup(n).orElse(new PirType.DataType()))
                        .toList();
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return unpackAccumulators(innerResult, innerAccs, innerTypes, rest);
            } else {
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let("_nested", innerResult, rest);
            }
        }
        if (stmt instanceof ForEachStmt fes) {
            // Nested for-each inside single-acc body
            var innerAccs = detectForEachAccumulators(fes.getBody());
            var innerResult = generateForEachStmt(fes, List.of(fes), 0);
            if (innerAccs.size() == 1) {
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let(innerAccs.get(0), innerResult, rest);
            } else if (innerAccs.size() > 1) {
                var innerTypes = innerAccs.stream()
                        .map(n -> symbolTable.lookup(n).orElse(new PirType.DataType()))
                        .toList();
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return unpackAccumulators(innerResult, innerAccs, innerTypes, rest);
            } else {
                var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
                return new PirTerm.Let("_nested", innerResult, rest);
            }
        }
        // Fallback: use generateStatement for other statements
        var term = generateStatement(stmt);
        if (index + 1 < stmts.size()) {
            var rest = generateSingleAccStatements(stmts, index + 1, accName, accType);
            return new PirTerm.Let("_", term, rest);
        }
        return new PirTerm.Var(accName, accType);
    }

    /**
     * Refine accumulator types for multi-accumulator while loops.
     * In UPLC, List(Data) and Data are distinct types. When an accumulator holds a list value
     * (e.g., a list cursor advanced via tailList), it must be typed as ListType so that
     * wrapEncode/wrapDecode apply ListData/UnListData during pack/unpack.
     * Detects list/map types by scanning assignments in the while body for known patterns,
     * and also checks initial declarations for unMapData to distinguish MapType cursors.
     */
    private List<PirType> refineAccumulatorTypes(WhileStmt ws, List<String> accNames, List<PirType> initialTypes,
                                                  List<Statement> precedingStmts) {
        // Collect names declared/known as pair-list types from context:
        // method parameters declared as MapData, and local variables initialized from mkNilPairData/unMapData
        var pairListNames = collectPairListNames(ws, precedingStmts);

        var result = new ArrayList<>(initialTypes);
        // Pass 1: Detect MapType from direct evidence
        for (int i = 0; i < accNames.size(); i++) {
            if (!(result.get(i) instanceof PirType.DataType)) continue;
            String accName = accNames.get(i);
            // Check if accumulator is initialized from pair-list source BEFORE the loop
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
                    // Cursor initialized from unMapData or MapData-typed parameter → MapType
                    result.set(i, new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                } else if (hasMkCons && isInitializedFromPairListSource(accName, precedingStmts, pairListNames)) {
                    // mkCons building a pair list (initialized from mkNilPairData/MapData param) → MapType
                    result.set(i, new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                } else if (hasMkCons && bodyUsesMkPairDataForCons(ws.getBody(), accName)) {
                    // mkCons with mkPairData items in loop body → building a pair list → MapType
                    result.set(i, new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                } else if (hasTailList && !hasMkNilData && !hasMkCons
                        && bodyUsesPairOpsOnCursor(ws.getBody(), accName)) {
                    // tailList cursor where body extracts pair elements via fstPair/sndPair → MapType
                    result.set(i, new PirType.MapType(new PirType.DataType(), new PirType.DataType()));
                } else {
                    result.set(i, new PirType.ListType(new PirType.DataType()));
                }
            }
        }
        // Pass 2: Propagate MapType to sibling cursors when one accumulator is proven MapType.
        // Only promote tailList-only cursors (not mkCons builders, to avoid mistyping list-data builders).
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
                    // Only promote pure cursors (tailList without mkCons) to MapType
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
     * Sources: method parameters declared as MapData, and local variables initialized
     * from mkNilPairData() or unMapData().
     */
    private Set<String> collectPairListNames(WhileStmt ws, List<Statement> precedingStmts) {
        var names = new HashSet<String>();
        // 1. Check method parameters for MapData type declarations
        var methodDecl = ws.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class);
        if (methodDecl.isPresent()) {
            for (var param : methodDecl.get().getParameters()) {
                if (isDeclaredAsMapData(param.getType())) {
                    names.add(param.getNameAsString());
                }
            }
        }
        // 2. Check preceding variable declarations for pair-list initializers
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
                        // Alias: var current = outerPairs (where outerPairs is a pair list)
                        names.add(decl.getNameAsString());
                    }
                }
            }
        }
        return names;
    }

    /** Check if a Java type declaration is MapData (PlutusData.MapData or just MapData). */
    private static boolean isDeclaredAsMapData(com.github.javaparser.ast.type.Type type) {
        if (type instanceof com.github.javaparser.ast.type.ClassOrInterfaceType ct) {
            var name = ct.getNameAsString();
            return name.equals("MapData");
        }
        return false;
    }

    /** Check if an expression is a direct pair-list source: mkNilPairData() or unMapData(). */
    private static boolean exprIsPairListSource(Expression expr) {
        if (expr instanceof MethodCallExpr mce) {
            var name = mce.getNameAsString();
            return name.equals("mkNilPairData") || name.equals("unMapData");
        }
        return false;
    }

    /**
     * Check if accumulator is initialized from a pair-list source.
     * Uses both preceding variable declarations and the collected pairListNames set
     * (which includes MapData-typed method parameters).
     */
    private boolean isInitializedFromPairListSource(String varName, List<Statement> precedingStmts,
                                                     Set<String> pairListNames) {
        // First check existing logic (declarations in preceding stmts)
        if (isInitializedFromPairList(varName, precedingStmts)) return true;
        if (isInitializedFromMapType(varName, precedingStmts)) return true;
        // Check if initialized from a known pair-list name (e.g., MapData parameter)
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
        // Check if the varName itself is a pair-list name (e.g., directly a MapData parameter)
        return pairListNames.contains(varName);
    }

    /**
     * Check if a variable's initial declaration is assigned from a MapType source.
     * This traces through variable aliases (e.g., current = pairs where pairs = unMapData(x)).
     */
    private boolean isInitializedFromMapType(String varName, List<Statement> precedingStmts) {
        // Find the declaration of varName in the preceding statements
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
     * When an accumulator is initialized with mkNilPairData() and built with mkCons in the loop body,
     * it should be typed as MapType (list (pair data data)), not ListType (list data).
     */
    private boolean isInitializedFromPairList(String varName, List<Statement> precedingStmts) {
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
     * Check if an expression resolves to a pair list type — mkNilPairData(), unMapData(...), or a
     * variable reference to one.
     */
    private boolean exprResolvesToPairList(Expression expr, List<Statement> precedingStmts) {
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
     * Check if an expression resolves to a MapType value — either a direct unMapData call,
     * or a variable reference to one.
     */
    private boolean exprResolvesToMapType(Expression expr, List<Statement> precedingStmts) {
        if (expr instanceof MethodCallExpr mce && mce.getNameAsString().equals("unMapData")) {
            return true;
        }
        // Trace through variable aliases: if expr is a variable reference, check its declaration
        if (expr instanceof NameExpr ne) {
            return isInitializedFromMapType(ne.getNameAsString(), precedingStmts);
        }
        return false;
    }

    /** Check if any assignment to varName in the statement tree uses a specific Builtins method. */
    private static boolean hasBuiltinAssignment(Statement body, String varName, String builtinMethod) {
        var stmts = blockStmts(body);
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

    /**
     * Check if mkCons assignments to accName in the loop body use mkPairData items.
     * Pattern: accName = mkCons(expr, accName) where expr traces to mkPairData.
     * This indicates the accumulator is building a pair list (MapType).
     */
    private static boolean bodyUsesMkPairDataForCons(Statement body, String accName) {
        return bodyContainsMethodCall(body, "mkPairData");
    }

    /**
     * Check if a tailList cursor's elements are treated as pairs in the loop body.
     * Specifically checks for fstPair/sndPair applied to the direct headList result of the cursor,
     * NOT just any fstPair/sndPair anywhere in the body.
     * Pattern: var x = headList(cursor); fstPair(x) / sndPair(x)
     */
    private static boolean bodyUsesPairOpsOnCursor(Statement body, String cursorName) {
        // Find the variable that holds headList(cursorName)
        String headVar = findHeadListVar(body, cursorName);
        if (headVar == null) return false;
        // Check if fstPair or sndPair is called with that variable as argument
        return bodyCallsPairOpOn(body, headVar);
    }

    /** Find the variable name assigned from headList(cursorName), e.g. "var pair = Builtins.headList(cursor)". */
    private static String findHeadListVar(Statement body, String cursorName) {
        var stmts = blockStmts(body);
        for (var stmt : stmts) {
            var found = findHeadListVarInStmt(stmt, cursorName);
            if (found != null) return found;
        }
        return null;
    }

    private static String findHeadListVarInStmt(Statement stmt, String cursorName) {
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
        // Recurse into if-else blocks
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
    private static boolean bodyCallsPairOpOn(Statement body, String varName) {
        var stmts = blockStmts(body);
        for (var stmt : stmts) {
            if (containsPairOpOnVar(stmt, varName)) return true;
        }
        return false;
    }

    private static boolean containsPairOpOnVar(com.github.javaparser.ast.Node node, String varName) {
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
    private static boolean bodyContainsMethodCall(Statement body, String methodName) {
        var stmts = blockStmts(body);
        for (var stmt : stmts) {
            if (containsMethodCallExpr(stmt, methodName)) return true;
        }
        return false;
    }

    /** Recursively check if a node tree contains a MethodCallExpr with the given name. */
    private static boolean containsMethodCallExpr(com.github.javaparser.ast.Node node, String methodName) {
        if (node instanceof MethodCallExpr mce && mce.getNameAsString().equals(methodName)) {
            return true;
        }
        for (var child : node.getChildNodes()) {
            if (containsMethodCallExpr(child, methodName)) return true;
        }
        return false;
    }

    /**
     * Find accumulator name from break patterns:
     *   1. Standalone assignment before if-with-break: acc = val; if (cond) { break; }
     *   2. Assignment inside if-with-break: if (cond) { acc = val; break; }
     */
    private String findAccumulatorInBreakPattern(List<Statement> stmts) {
        // Check for standalone assignment to a pre-loop variable (covers pattern: acc = expr; if (...) { break; })
        for (var stmt : stmts) {
            if (stmt instanceof ExpressionStmt es
                    && es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var name = ne.getNameAsString();
                if (symbolTable.lookup(name).isPresent()) return name;
            }
        }
        // Check for assignment inside if-blocks with break
        for (var stmt : stmts) {
            if (stmt instanceof IfStmt is) {
                var accName = findAccumulatorInBlock(is.getThenStmt());
                if (accName != null) return accName;
                if (is.getElseStmt().isPresent()) {
                    accName = findAccumulatorInBlock(is.getElseStmt().get());
                    if (accName != null) return accName;
                }
            }
        }
        return null;
    }

    private String findAccumulatorInBlock(Statement stmt) {
        List<Statement> inner;
        if (stmt instanceof BlockStmt bs) inner = bs.getStatements();
        else inner = List.of(stmt);
        // Look for pattern: acc = val; break;
        for (int i = 0; i < inner.size() - 1; i++) {
            if (inner.get(i) instanceof ExpressionStmt es
                    && es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && inner.get(i + 1) instanceof BreakStmt) {
                var name = ne.getNameAsString();
                if (symbolTable.lookup(name).isPresent()) return name;
            }
        }
        return null;
    }

    // --- Multi-accumulator helpers ---

    /** Pack accumulator values into a Data list: MkCons(encode(v1), MkCons(encode(v2), ...MkNilData)) */
    private PirTerm packAccumulators(List<String> names, List<PirType> types) {
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

    /** Wrap body with Let bindings that unpack each accumulator from a Data list tuple */
    private PirTerm unpackAccumulators(PirTerm tuple, List<String> names, List<PirType> types, PirTerm body) {
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

    /** Compile multi-acc loop body: process stmts, acc assignments become Let shadows, pack at end */
    private PirTerm generateMultiAccBody(Statement bodyStmt, List<String> accNames, List<PirType> accTypes) {
        var stmts = blockStmts(bodyStmt);
        return generateMultiAccStatements(stmts, 0, accNames, accTypes);
    }

    private PirTerm generateMultiAccStatements(List<Statement> stmts, int index,
                                                List<String> accNames, List<PirType> accTypes) {
        if (index >= stmts.size()) {
            return packAccumulators(accNames, accTypes); // repack at body end
        }
        var stmt = stmts.get(index);

        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && accNames.contains(ne.getNameAsString())) {
                var value = generateExpression(ae.getValue());
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            // Non-accumulator assignment: shadow with Let binding
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var value = generateExpression(ae.getValue());
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                var name = decl.getNameAsString();
                var initExpr = decl.getInitializer().orElseThrow(
                        () -> new CompilerException("Variable must be initialized: " + name
                                + ". Hint: On-chain variables need initial values, e.g. var " + name + " = BigInteger.ZERO;"));
                var value = generateExpression(initExpr);
                var pirType = inferType(decl.getType(), value, initExpr);
                symbolTable.define(name, pirType);
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return new PirTerm.Let(name, value, rest);
            }
            // Other expression: evaluate, discard, continue
            var expr = generateExpression(es.getExpression());
            var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
            return new PirTerm.Let("_", expr, rest);
        }
        if (stmt instanceof IfStmt is) {
            var cond = generateExpression(is.getCondition());
            var thenTerm = generateMultiAccBody(is.getThenStmt(), accNames, accTypes);
            var elseTerm = is.getElseStmt()
                    .map(e -> generateMultiAccBody(e, accNames, accTypes))
                    .orElse(packAccumulators(accNames, accTypes));
            var ifExpr = new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
            if (index + 1 < stmts.size()) {
                // After if, unpack the result back into acc vars, then continue
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return unpackAccumulators(ifExpr, accNames, accTypes.stream().toList(), rest);
            }
            return ifExpr;
        }
        if (stmt instanceof WhileStmt ws) {
            // Nested while inside multi-acc body: generate the inner loop standalone,
            // then rebind any accumulators it modified, and continue multi-acc processing.
            var innerAccs = detectForEachAccumulators(ws.getBody());
            var innerResult = generateWhileStmt(ws, List.of(ws), 0);

            if (innerAccs.size() == 1) {
                // Single-acc inner loop returns the scalar accumulator value
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return new PirTerm.Let(innerAccs.get(0), innerResult, rest);
            } else if (innerAccs.size() > 1) {
                // Multi-acc inner loop returns a tuple — unpack into individual vars
                var innerTypes = innerAccs.stream()
                        .map(n -> symbolTable.lookup(n).orElse(new PirType.DataType()))
                        .toList();
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return unpackAccumulators(innerResult, innerAccs, innerTypes, rest);
            } else {
                // No-acc inner loop (side-effect only)
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return new PirTerm.Let("_nested", innerResult, rest);
            }
        }
        if (stmt instanceof ForEachStmt fes) {
            var innerAccs = detectForEachAccumulators(fes.getBody());
            var innerResult = generateForEachStmt(fes, List.of(fes), 0);

            if (innerAccs.size() == 1) {
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return new PirTerm.Let(innerAccs.get(0), innerResult, rest);
            } else if (innerAccs.size() > 1) {
                var innerTypes = innerAccs.stream()
                        .map(n -> symbolTable.lookup(n).orElse(new PirType.DataType()))
                        .toList();
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return unpackAccumulators(innerResult, innerAccs, innerTypes, rest);
            } else {
                var rest = generateMultiAccStatements(stmts, index + 1, accNames, accTypes);
                return new PirTerm.Let("_nested", innerResult, rest);
            }
        }
        throw enrichedError("Unsupported in multi-acc loop body: " + stmt.getClass().getSimpleName(),
                "Inside multi-accumulator loops, only variable declarations, assignments, and if/else are supported.",
                stmt);
    }

    /** Compile multi-acc loop body with break support */
    private PirTerm generateMultiAccBreakAwareBody(Statement bodyStmt, List<String> accNames,
                                                     List<PirType> accTypes,
                                                     Function<PirTerm, PirTerm> continueFn) {
        var stmts = blockStmts(bodyStmt);
        return generateMultiAccBreakAwareStmts(stmts, 0, accNames, accTypes, continueFn);
    }

    private PirTerm generateMultiAccBreakAwareStmts(List<Statement> stmts, int index,
                                                      List<String> accNames, List<PirType> accTypes,
                                                      Function<PirTerm, PirTerm> continueFn) {
        if (index >= stmts.size()) {
            // End of body without break: continue with repacked tuple
            return continueFn.apply(packAccumulators(accNames, accTypes));
        }

        var stmt = stmts.get(index);

        if (stmt instanceof BreakStmt) {
            // break → return packed tuple (no recursion)
            return packAccumulators(accNames, accTypes);
        }

        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne
                    && accNames.contains(ne.getNameAsString())) {
                var value = generateExpression(ae.getValue());
                // Check if next statement is break
                if (index + 1 < stmts.size() && stmts.get(index + 1) instanceof BreakStmt) {
                    // acc = val; break; → shadow acc, pack and return
                    var rest = packAccumulators(accNames, accTypes);
                    return new PirTerm.Let(ne.getNameAsString(), value, rest);
                }
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            // Non-accumulator assignment: shadow with Let binding
            if (es.getExpression() instanceof AssignExpr ae
                    && ae.getTarget() instanceof NameExpr ne) {
                var value = generateExpression(ae.getValue());
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return new PirTerm.Let(ne.getNameAsString(), value, rest);
            }
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                var name = decl.getNameAsString();
                var initExpr = decl.getInitializer().orElseThrow(
                        () -> new CompilerException("Variable must be initialized: " + name
                                + ". Hint: On-chain variables need initial values, e.g. var " + name + " = BigInteger.ZERO;"));
                var value = generateExpression(initExpr);
                var pirType = inferType(decl.getType(), value, initExpr);
                symbolTable.define(name, pirType);
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return new PirTerm.Let(name, value, rest);
            }
            var expr = generateExpression(es.getExpression());
            var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
            return new PirTerm.Let("_", expr, rest);
        }

        if (stmt instanceof IfStmt is) {
            var cond = generateExpression(is.getCondition());
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
                // No else: fall through
                if (thenBreaks) {
                    elseTerm = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                    return new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
                }
                elseTerm = packAccumulators(accNames, accTypes); // placeholder
            }

            var ifExpr = new PirTerm.IfThenElse(cond, thenTerm, elseTerm);
            if (index + 1 < stmts.size() && !(thenBreaks && !is.getElseStmt().isPresent())) {
                // After if: unpack and continue
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return unpackAccumulators(ifExpr, accNames, accTypes, rest);
            }
            return ifExpr;
        }

        if (stmt instanceof WhileStmt ws) {
            var innerAccs = detectForEachAccumulators(ws.getBody());
            var innerResult = generateWhileStmt(ws, List.of(ws), 0);

            if (innerAccs.size() == 1) {
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return new PirTerm.Let(innerAccs.get(0), innerResult, rest);
            } else if (innerAccs.size() > 1) {
                var innerTypes = innerAccs.stream()
                        .map(n -> symbolTable.lookup(n).orElse(new PirType.DataType()))
                        .toList();
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return unpackAccumulators(innerResult, innerAccs, innerTypes, rest);
            } else {
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return new PirTerm.Let("_nested", innerResult, rest);
            }
        }
        if (stmt instanceof ForEachStmt fes) {
            var innerAccs = detectForEachAccumulators(fes.getBody());
            var innerResult = generateForEachStmt(fes, List.of(fes), 0);

            if (innerAccs.size() == 1) {
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return new PirTerm.Let(innerAccs.get(0), innerResult, rest);
            } else if (innerAccs.size() > 1) {
                var innerTypes = innerAccs.stream()
                        .map(n -> symbolTable.lookup(n).orElse(new PirType.DataType()))
                        .toList();
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return unpackAccumulators(innerResult, innerAccs, innerTypes, rest);
            } else {
                var rest = generateMultiAccBreakAwareStmts(stmts, index + 1, accNames, accTypes, continueFn);
                return new PirTerm.Let("_nested", innerResult, rest);
            }
        }
        throw enrichedError("Unsupported in multi-acc break-aware body: " + stmt.getClass().getSimpleName(),
                "Inside multi-accumulator loops with break, only variable declarations, assignments, if/else, and break are supported.",
                stmt);
    }

    private PirTerm generateWhileStmt(WhileStmt ws, List<Statement> followingStmts, int followingIndex) {
        if (containsReturn(ws.getBody())) {
            throw enrichedError(
                    "'return' is not supported inside while loop body",
                    "Use 'break' to exit the loop early, then return after the loop. "
                            + "Or use the accumulator pattern: result = value; (continue iterating) "
                            + "then return result; after the loop.",
                    ws);
        }
        var desugarer = loopDesugarer;
        boolean hasBreak = containsBreak(ws.getBody());
        var accumulators = detectForEachAccumulators(ws.getBody());

        if (accumulators.size() == 1) {
            // --- Single-accumulator while loop ---
            // Snapshot pre-loop variables for re-binding after the loop (fixes LetRec scope corruption)
            var preLoopVars = symbolTable.allVisibleVariables();

            var accName = accumulators.get(0);
            var accType = symbolTable.lookup(accName).orElse(new PirType.BoolType());
            var accInit = new PirTerm.Var(accName, accType);

            PirTerm whileResult;
            if (hasBreak) {
                // Generate condition with acc in scope
                symbolTable.pushScope();
                symbolTable.define(accName, accType);
                var condition = generateExpression(ws.getCondition());
                symbolTable.popScope();

                whileResult = desugarer.desugarWhileWithAccumulatorAndBreak(
                        condition, accName, accInit, accType,
                        (continueFn, accVar) -> {
                            symbolTable.pushScope();
                            symbolTable.define(accName, accType);

                            String prevAcc = forEachAccumulatorVar;
                            Function<PirTerm, PirTerm> prevBreak = breakContinueFn;
                            forEachAccumulatorVar = accName;
                            breakContinueFn = continueFn;

                            var bodyTerm = withLoopBodyFlag(() ->
                                    generateBreakAwareBody(ws.getBody(), accName, accType, continueFn));

                            forEachAccumulatorVar = prevAcc;
                            breakContinueFn = prevBreak;
                            symbolTable.popScope();
                            return bodyTerm;
                        });
            } else {
                // No break: generate condition and body with acc in scope
                symbolTable.pushScope();
                symbolTable.define(accName, accType);

                String prev = forEachAccumulatorVar;
                forEachAccumulatorVar = accName;
                var condition = generateExpression(ws.getCondition());
                var bodyTerm = withLoopBodyFlag(() -> generateSingleAccBody(ws.getBody(), accName, accType));
                forEachAccumulatorVar = prev;

                symbolTable.popScope();

                whileResult = desugarer.desugarWhileWithAccumulator(
                        condition, bodyTerm, accName, accInit, accType);
            }

            // Rebind accumulator with while result for following statements
            if (followingIndex + 1 < followingStmts.size()) {
                symbolTable.define(accName, accType);
                var rest = generateStatements(followingStmts, followingIndex + 1);
                // Re-bind pre-loop variables to fix scope corruption from LetRec boundary
                rest = rebindPreLoopVars(rest, preLoopVars, Set.of(accName));
                return new PirTerm.Let(accName, whileResult, rest);
            }
            return whileResult;

        } else if (accumulators.size() > 1) {
            // --- Multi-accumulator while loop ---
            // Snapshot pre-loop variables for re-binding after the loop (fixes LetRec scope corruption)
            var preLoopVars = symbolTable.allVisibleVariables();

            var rawAccTypes = accumulators.stream()
                    .map(n -> symbolTable.lookup(n).orElse(new PirType.DataType()))
                    .toList();
            // Refine types: detect List(Data) and Map accumulators from assignment patterns
            var precedingStmts = followingStmts.subList(0, followingIndex);
            var accTypes = refineAccumulatorTypes(ws, accumulators, rawAccTypes, precedingStmts);
            var accInit = packAccumulators(accumulators, accTypes);
            var tupleAccName = "__acc_tuple";
            var tupleAccType = new PirType.ListType(new PirType.DataType());

            PirTerm whileResult;
            if (hasBreak) {
                // Generate condition with accumulators in scope
                symbolTable.pushScope();
                for (int i = 0; i < accumulators.size(); i++) {
                    symbolTable.define(accumulators.get(i), accTypes.get(i));
                }
                var condition = generateExpression(ws.getCondition());
                symbolTable.popScope();

                final List<String> accNames = accumulators;
                final List<PirType> accTypesFinal = accTypes;
                // Wrap condition in unpack so it can access individual acc vars from the tuple
                var condWithUnpack = unpackAccumulators(
                        new PirTerm.Var(tupleAccName, tupleAccType), accNames, accTypesFinal, condition);

                whileResult = desugarer.desugarWhileWithAccumulatorAndBreak(
                        condWithUnpack, tupleAccName, accInit, tupleAccType,
                        (continueFn, tupleVar) -> {
                            symbolTable.pushScope();

                            var prevMultiAcc = multiAccVars;
                            multiAccVars = new LinkedHashSet<>(accNames);

                            var bodyTerm = unpackAccumulators(tupleVar, accNames, accTypesFinal,
                                    withLoopBodyFlag(() ->
                                            generateMultiAccBreakAwareBody(ws.getBody(), accNames, accTypesFinal, continueFn)));

                            multiAccVars = prevMultiAcc;
                            symbolTable.popScope();
                            return bodyTerm;
                        });
            } else {
                // Generate condition with accumulators in scope
                symbolTable.pushScope();
                for (int i = 0; i < accumulators.size(); i++) {
                    symbolTable.define(accumulators.get(i), accTypes.get(i));
                }
                var condition = generateExpression(ws.getCondition());
                symbolTable.popScope();

                final List<String> accNames = accumulators;
                // Wrap condition in unpack
                var condWithUnpack = unpackAccumulators(
                        new PirTerm.Var(tupleAccName, tupleAccType), accNames, accTypes, condition);

                symbolTable.pushScope();

                var prevMultiAcc = multiAccVars;
                multiAccVars = new LinkedHashSet<>(accumulators);

                var tupleVar = new PirTerm.Var(tupleAccName, tupleAccType);
                var bodyTerm = unpackAccumulators(tupleVar, accumulators, accTypes,
                        withLoopBodyFlag(() -> generateMultiAccBody(ws.getBody(), accumulators, accTypes)));

                multiAccVars = prevMultiAcc;
                symbolTable.popScope();

                whileResult = desugarer.desugarWhileWithAccumulator(
                        condWithUnpack, bodyTerm, tupleAccName, accInit, tupleAccType);
            }

            // After loop: unpack final tuple into individual vars for following statements
            if (followingIndex + 1 < followingStmts.size()) {
                for (int i = 0; i < accumulators.size(); i++) {
                    symbolTable.define(accumulators.get(i), accTypes.get(i));
                }
                var rest = generateStatements(followingStmts, followingIndex + 1);
                // Re-bind pre-loop variables to fix scope corruption from LetRec boundary
                rest = rebindPreLoopVars(rest, preLoopVars, new LinkedHashSet<>(accumulators));
                return unpackAccumulators(whileResult, accumulators, accTypes, rest);
            }
            return whileResult;

        } else {
            // --- No accumulator: existing side-effect-only while loop ---
            var condition = generateExpression(ws.getCondition());
            var bodyTerm = withLoopBodyFlag(() -> generateStatement(ws.getBody()));

            var whileResult = desugarer.desugarWhile(condition, bodyTerm);

            if (followingIndex + 1 < followingStmts.size()) {
                var rest = generateStatements(followingStmts, followingIndex + 1);
                return new PirTerm.Let("_while", whileResult, rest);
            }
            return whileResult;
        }
    }

    private PirTerm generateSwitchExpr(SwitchExpr se) {
        var selector = generateExpression(se.getSelector());
        // Determine the sum type from the selector's type
        var selectorType = inferPirType(selector);
        // Fallback: if PIR-level inference returns DataType, try resolving from the Java AST.
        // This handles cases like txOut.datum() where field extraction PIR loses the SumType info.
        if (!(selectorType instanceof PirType.SumType) && se.getSelector() instanceof Expression selectorExpr) {
            var astType = resolveExpressionType(selectorExpr);
            if (astType instanceof PirType.SumType) {
                selectorType = astType;
            }
        }
        if (!(selectorType instanceof PirType.SumType sumType)) {
            throw enrichedError("switch expression requires a sealed interface type, got: " + selectorType,
                    "Ensure the switch variable's type is a sealed interface with record variants.", se);
        }

        var desugarer = new PatternMatchDesugarer(typeResolver);
        var matchEntries = new ArrayList<PatternMatchDesugarer.MatchEntry>();
        PirTerm defaultBranchBody = null;

        for (var entry : se.getEntries()) {
            if (entry.isDefault()) {
                // Compile the default branch body for use as catch-all for unmatched variants
                defaultBranchBody = generateSwitchEntryBody(entry);
                continue;
            }
            for (var label : entry.getLabels()) {
                if (label instanceof com.github.javaparser.ast.expr.TypePatternExpr tpe) {
                    // case TypeName varName -> body
                    var typeName = tpe.getType().asClassOrInterfaceType().getNameAsString();
                    var varName = tpe.getNameAsString();

                    // Find the constructor fields for this variant
                    var ctorOpt = sumType.constructors().stream()
                            .filter(c -> c.name().equals(typeName))
                            .findFirst();
                    if (ctorOpt.isEmpty()) {
                        String available = sumType.constructors().stream()
                                .map(PirType.Constructor::name)
                                .reduce((a, b) -> a + ", " + b).orElse("none");
                        throw enrichedError("Unknown variant in switch: " + typeName,
                                "Available variants: " + available, se);
                    }
                    var ctor = ctorOpt.get();

                    symbolTable.pushScope();
                    // Define the field bindings in scope
                    for (var field : ctor.fields()) {
                        symbolTable.define(field.name(), field.type());
                    }
                    // Define the pattern variable (e.g. 'b' in 'case Bid b')
                    // with RecordType so b.fieldName() redirects to the bound field
                    var varRecordType = new PirType.RecordType(typeName, ctor.fields());
                    symbolTable.define(varName, varRecordType);

                    // Generate body from the entry's statements
                    PirTerm bodyTerm = generateSwitchEntryBody(entry);

                    symbolTable.popScope();
                    // For DataMatch, we bind the constructor fields
                    matchEntries.add(new PatternMatchDesugarer.MatchEntry(
                            typeName, ctor.fields().stream().map(PirType.Field::name).toList(), bodyTerm, varName));
                }
            }
        }

        if (defaultBranchBody != null) {
            // Fill missing variants with the default branch body
            var coveredCases = matchEntries.stream()
                    .map(PatternMatchDesugarer.MatchEntry::variantName)
                    .collect(java.util.stream.Collectors.toSet());
            for (var ctor : sumType.constructors()) {
                if (!coveredCases.contains(ctor.name())) {
                    matchEntries.add(new PatternMatchDesugarer.MatchEntry(
                            ctor.name(), List.of(), defaultBranchBody));
                }
            }
        } else {
            // Exhaustiveness check: verify all constructors are covered
            var coveredCases = matchEntries.stream()
                    .map(PatternMatchDesugarer.MatchEntry::variantName)
                    .collect(java.util.stream.Collectors.toSet());
            var missingCases = new LinkedHashSet<String>();
            for (var ctor : sumType.constructors()) {
                if (!coveredCases.contains(ctor.name())) {
                    missingCases.add(ctor.name());
                }
            }
            if (!missingCases.isEmpty()) {
                collectError("Switch on sealed interface '" + sumType.name()
                                + "' is not exhaustive. Missing cases: " + String.join(", ", missingCases),
                        "Add explicit cases for all variants.", se);
            }
        }

        return desugarer.buildDataMatch(selector, sumType, matchEntries);
    }

    private PirTerm generateSwitchEntryBody(com.github.javaparser.ast.stmt.SwitchEntry entry) {
        var stmts = entry.getStatements();
        if (stmts.isEmpty()) {
            return new PirTerm.Const(Constant.unit());
        }
        if (stmts.size() == 1) {
            var stmt = stmts.get(0);
            if (stmt instanceof ExpressionStmt es) {
                return generateExpression(es.getExpression());
            }
            if (stmt instanceof BlockStmt block) {
                return generateBlock(block);
            }
            if (stmt instanceof ReturnStmt rs) {
                var result = rs.getExpression().map(this::generateExpression)
                        .orElse(new PirTerm.Const(Constant.unit()));
                return applyBooleanReturnGuard(result, rs);
            }
        }
        return generateStatements(new ArrayList<>(stmts), 0);
    }

    private PirTerm generateInstanceOf(InstanceOfExpr ioe) {
        // For pattern matching: expr instanceof TypeName varName
        // This is used in if/else chains for ad-hoc matching
        // For now, just check the constructor tag
        var scrutinee = generateExpression(ioe.getExpression());
        var patternType = ioe.getPattern();
        if (patternType.isPresent() && patternType.get() instanceof com.github.javaparser.ast.expr.TypePatternExpr tpe) {
            var typeName = tpe.getType().asClassOrInterfaceType().getNameAsString();
            var sumType = typeResolver.lookupSumTypeForVariant(typeName);
            if (sumType.isPresent()) {
                // Find the tag for this variant
                for (var ctor : sumType.get().constructors()) {
                    if (ctor.name().equals(typeName)) {
                        // Emit: FstPair(UnConstrData(scrutinee)) == tag
                        // But since we work at PIR level, emit a tag check
                        var tagCheck = new PirTerm.App(
                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair),
                                                new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), scrutinee))),
                                new PirTerm.Const(Constant.integer(BigInteger.valueOf(ctor.tag()))));
                        return tagCheck;
                    }
                }
            }
        }
        throw enrichedError("Unsupported instanceof pattern: " + ioe,
                "instanceof is supported only with sealed interface variants: if (x instanceof Variant v) { ... }",
                ioe);
    }

    private PirTerm generateLambda(LambdaExpr le) {
        var params = le.getParameters();

        // Push lambda parameters into scope
        symbolTable.pushScope();
        for (var param : params) {
            var pirType = typeResolver.resolve(param.getType());
            symbolTable.define(param.getNameAsString(), pirType);
        }

        // Generate body — lambda body is a Statement (ExpressionStmt or BlockStmt)
        PirTerm bodyTerm;
        var lambdaBody = le.getBody();
        if (lambdaBody instanceof com.github.javaparser.ast.stmt.ExpressionStmt es) {
            bodyTerm = generateExpression(es.getExpression());
        } else if (lambdaBody instanceof com.github.javaparser.ast.stmt.BlockStmt block) {
            bodyTerm = generateBlock(block);
        } else {
            throw enrichedError("Unsupported lambda body: " + lambdaBody.getClass().getSimpleName(),
                    "Lambda bodies must be a single expression or a block statement.", le);
        }

        symbolTable.popScope();

        // Wrap body in lambda chain: \p1 -> \p2 -> ... -> body
        PirTerm result = bodyTerm;
        for (int i = params.size() - 1; i >= 0; i--) {
            var param = params.get(i);
            var pirType = typeResolver.resolve(param.getType());
            result = new PirTerm.Lam(param.getNameAsString(), pirType, result);
        }

        // Zero-parameter lambda: \_ -> body
        if (params.isEmpty()) {
            result = new PirTerm.Lam("_", new PirType.UnitType(), result);
        }

        return result;
    }

    /**
     * Generate a lambda with inferred parameter types from the HOF context.
     * <p>
     * When the lambda has untyped params (e.g., {@code x -> x + 1}), this method uses
     * the expected parameter types to:
     * <ol>
     *   <li>Define the param in the symbol table with the inferred type (enables correct arithmetic/comparison ops)</li>
     *   <li>Insert wrapDecode Let bindings for primitive types (Lam receives raw Data, body needs unwrapped value)</li>
     *   <li>Optionally wrap the result to Data (for map, where MkCons expects Data elements)</li>
     * </ol>
     *
     * @param le the lambda expression
     * @param expectedParamTypes the inferred parameter types from HOF context
     * @param wrapResultToData if true, wrap the lambda body result with wrapEncode (for map)
     */
    private PirTerm generateLambda(LambdaExpr le, java.util.List<PirType> expectedParamTypes, boolean wrapResultToData) {
        var params = le.getParameters();

        symbolTable.pushScope();

        var resolvedTypes = new ArrayList<PirType>();
        var needsUnwrap = new ArrayList<Boolean>();

        for (int i = 0; i < params.size(); i++) {
            var param = params.get(i);
            PirType pirType;
            boolean unwrap = false;
            if (param.getType().isUnknownType() && i < expectedParamTypes.size()) {
                pirType = expectedParamTypes.get(i);
                // Only unwrap primitive types (Integer, ByteString, Bool, String)
                // RecordType/SumType/DataType are already Data at UPLC level
                unwrap = isPrimitiveUplcType(pirType);
            } else {
                pirType = typeResolver.resolve(param.getType());
            }
            resolvedTypes.add(pirType);
            needsUnwrap.add(unwrap);
            symbolTable.define(param.getNameAsString(), pirType);
        }

        // Track HOF-unwrapped vars so dispatch can skip redundant UnBData.
        // Union with outer scope's unwrapped vars — nested lambdas may capture outer vars.
        var prevHofUnwrapped = hofUnwrappedVars;
        var unwrappedNames = new LinkedHashSet<String>(hofUnwrappedVars);
        for (int i = 0; i < params.size(); i++) {
            if (needsUnwrap.get(i)) {
                unwrappedNames.add(params.get(i).getNameAsString());
            }
        }
        hofUnwrappedVars = unwrappedNames;

        // Generate body
        PirTerm bodyTerm;
        var lambdaBody = le.getBody();
        if (lambdaBody instanceof com.github.javaparser.ast.stmt.ExpressionStmt es) {
            bodyTerm = generateExpression(es.getExpression());
        } else if (lambdaBody instanceof com.github.javaparser.ast.stmt.BlockStmt block) {
            bodyTerm = generateBlock(block);
        } else {
            throw enrichedError("Unsupported lambda body: " + lambdaBody.getClass().getSimpleName(),
                    "Lambda bodies must be a single expression or a block statement.", le);
        }

        hofUnwrappedVars = prevHofUnwrapped;
        symbolTable.popScope();

        // Auto-wrap result for map (MkCons expects Data elements)
        if (wrapResultToData) {
            var bodyType = inferPirType(bodyTerm);
            if (!(bodyType instanceof PirType.DataType)) {
                bodyTerm = PirHelpers.wrapEncode(bodyTerm, bodyType);
            }
        }

        // Insert wrapDecode Let bindings for inferred primitive types
        // Uses same-name shadowing: Let("x", UnIData(Var("x")), body)
        // UplcGenerator resolves correctly — value uses outer scope, body uses inner
        for (int i = params.size() - 1; i >= 0; i--) {
            if (needsUnwrap.get(i)) {
                var paramName = params.get(i).getNameAsString();
                bodyTerm = new PirTerm.Let(paramName,
                        PirHelpers.wrapDecode(
                                new PirTerm.Var(paramName, new PirType.DataType()),
                                resolvedTypes.get(i)),
                        bodyTerm);
            }
        }

        // Build Lam chain — use DataType for inferred params (Lam receives raw Data)
        PirTerm result = bodyTerm;
        for (int i = params.size() - 1; i >= 0; i--) {
            var param = params.get(i);
            var lamType = needsUnwrap.get(i) ? new PirType.DataType() : resolvedTypes.get(i);
            result = new PirTerm.Lam(param.getNameAsString(), lamType, result);
        }

        if (params.isEmpty()) {
            result = new PirTerm.Lam("_", new PirType.UnitType(), result);
        }
        return result;
    }

    /**
     * Returns true for primitive UPLC types that need unwrap/wrap at Data boundaries.
     * RecordType, SumType, DataType, ListType, MapType are already Data — no conversion needed.
     */
    private static boolean isPrimitiveUplcType(PirType type) {
        return type instanceof PirType.IntegerType
                || type instanceof PirType.ByteStringType
                || type instanceof PirType.BoolType
                || type instanceof PirType.StringType;
    }

    private static final java.util.Set<String> LIST_HOF_METHODS =
            java.util.Set.of("map", "filter", "any", "all", "find");

    private static final java.util.Set<String> STATIC_HOF_METHODS =
            java.util.Set.of("map", "filter", "any", "all", "find", "foldl");

    /**
     * Infer expected lambda parameter types for a HOF call based on the list's element type.
     */
    private java.util.List<PirType> inferHofLambdaParamTypes(String methodName, PirType.ListType lt) {
        return java.util.List.of(lt.elemType());
    }

    // --- Helpers ---

    private PirType inferType(com.github.javaparser.ast.type.Type declType, PirTerm initValue,
                               Expression initExpr) {
        // If an explicit type is given (not 'var'), resolve it directly
        if (!(declType instanceof com.github.javaparser.ast.type.VarType)) {
            return typeResolver.resolve(declType);
        }
        // For 'var', try to infer from the original JavaParser expression first
        // (needed for record field access that returns ListType, MapType, etc.)
        var exprType = resolveExpressionType(initExpr);
        if (!(exprType instanceof PirType.DataType)) {
            return exprType;
        }
        // Fall back to inferring from the PIR term
        return inferPirType(initValue);
    }

    private PirType inferPirType(PirTerm term) {
        if (term instanceof PirTerm.Const c) {
            return switch (c.value()) {
                case Constant.IntegerConst _ -> new PirType.IntegerType();
                case Constant.BoolConst _ -> new PirType.BoolType();
                case Constant.StringConst _ -> new PirType.StringType();
                case Constant.ByteStringConst _ -> new PirType.ByteStringType();
                case Constant.UnitConst _ -> new PirType.UnitType();
                default -> new PirType.DataType();
            };
        }
        if (term instanceof PirTerm.App app) {
            // For builtin applications, infer from the builtin function
            PirTerm fn = app.function();
            // Two-arg builtins: App(App(Builtin(f), a), b)
            if (fn instanceof PirTerm.App innerApp && innerApp.function() instanceof PirTerm.Builtin b) {
                return inferBuiltinReturnType(b.fun());
            }
            // One-arg builtins: App(Builtin(f), a)
            if (fn instanceof PirTerm.Builtin b) {
                // FstPair(UnConstrData(x)) → IntegerType (constr tag)
                if (b.fun() == DefaultFun.FstPair
                        && app.argument() instanceof PirTerm.App argApp
                        && argApp.function() instanceof PirTerm.Builtin argB
                        && argB.fun() == DefaultFun.UnConstrData) {
                    return new PirType.IntegerType();
                }
                return inferBuiltinReturnType(b.fun());
            }
            // For applications of Var/lambda with known FunType, peel return type
            var fnType = inferPirType(fn);
            if (fnType instanceof PirType.FunType ft) {
                return ft.returnType();
            }
        }
        if (term instanceof PirTerm.Var v) {
            return v.type();
        }
        if (term instanceof PirTerm.IfThenElse ite) {
            return inferPirType(ite.thenBranch());
        }
        if (term instanceof PirTerm.Let let) {
            return inferPirType(let.body());
        }
        if (term instanceof PirTerm.LetRec letRec) {
            return inferPirType(letRec.body());
        }
        return new PirType.DataType();
    }

    private PirType inferBuiltinReturnType(DefaultFun fun) {
        return switch (fun) {
            case AddInteger, SubtractInteger, MultiplyInteger, DivideInteger,
                 QuotientInteger, RemainderInteger, ModInteger,
                 LengthOfByteString, ByteStringToInteger, UnIData -> new PirType.IntegerType();
            case EqualsInteger, LessThanInteger, LessThanEqualsInteger,
                 EqualsByteString, LessThanByteString, LessThanEqualsByteString,
                 EqualsString, EqualsData, NullList -> new PirType.BoolType();
            case AppendByteString, SliceByteString, ConsByteString,
                 Sha2_256, Sha3_256, Blake2b_256, EncodeUtf8, UnBData -> new PirType.ByteStringType();
            case AppendString, DecodeUtf8 -> new PirType.StringType();
            // List-returning builtins: these return List(Data) in UPLC
            case UnListData, TailList, MkCons, MkNilData -> new PirType.ListType(new PirType.DataType());
            // Map-returning builtins: these return List(Pair(Data,Data)) in UPLC
            case UnMapData, MkNilPairData -> new PirType.MapType(new PirType.DataType(), new PirType.DataType());
            default -> new PirType.DataType();
        };
    }

    private static PirTerm builtinApp2(DefaultFun fun, PirTerm a, PirTerm b) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(fun), a),
                b);
    }

    // --- Error enrichment helpers ---

    /**
     * Create an enriched compiler error with source location and a suggestion.
     *
     * @param msg        the error message
     * @param suggestion a helpful suggestion for fixing the error, or null
     * @param node       the AST node for source location extraction
     */
    private static CompilerException enrichedError(String msg, String suggestion, Node node) {
        var location = extractLocation(node);
        var fullMsg = new StringBuilder();
        if (!location.isEmpty()) {
            fullMsg.append(location).append(": ");
        }
        fullMsg.append(msg);
        if (suggestion != null && !suggestion.isEmpty()) {
            fullMsg.append("\n  Hint: ").append(suggestion);
        }
        return new CompilerException(fullMsg.toString());
    }

    private static String extractLocation(Node node) {
        if (node == null) return "";
        var range = node.getRange();
        if (range.isEmpty()) return "";
        var begin = range.get().begin;
        // Try to get the file name from the compilation unit
        var cu = node.findCompilationUnit();
        String file = cu.flatMap(c -> c.getStorage())
                .map(s -> s.getFileName())
                .orElse("<source>");
        return file + ":" + begin.line + ":" + begin.column;
    }

    /**
     * Find the closest method name from available stdlib methods.
     * Returns a suggestion string like "Did you mean 'ListsLib.contains()'?" or empty if no close match.
     */
    private String suggestSimilarMethod(String methodName) {
        if (stdlibLookup == null) return "";

        // Known stdlib class names and their methods
        var knownMethods = List.of(
                "Builtins.headList", "Builtins.tailList", "Builtins.nullList", "Builtins.mkCons",
                "Builtins.mkNilData", "Builtins.fstPair", "Builtins.sndPair", "Builtins.mkPairData",
                "Builtins.constrData", "Builtins.iData", "Builtins.bData", "Builtins.listData",
                "Builtins.mapData", "Builtins.unConstrData", "Builtins.unIData", "Builtins.unBData",
                "Builtins.unListData", "Builtins.unMapData", "Builtins.equalsData",
                "Builtins.sha2_256", "Builtins.blake2b_256", "Builtins.verifyEd25519Signature",
                "Builtins.trace", "Builtins.error",
                "ListsLib.contains", "ListsLib.length", "ListsLib.reverse", "ListsLib.map",
                "ListsLib.filter", "ListsLib.foldl", "ListsLib.any", "ListsLib.all",
                "ListsLib.find", "ListsLib.concat", "ListsLib.nth", "ListsLib.take",
                "ListsLib.drop", "ListsLib.zip",
                "MathLib.abs", "MathLib.max", "MathLib.min", "MathLib.pow",
                "MathLib.divMod", "MathLib.quotRem", "MathLib.sign", "MathLib.expMod",
                "MapLib.lookup", "MapLib.member", "MapLib.insert", "MapLib.delete",
                "MapLib.keys", "MapLib.values", "MapLib.size", "MapLib.fromList", "MapLib.toList",
                "ValuesLib.geqMultiAsset", "ValuesLib.leq", "ValuesLib.eq", "ValuesLib.isZero",
                "ValuesLib.singleton", "ValuesLib.negate", "ValuesLib.flatten",
                "ContextsLib.getTxInfo", "ContextsLib.signedBy", "ContextsLib.findOwnInput",
                "ContextsLib.getContinuingOutputs", "ContextsLib.findDatum",
                "ContextsLib.valueSpent", "ContextsLib.valuePaid", "ContextsLib.ownHash",
                "ByteStringLib.length", "ByteStringLib.append", "ByteStringLib.equals",
                "CryptoLib.sha2_256", "CryptoLib.blake2b_256",
                "IntervalLib.between", "IntervalLib.never", "IntervalLib.isEmpty"
        );

        String bestMatch = null;
        int bestDist = Integer.MAX_VALUE;

        for (String qualified : knownMethods) {
            String simpleName = qualified.substring(qualified.indexOf('.') + 1);
            int dist = StringUtils.levenshtein(methodName.toLowerCase(), simpleName.toLowerCase());
            if (dist < bestDist && dist <= Math.max(2, methodName.length() / 3)) {
                bestDist = dist;
                bestMatch = qualified;
            }
        }

        if (bestMatch != null) {
            return "Did you mean '" + bestMatch + "()'?";
        }
        return "";
    }

    /**
     * Find which variable names from `candidates` are referenced in a PIR term.
     * Used to determine which pre-loop variables need to be re-bound after a while loop.
     */
    private static Set<String> findReferencedVars(PirTerm term, Map<String, PirType> candidates) {
        var result = new LinkedHashSet<String>();
        collectReferencedVars(term, candidates, result);
        return result;
    }

    private static void collectReferencedVars(PirTerm term, Map<String, PirType> candidates, Set<String> result) {
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

    /**
     * Wrap a PIR term with Let re-bindings for pre-loop variables that are referenced in the term.
     * This fixes the post-while-loop variable corruption bug where LetRec scope boundaries
     * from while loop desugaring block access to outer Let bindings.
     */
    private static PirTerm rebindPreLoopVars(PirTerm rest, Map<String, PirType> preLoopVars, Set<String> accumulatorNames) {
        // Filter out accumulator names — they are already rebound by unpackAccumulators
        var candidates = new LinkedHashMap<>(preLoopVars);
        for (var accName : accumulatorNames) {
            candidates.remove(accName);
        }
        if (candidates.isEmpty()) return rest;

        var referenced = findReferencedVars(rest, candidates);
        if (referenced.isEmpty()) return rest;

        // Wrap rest with Let re-bindings: Let(name, Var(name, type), rest)
        // This creates a fresh binding that captures the pre-loop variable value
        // inside the scope that the LetRec transformation produces.
        PirTerm result = rest;
        for (var name : referenced) {
            var type = candidates.get(name);
            result = new PirTerm.Let(name, new PirTerm.Var(name, type), result);
        }
        return result;
    }
}
