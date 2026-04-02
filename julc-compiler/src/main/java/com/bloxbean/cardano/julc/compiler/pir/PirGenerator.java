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
    private final TypeInferenceHelper typeInference;
    private final LoopBodyGenerator loopBody;
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
        this.typeInference = new TypeInferenceHelper(symbolTable, typeResolver, stdlibLookup, typeMethodRegistry);
        this.loopBody = new LoopBodyGenerator(this, symbolTable);
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
                    .flatMap(cu -> cu.getStorage().map(s -> s.getPath().toString()))
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
        return TypeInferenceHelper.findLibraryRegistry(lookup);
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

    PirTerm generateStatement(Statement stmt) {
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

        if (mce.getScope().isPresent()) {
            var scopeExpr = mce.getScope().get();
            PirTerm result;

            // 1. Constant folding: BigInteger.valueOf(n) → integer constant
            result = tryBigIntegerConstant(mce, scopeExpr, methodName, args);
            if (result != null) return result;

            // 2. Identity recognition: fromPlutusData() on known types
            result = tryFromPlutusDataIdentity(mce, scopeExpr, methodName, args);
            if (result != null) return result;

            // 3. Cast identity: PlutusData.cast(data, Type.class)
            result = tryPlutusDataCast(mce, scopeExpr, methodName, args);
            if (result != null) return result;

            // 4. Static stdlib/library dispatch
            result = tryStaticStdlibCall(mce, scopeExpr, methodName, args);
            if (result != null) return result;

            // 5-8. Instance dispatch (field access, chained access, registry, fallback)
            return resolveInstanceMethodCall(mce, scopeExpr, methodName, args);
        }

        // Unqualified method call — local or library static method
        return resolveUnqualifiedMethodCall(mce, methodName, args);
    }

    /** BigInteger.valueOf(n) → compile the argument directly as an integer constant. */
    private PirTerm tryBigIntegerConstant(MethodCallExpr mce, Expression scopeExpr,
                                           String methodName, com.github.javaparser.ast.NodeList<Expression> args) {
        if (scopeExpr instanceof NameExpr ne && ne.getNameAsString().equals("BigInteger")
                && methodName.equals("valueOf") && args.size() == 1) {
            return generateExpression(args.get(0));
        }
        return null;
    }

    /** Auto-recognize fromPlutusData() on known Plutus data types as identity. */
    private PirTerm tryFromPlutusDataIdentity(MethodCallExpr mce, Expression scopeExpr,
                                               String methodName, com.github.javaparser.ast.NodeList<Expression> args) {
        if (scopeExpr instanceof NameExpr ne && methodName.equals("fromPlutusData") && args.size() == 1) {
            var resolvedClassName = typeResolver.resolveClassName(ne.getNameAsString());
            var targetType = typeResolver.resolveNameToType(resolvedClassName);
            if (targetType.isPresent()) {
                options.logf("Resolved fromPlutusData identity: %s.fromPlutusData", ne.getNameAsString());
                return generateExpression(args.get(0));
            }
        }
        return null;
    }

    /** PlutusData.cast(data, TargetType.class) → identity with optional MapType unwrap. */
    private PirTerm tryPlutusDataCast(MethodCallExpr mce, Expression scopeExpr,
                                       String methodName, com.github.javaparser.ast.NodeList<Expression> args) {
        if (!(scopeExpr instanceof NameExpr ne
                && ne.getNameAsString().equals("PlutusData")
                && methodName.equals("cast") && args.size() == 2)) {
            return null;
        }
        if (!(args.get(1) instanceof ClassExpr)) {
            return collectError(
                "PlutusData.cast() second argument must be a literal ClassName.class expression",
                "Use PlutusData.cast(data, MyType.class) — a variable holding a Class<?> is not supported on-chain.",
                mce);
        }
        var classExpr = (ClassExpr) args.get(1);
        options.logf("Resolved PlutusData.cast: %s", classExpr.getType());
        var inner = generateExpression(args.get(0));
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

    /** Check if scope is a class name for static stdlib/library call (e.g., ContextsLib.signedBy). */
    private PirTerm tryStaticStdlibCall(MethodCallExpr mce, Expression scopeExpr,
                                         String methodName, com.github.javaparser.ast.NodeList<Expression> args) {
        if (!(scopeExpr instanceof NameExpr ne) || stdlibLookup == null) return null;

        var className = ne.getNameAsString();
        var resolvedClassName = typeResolver.resolveClassName(className);
        var compiledArgs = new ArrayList<PirTerm>();
        var argPirTypes = new ArrayList<PirType>();

        boolean isStaticHof = (className.equals("ListsLib") || resolvedClassName.endsWith(".ListsLib"))
                && STATIC_HOF_METHODS.contains(methodName) && args.size() >= 2;

        if (isStaticHof) {
            // Compile list arg first to determine element type for lambda type inference
            var listArg = generateExpression(args.get(0));
            compiledArgs.add(listArg);
            var listType = resolveExpressionType(args.get(0));
            if (listType instanceof PirType.DataType) listType = inferPirType(listArg);
            argPirTypes.add(listType);

            for (int i = 1; i < args.size(); i++) {
                var arg = args.get(i);
                PirTerm argPir;
                if (arg.isLambdaExpr() && listType instanceof PirType.ListType lt) {
                    var expectedTypes = inferHofLambdaParamTypes(methodName, lt);
                    boolean wrapResult = methodName.equals("map");
                    if (methodName.equals("foldl") && i == 1) {
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
        return null;
    }

    /**
     * Resolve an instance method call: record field access, chained access,
     * TypeMethodRegistry dispatch, and ByteStringType fallback.
     */
    private PirTerm resolveInstanceMethodCall(MethodCallExpr mce, Expression scopeExpr,
                                               String methodName, com.github.javaparser.ast.NodeList<Expression> args) {
        var scope = generateExpression(scopeExpr);

        // Record field access on Data-typed parameter
        if (args.isEmpty() && scopeExpr instanceof NameExpr ne) {
            var recordField = resolveRecordFieldAccess(ne.getNameAsString(), methodName);
            if (recordField.isPresent()) {
                return recordField.get().apply(scope);
            }
        }

        // Chained record field access: scope.innerRecord().field()
        if (args.isEmpty() && scopeExpr instanceof MethodCallExpr innerMce) {
            var scopeType = resolveMethodCallReturnType(innerMce);
            if (scopeType instanceof PirType.RecordType rt) {
                for (int i = 0; i < rt.fields().size(); i++) {
                    if (rt.fields().get(i).name().equals(methodName)) {
                        return generateFieldExtraction(scope, i, rt.fields().get(i).type());
                    }
                }
            }
            // Chained .hash() on ByteStringType — identity (already UnBData'd by parent)
            if (scopeType instanceof PirType.ByteStringType && methodName.equals("hash")) {
                return scope;
            }
        }

        // TypeMethodRegistry dispatch
        {
            var scopeType = resolveExpressionType(scopeExpr);
            if (scopeType instanceof PirType.DataType) scopeType = inferPirType(scope);

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

            // Skip .hash() on HOF-unwrapped ByteStringType vars (prevents double UnBData)
            if (scopeType instanceof PirType.ByteStringType
                    && methodName.equals("hash") && args.isEmpty()
                    && scopeExpr instanceof NameExpr ne
                    && hofUnwrappedVars.contains(ne.getNameAsString())) {
                return scope;
            }

            var registryResult = typeMethodRegistry.dispatch(scope, methodName, compiledArgs, scopeType, argPirTypes);
            if (registryResult.isPresent()) return registryResult.get();

            // Auto-recognize toPlutusData() — encode value as Data
            if (methodName.equals("toPlutusData") && args.isEmpty()) {
                return PirHelpers.wrapEncode(scope, scopeType);
            }

            // Handle unregistered no-arg methods on ByteStringType
            if (scopeType instanceof PirType.ByteStringType && args.isEmpty()) {
                if (scopeExpr instanceof NameExpr ne
                        && hofUnwrappedVars.contains(ne.getNameAsString())) {
                    return scope;
                }
                if (scopeExpr instanceof MethodCallExpr) {
                    return scope;
                }
                return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), scope);
            }
        }

        return generateFieldAccessFromMethod(scope, methodName, args);
    }

    /** Resolve an unqualified method call — local static method or library method. */
    private PirTerm resolveUnqualifiedMethodCall(MethodCallExpr mce, String methodName,
                                                  com.github.javaparser.ast.NodeList<Expression> args) {
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

    private PirType resolveExpressionType(Expression expr) {
        return typeInference.resolveExpressionType(expr);
    }

    private PirType resolveMethodCallReturnType(MethodCallExpr mce) {
        return typeInference.resolveMethodCallReturnType(mce);
    }

    private static PirType extractReturnType(PirType type) {
        return TypeInferenceHelper.extractReturnType(type);
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

    PirTerm generateForEachStmt(ForEachStmt fes, List<Statement> followingStmts, int followingIndex) {
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

                            var bodyTerm = generateBreakAwareBody(fes.getBody(), accName, accType, continueFn);

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
                var bodyTerm = generateSingleAccBody(fes.getBody(), accName, accType);
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
                                    generateMultiAccBreakAwareBody(fes.getBody(), accNames, accTypesFinal, continueFn));

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
                        generateMultiAccBody(fes.getBody(), accumulators, accTypes));

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

            var bodyTerm = generateStatement(fes.getBody());
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
        return loopBody.generateBreakAwareBody(bodyStmt, accName, accType, continueFn);
    }

    private boolean containsBreak(Statement stmt) {
        return loopBody.containsBreak(stmt);
    }

    private boolean containsReturn(Statement stmt) {
        return loopBody.containsReturn(stmt);
    }

    private boolean needsForEachUnwrapTracking(PirType elemType) {
        return loopBody.needsForEachUnwrapTracking(elemType);
    }

    private static List<Statement> blockStmts(Statement stmt) {
        return PirHelpers.blockStmts(stmt);
    }

    private PirTerm generateSingleAccBody(Statement bodyStmt, String accName, PirType accType) {
        return loopBody.generateSingleAccBody(bodyStmt, accName, accType);
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
        return AccumulatorTypeAnalyzer.refineAccumulatorTypes(ws, accNames, initialTypes, precedingStmts);
    }

    List<String> detectForEachAccumulators(Statement bodyStmt) {
        return AccumulatorTypeAnalyzer.detectForEachAccumulators(bodyStmt, symbolTable::lookup);
    }

    private String findAccumulatorInBreakPattern(List<Statement> stmts) {
        return AccumulatorTypeAnalyzer.findAccumulatorInBreakPattern(stmts, symbolTable::lookup);
    }

    // --- Multi-accumulator helpers (delegate to LoopBodyGenerator) ---

    private PirTerm packAccumulators(List<String> names, List<PirType> types) {
        return loopBody.packAccumulators(names, types);
    }

    private PirTerm unpackAccumulators(PirTerm tuple, List<String> names, List<PirType> types, PirTerm body) {
        return loopBody.unpackAccumulators(tuple, names, types, body);
    }

    private PirTerm generateMultiAccBody(Statement bodyStmt, List<String> accNames, List<PirType> accTypes) {
        return loopBody.generateMultiAccBody(bodyStmt, accNames, accTypes);
    }

    private PirTerm generateMultiAccBreakAwareBody(Statement bodyStmt, List<String> accNames,
                                                     List<PirType> accTypes,
                                                     Function<PirTerm, PirTerm> continueFn) {
        return loopBody.generateMultiAccBreakAwareBody(bodyStmt, accNames, accTypes, continueFn);
    }

    PirTerm generateWhileStmt(WhileStmt ws, List<Statement> followingStmts, int followingIndex) {
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

                            var bodyTerm = generateBreakAwareBody(ws.getBody(), accName, accType, continueFn);

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
                var bodyTerm = generateSingleAccBody(ws.getBody(), accName, accType);
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
                                    generateMultiAccBreakAwareBody(ws.getBody(), accNames, accTypesFinal, continueFn));

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
                        generateMultiAccBody(ws.getBody(), accumulators, accTypes));

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
            var bodyTerm = generateStatement(ws.getBody());

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

    PirType inferType(com.github.javaparser.ast.type.Type declType, PirTerm initValue,
                       Expression initExpr) {
        return typeInference.inferType(declType, initValue, initExpr);
    }

    private PirType inferPirType(PirTerm term) {
        return typeInference.inferPirType(term);
    }

    private PirType inferBuiltinReturnType(DefaultFun fun) {
        return typeInference.inferBuiltinReturnType(fun);
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
    static CompilerException enrichedError(String msg, String suggestion, Node node) {
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

    private static PirTerm rebindPreLoopVars(PirTerm rest, Map<String, PirType> preLoopVars, Set<String> accumulatorNames) {
        return LoopBodyGenerator.rebindPreLoopVars(rest, preLoopVars, accumulatorNames);
    }
}
