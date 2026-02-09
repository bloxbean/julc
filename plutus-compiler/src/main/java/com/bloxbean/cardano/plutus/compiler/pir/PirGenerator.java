package com.bloxbean.cardano.plutus.compiler.pir;

import com.bloxbean.cardano.plutus.compiler.CompilerException;
import com.bloxbean.cardano.plutus.compiler.desugar.LoopDesugarer;
import com.bloxbean.cardano.plutus.compiler.desugar.PatternMatchDesugarer;
import com.bloxbean.cardano.plutus.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.plutus.compiler.resolve.TypeResolver;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;

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
    private String forEachAccumulatorVar; // non-null when compiling fold body
    private Function<PirTerm, PirTerm> breakContinueFn; // non-null when compiling break-capable fold body
    private Set<String> multiAccVars = Set.of(); // non-empty when compiling multi-acc fold body

    public PirGenerator(TypeResolver typeResolver, SymbolTable symbolTable) {
        this(typeResolver, symbolTable, null, TypeMethodRegistry.defaultRegistry());
    }

    public PirGenerator(TypeResolver typeResolver, SymbolTable symbolTable, StdlibLookup stdlibLookup) {
        this(typeResolver, symbolTable, stdlibLookup, TypeMethodRegistry.defaultRegistry());
    }

    public PirGenerator(TypeResolver typeResolver, SymbolTable symbolTable,
                        StdlibLookup stdlibLookup, TypeMethodRegistry typeMethodRegistry) {
        this.typeResolver = typeResolver;
        this.symbolTable = symbolTable;
        this.stdlibLookup = stdlibLookup;
        this.typeMethodRegistry = typeMethodRegistry;
    }

    /**
     * Generate PIR for a method body. Returns a lambda term wrapping the body.
     */
    public PirTerm generateMethod(MethodDeclaration method) {
        var params = method.getParameters();
        var body = method.getBody().orElseThrow(
                () -> new CompilerException("Method must have a body: " + method.getNameAsString()));

        // Register parameters in scope
        symbolTable.pushScope();
        for (var param : params) {
            var pirType = typeResolver.resolve(param.getType());
            symbolTable.define(param.getNameAsString(), pirType);
        }

        PirTerm bodyTerm = generateBlock(body);
        symbolTable.popScope();

        // Wrap body in lambda chain: \p1 -> \p2 -> ... -> body
        PirTerm result = bodyTerm;
        for (int i = params.size() - 1; i >= 0; i--) {
            var param = params.get(i);
            var pirType = typeResolver.resolve(param.getType());
            result = new PirTerm.Lam(param.getNameAsString(), pirType, result);
        }
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
            return rs.getExpression().map(this::generateExpression)
                    .orElse(new PirTerm.Const(Constant.unit()));
        }
        if (stmt instanceof ExpressionStmt es) {
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                var name = decl.getNameAsString();
                var initExpr = decl.getInitializer().orElseThrow(
                        () -> new CompilerException("Variable must be initialized: " + name));
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
            throw new CompilerException("break statement outside of a loop");
        }
        throw new CompilerException("Unsupported statement: " + stmt.getClass().getSimpleName());
    }

    private PirTerm generateIfStmt(IfStmt is, List<Statement> followingStmts, int followingIndex) {
        var cond = generateExpression(is.getCondition());
        var thenTerm = generateStatement(is.getThenStmt());
        var elseTerm = is.getElseStmt()
                .map(this::generateStatement)
                .orElse(new PirTerm.Const(Constant.unit()));
        var ifExpr = new PirTerm.IfThenElse(cond, thenTerm, elseTerm);

        // If there are statements following the if, wrap in a let
        if (followingIndex + 1 < followingStmts.size()) {
            var rest = generateStatements(followingStmts, followingIndex + 1);
            return new PirTerm.Let("_if", ifExpr, rest);
        }
        return ifExpr;
    }

    private PirTerm generateStatement(Statement stmt) {
        if (stmt instanceof BlockStmt block) {
            symbolTable.pushScope();
            var result = generateBlock(block);
            symbolTable.popScope();
            return result;
        }
        if (stmt instanceof ReturnStmt rs) {
            return rs.getExpression().map(this::generateExpression)
                    .orElse(new PirTerm.Const(Constant.unit()));
        }
        if (stmt instanceof ExpressionStmt es) {
            return generateExpression(es.getExpression());
        }
        if (stmt instanceof IfStmt is) {
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
        throw new CompilerException("Unsupported statement: " + stmt.getClass().getSimpleName());
    }

    public PirTerm generateExpression(Expression expr) {
        if (expr instanceof IntegerLiteralExpr ile) {
            return new PirTerm.Const(Constant.integer(new BigInteger(ile.getValue())));
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
            var type = symbolTable.require(name);
            return new PirTerm.Var(name, type);
        }
        if (expr instanceof BinaryExpr be) {
            return generateBinaryExpr(be);
        }
        if (expr instanceof UnaryExpr ue) {
            return generateUnaryExpr(ue);
        }
        if (expr instanceof EnclosedExpr ee) {
            return generateExpression(ee.getInner());
        }
        if (expr instanceof MethodCallExpr mce) {
            return generateMethodCall(mce);
        }
        if (expr instanceof FieldAccessExpr fae) {
            return generateFieldAccess(fae);
        }
        if (expr instanceof ObjectCreationExpr oce) {
            return generateObjectCreation(oce);
        }
        if (expr instanceof ConditionalExpr ce) {
            // Ternary: cond ? then : else
            return new PirTerm.IfThenElse(
                    generateExpression(ce.getCondition()),
                    generateExpression(ce.getThenExpr()),
                    generateExpression(ce.getElseExpr()));
        }
        if (expr instanceof LambdaExpr le) {
            return generateLambda(le);
        }
        if (expr instanceof SwitchExpr se) {
            return generateSwitchExpr(se);
        }
        if (expr instanceof InstanceOfExpr ioe) {
            return generateInstanceOf(ioe);
        }
        if (expr instanceof AssignExpr ae && ae.getTarget() instanceof NameExpr ne) {
            var name = ne.getNameAsString();
            if ((forEachAccumulatorVar != null && name.equals(forEachAccumulatorVar))
                    || multiAccVars.contains(name)) {
                return generateExpression(ae.getValue());
            }
        }
        throw new CompilerException("Unsupported expression: " + expr.getClass().getSimpleName() + " = " + expr);
    }

    private PirTerm generateBinaryExpr(BinaryExpr be) {
        var left = generateExpression(be.getLeft());
        var right = generateExpression(be.getRight());

        // Infer operand type for type-aware dispatching
        var leftType = resolveExpressionType(be.getLeft());
        if (leftType instanceof PirType.DataType) leftType = inferPirType(left);

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
            default -> throw new CompilerException("Unsupported operator: " + be.getOperator());
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
            default -> throw new CompilerException("Unsupported unary operator: " + ue.getOperator());
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

            // Check if scope is a class name for static stdlib call (e.g., ContextsLib.signedBy)
            if (scopeExpr instanceof NameExpr ne && stdlibLookup != null) {
                var className = ne.getNameAsString();
                var compiledArgs = new ArrayList<PirTerm>();
                for (var arg : args) {
                    compiledArgs.add(generateExpression(arg));
                }
                var result = stdlibLookup.lookup(className, methodName, compiledArgs);
                if (result.isPresent()) {
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
            }

            // Dispatch instance methods via TypeMethodRegistry
            {
                var scopeType = resolveExpressionType(scopeExpr);
                if (scopeType instanceof PirType.DataType) scopeType = inferPirType(scope);

                // Compile args and resolve arg types
                var compiledArgs = new ArrayList<PirTerm>();
                var argPirTypes = new ArrayList<PirType>();
                for (var arg : args) {
                    var argPir = generateExpression(arg);
                    compiledArgs.add(argPir);
                    var argType = resolveExpressionType(arg);
                    if (argType instanceof PirType.DataType) argType = inferPirType(argPir);
                    argPirTypes.add(argType);
                }

                var registryResult = typeMethodRegistry.dispatch(scope, methodName, compiledArgs, scopeType, argPirTypes);
                if (registryResult.isPresent()) return registryResult.get();
            }

            return generateFieldAccessFromMethod(scope, methodName, args);
        }

        // Static method call
        var funType = symbolTable.lookup(methodName);
        if (funType.isPresent()) {
            PirTerm fn = new PirTerm.Var(methodName, funType.get());
            for (var arg : args) {
                fn = new PirTerm.App(fn, generateExpression(arg));
            }
            return fn;
        }
        throw new CompilerException("Unknown method: " + methodName
                + ". Did you mean ClassName." + methodName + "()? "
                + "Library methods require class qualification (e.g., MathUtils." + methodName + "(...)).");
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
        // Handle scopeless method calls (static helper methods)
        if (expr instanceof MethodCallExpr mce && mce.getScope().isEmpty()) {
            var methodType = symbolTable.lookup(mce.getNameAsString());
            if (methodType.isPresent()) {
                PirType type = methodType.get();
                while (type instanceof PirType.FunType ft) {
                    type = ft.returnType();
                }
                return type;
            }
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

        // Delegate to TypeMethodRegistry for return type resolution
        var scopeType = resolveExpressionType(scopeExpr);
        var returnType = typeMethodRegistry.resolveReturnType(scopeType, methodName);
        if (returnType.isPresent()) return returnType.get();

        return new PirType.DataType();
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
                default -> throw new CompilerException("Unsupported BigInteger field: " + fae.getNameAsString());
            };
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
        var typeName = oce.getType().getNameAsString();

        // Handle new BigInteger("12345") → integer constant
        if (typeName.equals("BigInteger") && oce.getArguments().size() == 1) {
            var arg = oce.getArguments().get(0);
            if (arg instanceof StringLiteralExpr sle) {
                return new PirTerm.Const(Constant.integer(new BigInteger(sle.getValue())));
            }
            throw new CompilerException("new BigInteger() requires a string literal argument");
        }

        // Check if this is a variant of a sealed interface (sum type)
        var sumType = typeResolver.lookupSumTypeForVariant(typeName);
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

        // Check standalone record type
        var recordType = typeResolver.lookupRecord(typeName);
        if (recordType.isPresent()) {
            var fields = new ArrayList<PirTerm>();
            for (var arg : oce.getArguments()) {
                fields.add(generateExpression(arg));
            }
            return new PirTerm.DataConstr(0, recordType.get(), fields);
        }
        throw new CompilerException("Cannot construct non-record type: " + typeName);
    }

    private PirTerm generateForEachStmt(ForEachStmt fes, List<Statement> followingStmts, int followingIndex) {
        var iterableExpr = generateExpression(fes.getIterable());
        var itemName = fes.getVariable().getVariables().get(0).getNameAsString();

        // Infer element type from the iterable expression
        PirType elemType = new PirType.DataType(); // default
        var iterableJavaExpr = fes.getIterable();
        var iterableType = resolveExpressionType(iterableJavaExpr);
        if (iterableType instanceof PirType.ListType lt) {
            elemType = lt.elemType();
        }

        var desugarer = new LoopDesugarer();
        boolean hasBreak = containsBreak(fes.getBody());

        // Detect all accumulator assignments in the loop body
        var accumulators = detectForEachAccumulators(fes.getBody());

        if (accumulators.size() == 1) {
            // --- Single-accumulator path (unchanged) ---
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

                            String prevAcc = forEachAccumulatorVar;
                            Function<PirTerm, PirTerm> prevBreak = breakContinueFn;
                            forEachAccumulatorVar = accName;
                            breakContinueFn = continueFn;

                            var bodyTerm = generateBreakAwareBody(fes.getBody(), accName, accType, continueFn);

                            forEachAccumulatorVar = prevAcc;
                            breakContinueFn = prevBreak;
                            symbolTable.popScope();
                            return bodyTerm;
                        });
            } else {
                // Normal fold: no break
                symbolTable.pushScope();
                symbolTable.define(itemName, elemType);
                symbolTable.define(accName, accType);

                String prev = forEachAccumulatorVar;
                forEachAccumulatorVar = accName;
                var bodyTerm = generateStatement(fes.getBody());
                forEachAccumulatorVar = prev;

                symbolTable.popScope();

                foldResult = desugarer.desugarForEach(
                        iterableExpr, itemName, accName, accInit, accType, bodyTerm);
            }

            // Rebind accumulator with fold result for following statements
            if (followingIndex + 1 < followingStmts.size()) {
                symbolTable.define(accName, accType);
                var rest = generateStatements(followingStmts, followingIndex + 1);
                return new PirTerm.Let(accName, foldResult, rest);
            }
            return foldResult;

        } else if (accumulators.size() > 1) {
            // --- Multi-accumulator path: pack into a Data list tuple ---
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

                            var prevMultiAcc = multiAccVars;
                            multiAccVars = new LinkedHashSet<>(accNames);

                            var bodyTerm = unpackAccumulators(tupleVar, accNames, accTypesFinal,
                                    generateMultiAccBreakAwareBody(fes.getBody(), accNames, accTypesFinal, continueFn));

                            multiAccVars = prevMultiAcc;
                            symbolTable.popScope();
                            return bodyTerm;
                        });
            } else {
                symbolTable.pushScope();
                symbolTable.define(itemName, elemType);

                var prevMultiAcc = multiAccVars;
                multiAccVars = new LinkedHashSet<>(accumulators);

                var tupleVar = new PirTerm.Var(tupleAccName, tupleAccType);
                var bodyTerm = unpackAccumulators(tupleVar, accumulators, accTypes,
                        generateMultiAccBody(fes.getBody(), accumulators, accTypes));

                multiAccVars = prevMultiAcc;
                symbolTable.popScope();

                foldResult = desugarer.desugarForEach(
                        iterableExpr, itemName, tupleAccName, accInit, tupleAccType, bodyTerm);
            }

            // After loop: unpack final tuple into individual vars for following statements
            if (followingIndex + 1 < followingStmts.size()) {
                for (int i = 0; i < accumulators.size(); i++) {
                    symbolTable.define(accumulators.get(i), accTypes.get(i));
                }
                var rest = generateStatements(followingStmts, followingIndex + 1);
                return unpackAccumulators(foldResult, accumulators, accTypes, rest);
            }
            return foldResult;

        } else {
            // --- Unit-accumulator fallback: for-each with no accumulator ---
            symbolTable.pushScope();
            symbolTable.define(itemName, elemType);
            var bodyTerm = generateStatement(fes.getBody());
            symbolTable.popScope();

            var forEachResult = desugarer.desugarForEach(
                    iterableExpr, itemName, "acc__forEach",
                    new PirTerm.Const(Constant.unit()), new PirType.UnitType(),
                    bodyTerm);

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
                        () -> new CompilerException("Variable must be initialized: " + name));
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

        throw new CompilerException("Unsupported statement in break-aware loop body: " + stmt.getClass().getSimpleName());
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
            // Neither breaks: normal if
            thenTerm = generateStatement(is.getThenStmt());
            elseTerm = is.getElseStmt().map(this::generateStatement)
                    .orElse(new PirTerm.Const(Constant.unit()));
        }

        var ifExpr = new PirTerm.IfThenElse(cond, thenTerm, elseTerm);

        // If there are following statements and neither branch fully terminated
        if (followingIndex + 1 < followingStmts.size()) {
            if (thenBreaks && !elseBreaks && !is.getElseStmt().isPresent()) {
                // Already handled: else path includes remaining stmts
                return ifExpr;
            }
            var rest = generateBreakAwareStatements(followingStmts, followingIndex + 1, accName, accType, continueFn);
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
        if (stmt instanceof BlockStmt bs) {
            return bs.getStatements().stream().anyMatch(this::containsBreak);
        }
        if (stmt instanceof IfStmt is) {
            if (containsBreak(is.getThenStmt())) return true;
            return is.getElseStmt().map(this::containsBreak).orElse(false);
        }
        return false;
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
        }
    }

    private static List<Statement> blockStmts(Statement stmt) {
        if (stmt instanceof BlockStmt bs) return bs.getStatements();
        return List.of(stmt);
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
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                var name = decl.getNameAsString();
                var initExpr = decl.getInitializer().orElseThrow(
                        () -> new CompilerException("Variable must be initialized: " + name));
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
        throw new CompilerException("Unsupported in multi-acc loop body: " + stmt.getClass().getSimpleName());
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
            if (es.getExpression() instanceof VariableDeclarationExpr vde) {
                var decl = vde.getVariable(0);
                var name = decl.getNameAsString();
                var initExpr = decl.getInitializer().orElseThrow(
                        () -> new CompilerException("Variable must be initialized: " + name));
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

        throw new CompilerException("Unsupported in multi-acc break-aware body: " + stmt.getClass().getSimpleName());
    }

    private PirTerm generateWhileStmt(WhileStmt ws, List<Statement> followingStmts, int followingIndex) {
        var condition = generateExpression(ws.getCondition());
        var bodyTerm = generateStatement(ws.getBody());

        var desugarer = new LoopDesugarer();
        var whileResult = desugarer.desugarWhile(condition, bodyTerm);

        if (followingIndex + 1 < followingStmts.size()) {
            var rest = generateStatements(followingStmts, followingIndex + 1);
            return new PirTerm.Let("_while", whileResult, rest);
        }
        return whileResult;
    }

    private PirTerm generateSwitchExpr(SwitchExpr se) {
        var selector = generateExpression(se.getSelector());
        // Determine the sum type from the selector's type
        var selectorType = inferPirType(selector);
        if (!(selectorType instanceof PirType.SumType sumType)) {
            throw new CompilerException("switch expression requires a sealed interface type, got: " + selectorType);
        }

        var desugarer = new PatternMatchDesugarer(typeResolver);
        var matchEntries = new ArrayList<PatternMatchDesugarer.MatchEntry>();

        for (var entry : se.getEntries()) {
            if (entry.isDefault()) {
                // default branch — skip for now
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
                        throw new CompilerException("Unknown variant in switch: " + typeName);
                    }
                    var ctor = ctorOpt.get();

                    symbolTable.pushScope();
                    // Define the field bindings in scope
                    for (var field : ctor.fields()) {
                        symbolTable.define(field.name(), field.type());
                    }

                    // Generate body from the entry's statements
                    PirTerm bodyTerm = generateSwitchEntryBody(entry);

                    symbolTable.popScope();
                    // For DataMatch, we bind the constructor fields
                    matchEntries.add(new PatternMatchDesugarer.MatchEntry(
                            typeName, ctor.fields().stream().map(PirType.Field::name).toList(), bodyTerm));
                }
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
                return rs.getExpression().map(this::generateExpression)
                        .orElse(new PirTerm.Const(Constant.unit()));
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
        throw new CompilerException("Unsupported instanceof pattern: " + ioe);
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
            throw new CompilerException("Unsupported lambda body: " + lambdaBody.getClass().getSimpleName());
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
        return new PirType.DataType();
    }

    private PirType inferBuiltinReturnType(DefaultFun fun) {
        return switch (fun) {
            case AddInteger, SubtractInteger, MultiplyInteger, DivideInteger,
                 QuotientInteger, RemainderInteger, ModInteger,
                 LengthOfByteString -> new PirType.IntegerType();
            case EqualsInteger, LessThanInteger, LessThanEqualsInteger,
                 EqualsByteString, LessThanByteString, LessThanEqualsByteString,
                 EqualsString, EqualsData, NullList -> new PirType.BoolType();
            case AppendByteString, SliceByteString, ConsByteString,
                 Sha2_256, Sha3_256, Blake2b_256, EncodeUtf8 -> new PirType.ByteStringType();
            case AppendString, DecodeUtf8 -> new PirType.StringType();
            default -> new PirType.DataType();
        };
    }

    private static PirTerm builtinApp2(DefaultFun fun, PirTerm a, PirTerm b) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(fun), a),
                b);
    }
}
