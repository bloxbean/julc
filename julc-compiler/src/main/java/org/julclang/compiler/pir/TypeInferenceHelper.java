package org.julclang.compiler.pir;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.resolve.LibraryMethodRegistry;
import org.julclang.compiler.resolve.SymbolTable;
import org.julclang.compiler.resolve.TypeResolver;
import org.julclang.core.DefaultFun;
import org.julclang.core.source.SourceLocation;
import com.github.javaparser.ast.expr.*;
import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

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
    /** What a term's own structure, or a typed dispatch, says of its type. */
    private final TermTypes termTypes = new TermTypes();
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
            // A diamond tuple's component types are inferred while it is generated (#299): the
            // lowered term carries them, so the caller falls back to the term's type.
            if (isDiamondTuple(oce)) return new PirType.DataType();
            var resolved = typeResolver.resolve(oce.getType());
            if (!(resolved instanceof PirType.DataType)) return resolved;
        }
        if (expr instanceof MethodCallExpr mce && mce.getScope().isEmpty()) {
            var methodType = symbolTable.lookupMethodSignature(mce.getNameAsString())
                    .map(SymbolTable.MethodSignature::type);
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

    static boolean isDiamondTuple(ObjectCreationExpr oce) {
        var type = oce.getType();
        return (type.getNameAsString().equals("Tuple2") || type.getNameAsString().equals("Tuple3"))
                && type.getTypeArguments().map(List::isEmpty).orElse(false);
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
        return termTypes.arrayLiteralElementTypes(term);
    }

    /** Record the source element types of a lowered {@code JulcArray.of(...)} term (identity). */
    void recordArrayLiteral(PirTerm term, List<PirType> elementTypes) {
        termTypes.recordArrayLiteral(term, elementTypes);
    }

    /** Record the type the registry declares for a term produced by a typed dispatch (identity). */
    void recordTermType(PirTerm term, PirType type) {
        termTypes.record(term, type);
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
     * The type of a lowered {@code switch} expression (ADR-068 D6, review F710): the type its arms
     * agree on, read through the matches, conditionals and bindings the lowering wraps them in.
     * Arms that are all Data-shaped (Data, records, sealed values, {@code Optional}) but differ give
     * {@code DataType}, which is their encoding; arms that otherwise disagree give null, an unknown
     * type. {@code inferPirType} alone reads no further than the outer match.
     */
    PirType switchResultType(PirTerm term) {
        var leaves = new LinkedHashSet<PirType>();
        collectArmTypes(term, leaves);
        if (leaves.isEmpty()) return new PirType.DataType();
        if (leaves.size() == 1) return leaves.iterator().next();
        return leaves.stream().allMatch(TypeInferenceHelper::isDataShaped) ? new PirType.DataType() : null;
    }

    private void collectArmTypes(PirTerm term, Set<PirType> leaves) {
        switch (term) {
            case PirTerm.Error _ -> { }
            case PirTerm.DataMatch m -> m.branches().forEach(b -> collectArmTypes(b.body(), leaves));
            case PirTerm.IntegerCase c -> c.branches().forEach(b -> collectArmTypes(b, leaves));
            case PirTerm.IfThenElse ite -> {
                collectArmTypes(ite.thenBranch(), leaves);
                collectArmTypes(ite.elseBranch(), leaves);
            }
            case PirTerm.Let let -> collectArmTypes(let.body(), leaves);
            case PirTerm.LetRec letRec -> collectArmTypes(letRec.body(), leaves);
            case PirTerm.PairMatch m -> collectArmTypes(m.body(), leaves);
            case PirTerm.ListMatch m -> {
                collectArmTypes(m.nilBranch(), leaves);
                collectArmTypes(m.consBranch(), leaves);
            }
            default -> leaves.add(typeResolver.resolveNamed(inferPirType(term)));
        }
    }

    /** A type whose values are already Data, so that encoding leaves them as they are. */
    static boolean isDataShaped(PirType type) {
        return type instanceof PirType.DataType || type instanceof PirType.RecordType
                || type instanceof PirType.SumType || type instanceof PirType.OptionalType
                || type instanceof PirType.NamedTypeRef;
    }

    /** The PIR type of a term by its structure: {@link TermTypes#infer}. */
    PirType inferPirType(PirTerm term) {
        return termTypes.infer(term);
    }

    PirType inferBuiltinReturnType(DefaultFun fun) {
        return TermTypes.builtinReturnType(fun);
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
