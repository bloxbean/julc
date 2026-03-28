package com.bloxbean.cardano.julc.compiler.pir;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.resolve.LibraryMethodRegistry;
import com.bloxbean.cardano.julc.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.julc.compiler.resolve.TypeResolver;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.github.javaparser.ast.expr.*;

/**
 * Read-only type inference utilities for PIR generation.
 * <p>
 * Queries types from the symbol table, type resolver, stdlib lookup,
 * and type method registry without mutating any state.
 */
final class TypeInferenceHelper {

    private final SymbolTable symbolTable;
    private final TypeResolver typeResolver;
    private final StdlibLookup stdlibLookup;
    private final TypeMethodRegistry typeMethodRegistry;

    TypeInferenceHelper(SymbolTable symbolTable, TypeResolver typeResolver,
                        StdlibLookup stdlibLookup, TypeMethodRegistry typeMethodRegistry) {
        this.symbolTable = symbolTable;
        this.typeResolver = typeResolver;
        this.stdlibLookup = stdlibLookup;
        this.typeMethodRegistry = typeMethodRegistry;
    }

    /**
     * Resolve the PirType of a JavaParser expression without generating PIR.
     */
    PirType resolveExpressionType(Expression expr) {
        if (expr instanceof NameExpr ne) {
            return symbolTable.lookup(ne.getNameAsString()).orElse(new PirType.DataType());
        }
        if (expr instanceof MethodCallExpr mce && mce.getScope().isPresent()) {
            return resolveMethodCallReturnType(mce);
        }
        if (expr instanceof ObjectCreationExpr oce) {
            var resolved = typeResolver.resolve(oce.getType());
            if (!(resolved instanceof PirType.DataType)) return resolved;
        }
        if (expr instanceof MethodCallExpr mce && mce.getScope().isEmpty()) {
            var methodType = symbolTable.lookup(mce.getNameAsString());
            if (methodType.isPresent()) {
                return extractReturnType(methodType.get());
            }
        }
        if (expr instanceof IntegerLiteralExpr || expr instanceof LongLiteralExpr)
            return new PirType.IntegerType();
        if (expr instanceof StringLiteralExpr) return new PirType.StringType();
        if (expr instanceof BooleanLiteralExpr) return new PirType.BoolType();
        if (expr instanceof CastExpr ce) {
            try {
                var castType = typeResolver.resolve(ce.getType());
                if (!(castType instanceof PirType.DataType)) return castType;
            } catch (IllegalArgumentException | CompilerException _) {
            }
            return resolveExpressionType(ce.getExpression());
        }
        return new PirType.DataType();
    }

    /**
     * Infer the return type of a method call expression (for chained access).
     */
    PirType resolveMethodCallReturnType(MethodCallExpr mce) {
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

        // If scope is itself a method call, resolve recursively
        if (scopeExpr instanceof MethodCallExpr innerMce && mce.getArguments().isEmpty()) {
            var innerType = resolveMethodCallReturnType(innerMce);
            if (innerType instanceof PirType.RecordType rt) {
                for (var field : rt.fields()) {
                    if (field.name().equals(methodName)) return field.type();
                }
            }
            if (innerType instanceof PirType.ListType && methodName.equals("tail")) {
                return innerType;
            }
        }

        // Static library method call — look up return type from registry
        if (scopeExpr instanceof NameExpr ne) {
            var registry = findLibraryRegistry(stdlibLookup);
            if (registry != null) {
                var resolvedClassName = typeResolver.resolveClassName(ne.getNameAsString());
                var qualifiedKey = resolvedClassName + "." + methodName;
                var libMethod = registry.lookupMethod(qualifiedKey);
                if (libMethod.isPresent()) {
                    return extractReturnType(libMethod.get().type());
                }
                var simpleKey = ne.getNameAsString() + "." + methodName;
                if (!simpleKey.equals(qualifiedKey)) {
                    libMethod = registry.lookupMethod(simpleKey);
                    if (libMethod.isPresent()) {
                        return extractReturnType(libMethod.get().type());
                    }
                }
            }
        }

        // TypeMethodRegistry return type resolution
        var scopeType = resolveExpressionType(scopeExpr);
        var returnType = typeMethodRegistry.resolveReturnType(scopeType, methodName);
        if (returnType.isPresent()) return returnType.get();

        return new PirType.DataType();
    }

    /**
     * Extract the return type from a FunType chain.
     */
    static PirType extractReturnType(PirType type) {
        while (type instanceof PirType.FunType ft) {
            type = ft.returnType();
        }
        return type;
    }

    /**
     * Resolve the PirType of a record field without generating extraction PIR.
     */
    java.util.Optional<PirType> resolveRecordFieldType(String varName, String fieldName) {
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

    /**
     * Infer the type of a variable declaration — from explicit type or 'var' inference.
     */
    PirType inferType(com.github.javaparser.ast.type.Type declType, PirTerm initValue,
                       Expression initExpr) {
        if (!(declType instanceof com.github.javaparser.ast.type.VarType)) {
            return typeResolver.resolve(declType);
        }
        var exprType = resolveExpressionType(initExpr);
        if (!(exprType instanceof PirType.DataType)) {
            return exprType;
        }
        return inferPirType(initValue);
    }

    /**
     * Infer the PirType of a PIR term by structural analysis.
     */
    PirType inferPirType(PirTerm term) {
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
            PirTerm fn = app.function();
            if (fn instanceof PirTerm.App innerApp && innerApp.function() instanceof PirTerm.Builtin b) {
                return inferBuiltinReturnType(b.fun());
            }
            if (fn instanceof PirTerm.Builtin b) {
                if (b.fun() == DefaultFun.FstPair
                        && app.argument() instanceof PirTerm.App argApp
                        && argApp.function() instanceof PirTerm.Builtin argB
                        && argB.fun() == DefaultFun.UnConstrData) {
                    return new PirType.IntegerType();
                }
                return inferBuiltinReturnType(b.fun());
            }
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

    /**
     * Infer the return type of a UPLC builtin function.
     */
    PirType inferBuiltinReturnType(DefaultFun fun) {
        return switch (fun) {
            case AddInteger, SubtractInteger, MultiplyInteger, DivideInteger,
                 QuotientInteger, RemainderInteger, ModInteger,
                 LengthOfByteString, ByteStringToInteger, UnIData,
                 LengthOfArray, LookupCoin -> new PirType.IntegerType();
            case EqualsInteger, LessThanInteger, LessThanEqualsInteger,
                 EqualsByteString, LessThanByteString, LessThanEqualsByteString,
                 EqualsString, EqualsData, NullList,
                 Bls12_381_G1_equal, Bls12_381_G2_equal, Bls12_381_finalVerify,
                 ValueContains -> new PirType.BoolType();
            case AppendByteString, SliceByteString, ConsByteString,
                 Sha2_256, Sha3_256, Blake2b_256, EncodeUtf8, UnBData,
                 Bls12_381_G1_compress, Bls12_381_G2_compress,
                 Bls12_381_G1_add, Bls12_381_G1_neg, Bls12_381_G1_scalarMul,
                 Bls12_381_G1_hashToGroup, Bls12_381_G1_uncompress,
                 Bls12_381_G2_add, Bls12_381_G2_neg, Bls12_381_G2_scalarMul,
                 Bls12_381_G2_hashToGroup, Bls12_381_G2_uncompress,
                 Bls12_381_millerLoop, Bls12_381_mulMlResult,
                 Bls12_381_G1_multiScalarMul, Bls12_381_G2_multiScalarMul -> new PirType.ByteStringType();
            case AppendString, DecodeUtf8 -> new PirType.StringType();
            case UnListData, TailList, MkCons, MkNilData,
                 DropList, MultiIndexArray -> new PirType.ListType(new PirType.DataType());
            case UnMapData, MkNilPairData -> new PirType.MapType(new PirType.DataType(), new PirType.DataType());
            case ListToArray -> new PirType.ArrayType(new PirType.DataType());
            default -> new PirType.DataType();
        };
    }

    /** Find a LibraryMethodRegistry within a StdlibLookup chain. */
    static LibraryMethodRegistry findLibraryRegistry(StdlibLookup lookup) {
        if (lookup instanceof LibraryMethodRegistry r) return r;
        if (lookup instanceof CompositeStdlibLookup composite) {
            for (var inner : composite.getLookups()) {
                if (inner instanceof LibraryMethodRegistry r) return r;
            }
        }
        return null;
    }
}
