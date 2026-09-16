package org.julclang.compiler.pir;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.resolve.LibraryMethodRegistry;
import org.julclang.compiler.resolve.SymbolTable;
import org.julclang.compiler.resolve.TypeResolver;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.source.SourceLocation;
import com.github.javaparser.ast.expr.*;
import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;

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
    /** Lowered {@code JulcArray.of(...)} terms and the source types of their elements (ADR-046). */
    private final IdentityHashMap<PirTerm, List<PirType>> arrayLiterals = new IdentityHashMap<>();
    /**
     * Lowered {@code JulcList.of(...)} terms and the source types of their elements: read only to
     * type a list literal that is an element of an array literal, so that {@code var rows =
     * JulcArray.of(JulcList.of(1))} is {@code JulcArray<JulcList<BigInteger>>} as javac types it.
     * A {@code var} local of a bare list literal keeps its existing {@code JulcList<PlutusData>}
     * typing (a change of that convention is a decision of its own).
     */
    private final IdentityHashMap<PirTerm, List<PirType>> listLiterals = new IdentityHashMap<>();

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
            return typeResolver.resolveNamed(
                    symbolTable.lookup(ne.getNameAsString()).orElse(new PirType.DataType()));
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

        // JulcArray.of(a, b, ...): the element type the elements resolve to, when every element
        // resolves to the same one (javac's inference for a `var` local or a chained access);
        // otherwise unknown here, and the declaration reads the encodings of the generated
        // literal (ADR-046).
        if (scopeExpr instanceof NameExpr ne && methodName.equals("of") && isJulcArray(ne.getNameAsString())) {
            PirType common = null;
            for (var arg : mce.getArguments()) {
                var argType = typeResolver.resolveNamed(resolveExpressionType(arg));
                if (argType instanceof PirType.DataType || (common != null && !common.equals(argType))) {
                    common = null;
                    break;
                }
                common = argType;
            }
            if (common != null) return new PirType.ArrayType(common);
        }

        // If scope is a variable with RecordType, return the field type
        if (scopeExpr instanceof NameExpr ne && mce.getArguments().isEmpty()) {
            var fieldType = resolveRecordFieldType(ne.getNameAsString(), methodName);
            if (fieldType.isPresent()) return fieldType.get();
        }

        // If scope is itself a method call, resolve recursively
        if (scopeExpr instanceof MethodCallExpr innerMce && mce.getArguments().isEmpty()) {
            var innerType = resolveMethodCallReturnType(innerMce);
            innerType = typeResolver.resolveNamed(innerType);
            if (innerType instanceof PirType.RecordType rt) {
                for (var field : rt.fields()) {
                    if (field.name().equals(methodName)) {
                        return typeResolver.resolveNamed(field.type());
                    }
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
        scopeType = typeResolver.resolveNamed(scopeType);
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
        type = typeResolver.resolveNamed(type);
        if (!(type instanceof PirType.RecordType rt)) return java.util.Optional.empty();

        for (var field : rt.fields()) {
            if (field.name().equals(fieldName)) {
                return java.util.Optional.of(typeResolver.resolveNamed(field.type()));
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Infer the type of a variable declaration — from explicit type or 'var' inference.
     */
    PirType inferType(com.github.javaparser.ast.type.Type declType, PirTerm initValue,
                       Expression initExpr) {
        return inferType(declType, initValue, initExpr, null);
    }

    /**
     * As {@link #inferType(com.github.javaparser.ast.type.Type, PirTerm, Expression)};
     * {@code location} names the declaration in the diagnostic raised when {@code var} cannot
     * give an array literal a single element type.
     */
    PirType inferType(com.github.javaparser.ast.type.Type declType, PirTerm initValue,
                       Expression initExpr, SourceLocation location) {
        if (!(declType instanceof com.github.javaparser.ast.type.VarType)) {
            return typeResolver.resolve(declType);
        }
        var exprType = resolveExpressionType(initExpr);
        if (!(exprType instanceof PirType.DataType)) {
            return exprType;
        }
        // `var a = JulcArray.of(...)`: javac types the local by its elements (ADR-046); the
        // generator recorded their source types on the lowered literal. Elements of different
        // types would need a common supertype the subset cannot represent, and the access needs
        // one element type for its decode: fail closed rather than type them Data.
        var elementTypes = arrayLiteralElementTypes(initValue);
        if (elementTypes.isPresent() && new LinkedHashSet<>(elementTypes.get()).size() > 1) {
            throw CompilerTypeDiagnostics.arrayLiteralElementTypesDiffer(elementTypes.get(), location);
        }
        return inferPirType(initValue);
    }

    /**
     * The element types of an array literal ({@code JulcArray.of(...)}) as the generator lowered
     * it: the source types of its arguments, recorded by {@link #recordArrayLiteral} on the
     * lowered term. They are the types {@link PirHelpers#wrapEncode} encoded the elements with,
     * so an access typed by them decodes exactly what was encoded; the encodings themselves are
     * not read back, since {@code IData(x)} is also what a user's {@code Builtins.iData(x)}
     * lowers to. Empty when the term is not a recorded literal.
     */
    java.util.Optional<List<PirType>> arrayLiteralElementTypes(PirTerm term) {
        return java.util.Optional.ofNullable(arrayLiterals.get(term));
    }

    /** Record the source element types of a lowered {@code JulcArray.of(...)} term (identity). */
    void recordArrayLiteral(PirTerm term, List<PirType> elementTypes) {
        arrayLiterals.put(term, List.copyOf(elementTypes));
    }

    /** Record the source element types of a lowered {@code JulcList.of(...)} term (identity). */
    void recordListLiteral(PirTerm term, List<PirType> elementTypes) {
        listLiterals.put(term, List.copyOf(elementTypes));
    }

    /**
     * The source types of a literal's arguments, each list literal among them typed by its own
     * recorded elements ({@code JulcList<T>} rather than the {@code JulcList<PlutusData>} the
     * expression resolves to), recursively, so nested literals keep their Java types.
     */
    List<PirType> literalElementTypes(List<PirType> argTypes, List<PirTerm> args) {
        var types = new ArrayList<PirType>(argTypes.size());
        for (int i = 0; i < argTypes.size(); i++) {
            var recorded = i < args.size() ? listLiterals.get(args.get(i)) : null;
            if (recorded != null && argTypes.get(i) instanceof PirType.ListType) {
                var distinct = new LinkedHashSet<>(recorded);
                types.add(new PirType.ListType(distinct.size() == 1 ? distinct.iterator().next() : new PirType.DataType()));
            } else {
                types.add(argTypes.get(i));
            }
        }
        return types;
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
                case Constant.ValueConst _ -> new PirType.NativeValueType();
                default -> new PirType.DataType();
            };
        }
        if (term instanceof PirTerm.App app) {
            // An array literal is typed by the source types of its elements (ADR-046).
            var elementTypes = arrayLiteralElementTypes(term);
            if (elementTypes.isPresent()) {
                var distinct = new LinkedHashSet<>(elementTypes.get());
                return new PirType.ArrayType(distinct.size() == 1 ? distinct.iterator().next() : new PirType.DataType());
            }
            PirTerm fn = app.function();
            if (fn instanceof PirTerm.Builtin b) {
                if (b.fun() == DefaultFun.FstPair
                        && app.argument() instanceof PirTerm.App argApp
                        && argApp.function() instanceof PirTerm.Builtin argB
                        && argB.fun() == DefaultFun.UnConstrData) {
                    return new PirType.IntegerType();
                }
                return inferBuiltinReturnType(b.fun());
            }
            var root = fn;
            while (root instanceof PirTerm.App nested) {
                root = nested.function();
            }
            if (root instanceof PirTerm.Builtin builtin) {
                return inferBuiltinReturnType(builtin.fun());
            }
            var fnType = inferPirType(fn);
            if (fnType instanceof PirType.FunType ft) {
                return ft.returnType();
            }
        }
        if (term instanceof PirTerm.Var v) {
            return v.type();
        }
        if (term instanceof PirTerm.PairMatch m) {
            return m.body() instanceof PirTerm.Error error ? error.type() : inferPirType(m.body());
        }
        if (term instanceof PirTerm.IntegerCase c) {
            // Every branch produces the same result type; prefer a non-Error branch.
            for (var branch : c.branches()) {
                if (!(branch instanceof PirTerm.Error)) return inferPirType(branch);
            }
            return ((PirTerm.Error) c.branches().getFirst()).type();
        }
        if (term instanceof PirTerm.ListMatch m) {
            // The guarded for-each producer uses a typed Error for unreachable nil.
            return m.nilBranch() instanceof PirTerm.Error error
                    ? error.type() : inferPirType(m.nilBranch());
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
                 IntegerToByteString,
                 Bls12_381_G1_compress, Bls12_381_G2_compress,
                 Bls12_381_G1_add, Bls12_381_G1_neg, Bls12_381_G1_scalarMul,
                 Bls12_381_G1_hashToGroup, Bls12_381_G1_uncompress,
                 Bls12_381_G2_add, Bls12_381_G2_neg, Bls12_381_G2_scalarMul,
                 Bls12_381_G2_hashToGroup, Bls12_381_G2_uncompress,
                 Bls12_381_millerLoop, Bls12_381_mulMlResult,
                 Bls12_381_G1_multiScalarMul, Bls12_381_G2_multiScalarMul -> new PirType.ByteStringType();
            case AppendString, DecodeUtf8 -> new PirType.StringType();
            case InsertCoin, UnionValue, UnValueData, ScaleValue ->
                    new PirType.NativeValueType();
            case UnListData, TailList, MkCons, MkNilData,
                 DropList, MultiIndexArray -> new PirType.ListType(new PirType.DataType());
            case UnMapData, MkNilPairData -> new PirType.MapType(new PirType.DataType(), new PirType.DataType());
            case ListToArray -> new PirType.ArrayType(new PirType.DataType());
            default -> new PirType.DataType();
        };
    }

    private boolean isJulcArray(String name) {
        return name.equals("JulcArray") || "org.julclang.core.types.JulcArray".equals(typeResolver.resolveClassName(name));
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
