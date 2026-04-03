package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.*;
import com.bloxbean.cardano.julc.compiler.resolve.ImportResolver;
import com.bloxbean.cardano.julc.compiler.resolve.LibraryMethodRegistry;
import com.bloxbean.cardano.julc.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.julc.compiler.resolve.TypeResolver;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Compiles {@code @OnchainLibrary} Java sources to PIR method bodies
 * and registers them into a {@link LibraryMethodRegistry}.
 * <p>
 * Extracted from JulcCompiler to isolate the library-specific compilation
 * sub-pipeline (per-CU ImportResolver, per-class SymbolTable, retry strategy).
 */
final class LibraryCompiler {

    private final CompilerOptions options;

    LibraryCompiler(CompilerOptions options) {
        this.options = options;
    }

    /**
     * Compile all library compilation units into the given registry.
     * Uses a multi-pass strategy: retry CUs that fail due to unresolved cross-library references.
     */
    void compile(List<CompilationUnit> libCus, TypeResolver typeResolver,
                 LibraryMethodRegistry registry, StdlibLookup effectiveLookup,
                 Set<String> knownFqcns) {
        var remaining = new ArrayList<>(libCus);
        boolean progress = true;
        while (progress && !remaining.isEmpty()) {
            progress = false;
            var nextRemaining = new ArrayList<CompilationUnit>();
            for (var libCu : remaining) {
                try {
                    compileSingleCu(libCu, typeResolver, registry, effectiveLookup, knownFqcns);
                    progress = true;
                } catch (CompilerException e) {
                    // Expected: unresolved cross-library reference — will retry
                    nextRemaining.add(libCu);
                } catch (Exception e) {
                    // Unexpected: likely a compiler bug. Retry to avoid blocking the user,
                    // but warn so developers can investigate.
                    var cuName = libCu.getStorage().map(s -> s.getFileName()).orElse("<unknown>");
                    options.warnf("Unexpected error compiling library %s (will retry): %s: %s",
                            cuName, e.getClass().getSimpleName(), e.getMessage());
                    nextRemaining.add(libCu);
                }
            }
            remaining = nextRemaining;
        }
        // Final pass: compile remaining CUs (will throw with proper error if unresolvable)
        for (var libCu : remaining) {
            compileSingleCu(libCu, typeResolver, registry, effectiveLookup, knownFqcns);
        }
    }

    private void compileSingleCu(CompilationUnit libCu, TypeResolver typeResolver,
                                  LibraryMethodRegistry registry, StdlibLookup effectiveLookup,
                                  Set<String> knownFqcns) {
        var libImportResolver = new ImportResolver(libCu, knownFqcns);
        typeResolver.setCurrentImportResolver(libImportResolver);

        for (var cls : libCu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cls.isInterface()) continue;
            var className = cls.getNameAsString();
            var classNameFqcn = cls.getFullyQualifiedName().orElse(className);

            var libStaticFields = findStaticFields(cls, typeResolver);

            var libSymbolTable = new SymbolTable();
            for (var sf : libStaticFields) {
                libSymbolTable.define(sf.name(), sf.pirType());
            }
            for (var method : cls.getMethods()) {
                if (method.isStatic()) {
                    var mType = computeMethodType(method, typeResolver);
                    libSymbolTable.define(classNameFqcn + "." + method.getNameAsString(), mType);
                }
            }

            var composedLookup = new CompositeStdlibLookup(effectiveLookup, registry);
            var libPirGenerator = new PirGenerator(typeResolver, libSymbolTable, composedLookup,
                    TypeMethodRegistry.defaultRegistry(), classNameFqcn);

            record LibCompiledField(String name, PirTerm initPir) {}
            var compiledLibFields = new ArrayList<LibCompiledField>();
            for (var sf : libStaticFields) {
                var initPir = libPirGenerator.generateExpression(sf.initExpr());
                compiledLibFields.add(new LibCompiledField(sf.name(), initPir));
            }

            for (var method : cls.getMethods()) {
                if (method.isStatic()) {
                    var pirBody = libPirGenerator.generateMethod(method);
                    for (int i = compiledLibFields.size() - 1; i >= 0; i--) {
                        var sf = compiledLibFields.get(i);
                        pirBody = new PirTerm.Let(sf.name(), sf.initPir(), pirBody);
                    }
                    var mType = computeMethodType(method, typeResolver);
                    registry.register(classNameFqcn, method.getNameAsString(), mType, pirBody);
                }
            }
        }
    }

    // --- Shared helpers (same logic as JulcCompiler, extracted to avoid coupling) ---

    private record StaticField(String name, PirType pirType, com.github.javaparser.ast.expr.Expression initExpr) {}

    private List<StaticField> findStaticFields(ClassOrInterfaceDeclaration cls, TypeResolver typeResolver) {
        var result = new ArrayList<StaticField>();
        for (var field : cls.getFields()) {
            if (!field.isStatic()) continue;
            if (field.getAnnotationByName("Param").isPresent()) continue;
            for (var variable : field.getVariables()) {
                if (variable.getInitializer().isEmpty()) continue;
                var name = variable.getNameAsString();
                var pirType = typeResolver.resolve(field.getCommonType());
                result.add(new StaticField(name, pirType, variable.getInitializer().get()));
            }
        }
        return result;
    }

    private PirType computeMethodType(MethodDeclaration method, TypeResolver typeResolver) {
        var paramTypes = new ArrayList<PirType>();
        for (var param : method.getParameters()) {
            paramTypes.add(typeResolver.resolve(param.getType()));
        }
        PirType methodType = typeResolver.resolve(method.getType());
        for (int i = paramTypes.size() - 1; i >= 0; i--) {
            methodType = new PirType.FunType(paramTypes.get(i), methodType);
        }
        return methodType;
    }
}
