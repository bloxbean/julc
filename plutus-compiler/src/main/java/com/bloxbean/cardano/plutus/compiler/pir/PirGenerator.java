package com.bloxbean.cardano.plutus.compiler.pir;

import com.bloxbean.cardano.plutus.compiler.CompilerException;
import com.bloxbean.cardano.plutus.compiler.desugar.LoopDesugarer;
import com.bloxbean.cardano.plutus.compiler.desugar.PatternMatchDesugarer;
import com.bloxbean.cardano.plutus.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.plutus.compiler.resolve.TypeResolver;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles JavaParser AST to PIR terms.
 * Handles expressions, statements, and method bodies.
 */
public class PirGenerator {

    private final TypeResolver typeResolver;
    private final SymbolTable symbolTable;
    private final StdlibLookup stdlibLookup;

    public PirGenerator(TypeResolver typeResolver, SymbolTable symbolTable) {
        this(typeResolver, symbolTable, null);
    }

    public PirGenerator(TypeResolver typeResolver, SymbolTable symbolTable, StdlibLookup stdlibLookup) {
        this.typeResolver = typeResolver;
        this.symbolTable = symbolTable;
        this.stdlibLookup = stdlibLookup;
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
        throw new CompilerException("Unsupported expression: " + expr.getClass().getSimpleName() + " = " + expr);
    }

    private PirTerm generateBinaryExpr(BinaryExpr be) {
        var left = generateExpression(be.getLeft());
        var right = generateExpression(be.getRight());
        return switch (be.getOperator()) {
            case PLUS -> builtinApp2(DefaultFun.AddInteger, left, right);
            case MINUS -> builtinApp2(DefaultFun.SubtractInteger, left, right);
            case MULTIPLY -> builtinApp2(DefaultFun.MultiplyInteger, left, right);
            case DIVIDE -> builtinApp2(DefaultFun.DivideInteger, left, right);
            case REMAINDER -> builtinApp2(DefaultFun.RemainderInteger, left, right);
            case EQUALS -> builtinApp2(DefaultFun.EqualsInteger, left, right);
            case NOT_EQUALS -> new PirTerm.IfThenElse(
                    builtinApp2(DefaultFun.EqualsInteger, left, right),
                    new PirTerm.Const(Constant.bool(false)),
                    new PirTerm.Const(Constant.bool(true)));
            case LESS -> builtinApp2(DefaultFun.LessThanInteger, left, right);
            case LESS_EQUALS -> builtinApp2(DefaultFun.LessThanEqualsInteger, left, right);
            case GREATER -> builtinApp2(DefaultFun.LessThanInteger, right, left); // swap
            case GREATER_EQUALS -> builtinApp2(DefaultFun.LessThanEqualsInteger, right, left); // swap
            case AND -> new PirTerm.IfThenElse(left, right, new PirTerm.Const(Constant.bool(false)));
            case OR -> new PirTerm.IfThenElse(left, new PirTerm.Const(Constant.bool(true)), right);
            default -> throw new CompilerException("Unsupported operator: " + be.getOperator());
        };
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

            // Check if scope is a list variable for instance method dispatch
            var listMethodResult = tryListMethodOnExpr(scopeExpr, scope, methodName, args);
            if (listMethodResult != null) {
                return listMethodResult;
            }

            // Handle .equals() on integer/bytestring types → EqualsInteger / EqualsByteString
            if (methodName.equals("equals") && args.size() == 1) {
                var scopeType = resolveExpressionType(scopeExpr);
                if (scopeType instanceof PirType.DataType) {
                    // Fallback: try inferring from the generated PIR
                    scopeType = inferPirType(scope);
                }
                if (scopeType instanceof PirType.IntegerType) {
                    var argPir = generateExpression(args.get(0));
                    var argType = resolveExpressionType(args.get(0));
                    if (argType instanceof PirType.DataType) argType = inferPirType(argPir);
                    // Coerce Data argument to Integer if needed
                    if (!(argType instanceof PirType.IntegerType)) {
                        argPir = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), argPir);
                    }
                    return builtinApp2(DefaultFun.EqualsInteger, scope, argPir);
                }
                if (scopeType instanceof PirType.ByteStringType) {
                    var argPir = generateExpression(args.get(0));
                    var argType = resolveExpressionType(args.get(0));
                    if (argType instanceof PirType.DataType) argType = inferPirType(argPir);
                    // Coerce Data argument to ByteString if needed
                    if (!(argType instanceof PirType.ByteStringType)) {
                        argPir = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), argPir);
                    }
                    return builtinApp2(DefaultFun.EqualsByteString, scope, argPir);
                }
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
        throw new CompilerException("Unknown method: " + methodName);
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

    /**
     * Wrap a raw Data term with the appropriate decode builtin for the target type.
     */
    private PirTerm wrapDecode(PirTerm data, PirType targetType) {
        if (targetType instanceof PirType.IntegerType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), data);
        }
        if (targetType instanceof PirType.ByteStringType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), data);
        }
        if (targetType instanceof PirType.ListType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnListData), data);
        }
        if (targetType instanceof PirType.MapType) {
            return new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnMapData), data);
        }
        if (targetType instanceof PirType.BoolType) {
            // Bool encoded as Constr: False=Constr(0,[]), True=Constr(1,[])
            // Decode: FstPair(UnConstrData(data)) == 1
            var tag = new PirTerm.App(new PirTerm.Builtin(DefaultFun.FstPair),
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnConstrData), data));
            return builtinApp2(DefaultFun.EqualsInteger, tag,
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        }
        // For Data, RecordType, SumType, etc., pass through as raw Data
        return data;
    }

    /**
     * Try to dispatch a list instance method on the given expression.
     * Resolves the PirType of the scope expression and checks if it's a ListType.
     * Returns null if the scope is not a list or the method is not a list method.
     */
    private PirTerm tryListMethodOnExpr(Expression scopeExpr, PirTerm scope, String methodName,
                                         com.github.javaparser.ast.NodeList<Expression> args) {
        var scopeType = resolveExpressionType(scopeExpr);
        if (scopeType instanceof PirType.ListType lt) {
            return generateListMethod(scope, methodName, args, lt);
        }
        return null;
    }

    /**
     * Generate PIR for a list instance method call.
     * Returns null if the method is not a recognized list method.
     */
    private PirTerm generateListMethod(PirTerm list, String method,
                                        com.github.javaparser.ast.NodeList<Expression> args,
                                        PirType.ListType listType) {
        return switch (method) {
            case "size" -> generateListLength(list);
            case "isEmpty" -> new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), list);
            case "head" -> wrapDecode(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), list),
                    listType.elemType());
            case "contains" -> {
                if (args.isEmpty()) throw new CompilerException("contains() requires one argument");
                var targetExpr = args.get(0);
                var targetType = resolveExpressionType(targetExpr);
                yield generateListContains(list, generateExpression(targetExpr), listType.elemType(), targetType);
            }
            default -> null;
        };
    }

    /**
     * Generate PIR for list.size() — foldl(\acc _ -> acc + 1, 0, list).
     */
    private PirTerm generateListLength(PirTerm list) {
        var accVar = new PirTerm.Var("acc__len", new PirType.IntegerType());
        var xVar = new PirTerm.Var("_x__len", new PirType.DataType());
        var addOne = builtinApp2(DefaultFun.AddInteger, accVar,
                new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        var foldFn = new PirTerm.Lam("acc__len", new PirType.IntegerType(),
                new PirTerm.Lam("_x__len", new PirType.DataType(), addOne));
        return generateFoldl(foldFn, new PirTerm.Const(Constant.integer(BigInteger.ZERO)), list);
    }

    /**
     * Generate PIR for list.contains(target) — recursive search with typed equality.
     *
     * @param list       PIR term for the builtin list
     * @param target     PIR term for the element to search for
     * @param elemType   the element type of the list (determines equality comparison)
     * @param targetType the actual type of the target argument (may be decoded already)
     */
    private PirTerm generateListContains(PirTerm list, PirTerm target,
                                          PirType elemType, PirType targetType) {
        var lstVar = new PirTerm.Var("lst_c", new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var("go_c", new PirType.FunType(
                new PirType.ListType(new PirType.DataType()), new PirType.BoolType()));
        var targetVar = new PirTerm.Var("target_c", targetType);
        var listVar = new PirTerm.Var("list_c", new PirType.ListType(new PirType.DataType()));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);
        var hVar = new PirTerm.Var("h_c", new PirType.DataType());

        // Build equality: decode list element, but only decode target if it's still Data
        PirTerm equalCheck = buildContainsEquality(hVar, targetVar, elemType, targetType);

        var recurse = new PirTerm.App(goVar, tailExpr);
        var innerIf = new PirTerm.IfThenElse(equalCheck,
                new PirTerm.Const(Constant.bool(true)), recurse);
        var letHead = new PirTerm.Let("h_c", headExpr, innerIf);
        var outerIf = new PirTerm.IfThenElse(nullCheck,
                new PirTerm.Const(Constant.bool(false)), letHead);

        var goBody = new PirTerm.Lam("lst_c", new PirType.ListType(new PirType.DataType()), outerIf);
        var binding = new PirTerm.Binding("go_c", goBody);

        var search = new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(goVar, listVar));

        return new PirTerm.Let("target_c", target,
                new PirTerm.Let("list_c", list, search));
    }

    /**
     * Build equality check for contains(). Always decodes the list element (it's raw Data).
     * Only decodes the target if it's still Data (not already decoded by field extraction).
     */
    private PirTerm buildContainsEquality(PirTerm listElem, PirTerm target,
                                           PirType elemType, PirType targetType) {
        if (elemType instanceof PirType.ByteStringType) {
            var decodedElem = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), listElem);
            // If target is already decoded to ByteString, don't decode again
            var decodedTarget = (targetType instanceof PirType.ByteStringType) ?
                    target :
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnBData), target);
            return builtinApp2(DefaultFun.EqualsByteString, decodedElem, decodedTarget);
        }
        if (elemType instanceof PirType.IntegerType) {
            var decodedElem = new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), listElem);
            var decodedTarget = (targetType instanceof PirType.IntegerType) ?
                    target :
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.UnIData), target);
            return builtinApp2(DefaultFun.EqualsInteger, decodedElem, decodedTarget);
        }
        // Default: EqualsData — both are Data
        return builtinApp2(DefaultFun.EqualsData, listElem, target);
    }

    /**
     * Generate PIR foldl using LetRec — same pattern as ListsLib.foldl.
     */
    private PirTerm generateFoldl(PirTerm f, PirTerm init, PirTerm list) {
        var accVar = new PirTerm.Var("acc__f", new PirType.DataType());
        var lstVar = new PirTerm.Var("lst__f", new PirType.ListType(new PirType.DataType()));
        var goVar = new PirTerm.Var("go__f", new PirType.FunType(new PirType.DataType(),
                new PirType.FunType(new PirType.ListType(new PirType.DataType()), new PirType.DataType())));

        var nullCheck = new PirTerm.App(new PirTerm.Builtin(DefaultFun.NullList), lstVar);
        var headExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.HeadList), lstVar);
        var tailExpr = new PirTerm.App(new PirTerm.Builtin(DefaultFun.TailList), lstVar);

        var fApp = new PirTerm.App(new PirTerm.App(f, accVar), headExpr);
        var recurse = new PirTerm.App(new PirTerm.App(goVar, fApp), tailExpr);

        var ifExpr = new PirTerm.IfThenElse(nullCheck, accVar, recurse);

        var goBody = new PirTerm.Lam("acc__f", new PirType.DataType(),
                new PirTerm.Lam("lst__f", new PirType.ListType(new PirType.DataType()), ifExpr));
        var binding = new PirTerm.Binding("go__f", goBody);

        return new PirTerm.LetRec(List.of(binding),
                new PirTerm.App(new PirTerm.App(goVar, init), list));
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
        }

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
        return new PirTerm.App(
                new PirTerm.Var("." + fieldName, new PirType.DataType()),
                scope);
    }

    private PirTerm generateObjectCreation(ObjectCreationExpr oce) {
        var typeName = oce.getType().getNameAsString();

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

        symbolTable.pushScope();
        symbolTable.define(itemName, elemType);
        var bodyTerm = generateStatement(fes.getBody());
        symbolTable.popScope();

        // Simple desugaring: for-each becomes fold that discards items
        // The body becomes a function applied to each element
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
            if (fn instanceof PirTerm.App innerApp && innerApp.function() instanceof PirTerm.Builtin b) {
                return inferBuiltinReturnType(b.fun());
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
                 QuotientInteger, RemainderInteger, ModInteger -> new PirType.IntegerType();
            case EqualsInteger, LessThanInteger, LessThanEqualsInteger,
                 EqualsByteString, LessThanByteString, LessThanEqualsByteString,
                 EqualsString, NullList -> new PirType.BoolType();
            case AppendByteString, SliceByteString, ConsByteString,
                 Sha2_256, Sha3_256, Blake2b_256 -> new PirType.ByteStringType();
            case AppendString -> new PirType.StringType();
            default -> new PirType.DataType();
        };
    }

    private static PirTerm builtinApp2(DefaultFun fun, PirTerm a, PirTerm b) {
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(fun), a),
                b);
    }
}
