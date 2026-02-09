package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.compiler.codegen.ValidatorWrapper;
import com.bloxbean.cardano.plutus.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.plutus.compiler.pir.*;
import com.bloxbean.cardano.plutus.compiler.resolve.LibraryMethodRegistry;
import com.bloxbean.cardano.plutus.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.plutus.compiler.resolve.TypeRegistrar;
import com.bloxbean.cardano.plutus.compiler.resolve.TypeResolver;
import com.bloxbean.cardano.plutus.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.plutus.compiler.uplc.UplcOptimizer;
import com.bloxbean.cardano.plutus.compiler.validate.SubsetValidator;
import com.bloxbean.cardano.plutus.core.Program;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Main compiler facade. Orchestrates the pipeline:
 * parse -> validate -> resolve -> PIR generate -> UPLC generate -> Program
 */
public class PlutusCompiler {

    private record ParamField(String name, PirType pirType, String javaType) {}

    private final StdlibLookup stdlibLookup;

    public PlutusCompiler() {
        this(null);
    }

    public PlutusCompiler(StdlibLookup stdlibLookup) {
        this.stdlibLookup = stdlibLookup;
    }

    /**
     * Compile a single-file validator to a UPLC Program.
     * Delegates to multi-file compile with no library sources.
     */
    public CompileResult compile(String javaSource) {
        return compile(javaSource, List.of());
    }

    /**
     * Compile a validator with library sources to a UPLC Program.
     *
     * @param validatorSource the validator Java source (must contain @Validator or @MintingPolicy)
     * @param librarySources  library Java sources (must NOT contain @Validator/@MintingPolicy)
     * @return the compile result containing the UPLC Program
     */
    public CompileResult compile(String validatorSource, List<String> librarySources) {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        // 1. Parse all sources
        var validatorCu = parseSource(validatorSource, "validator");
        var libraryCus = new ArrayList<CompilationUnit>();
        for (int i = 0; i < librarySources.size(); i++) {
            libraryCus.add(parseSource(librarySources.get(i), "library[" + i + "]"));
        }

        // 2. Validate subset on all compilation units
        var subsetValidator = new SubsetValidator();
        var diagnostics = new ArrayList<>(subsetValidator.validate(validatorCu));
        for (var libCu : libraryCus) {
            diagnostics.addAll(subsetValidator.validate(libCu));
        }
        if (hasErrors(diagnostics)) {
            throw new CompilerException(diagnostics);
        }

        // 3. Validate: library CUs must not contain @Validator/@MintingPolicy
        for (var libCu : libraryCus) {
            for (var type : libCu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.getAnnotationByName("Validator").isPresent() ||
                        type.getAnnotationByName("MintingPolicy").isPresent()) {
                    throw new CompilerException("Library source must not contain @Validator or @MintingPolicy: "
                            + type.getNameAsString());
                }
            }
        }

        // 4. Find @Validator or @MintingPolicy class
        var validatorClass = findAnnotatedClass(validatorCu);
        boolean isMinting = isAnnotatedMinting(validatorClass);

        // 5. Register types from ALL sources (topo-sorted)
        var typeResolver = new TypeResolver();
        com.bloxbean.cardano.plutus.compiler.resolve.LedgerTypeRegistry.registerAll(typeResolver);
        var allCus = new ArrayList<CompilationUnit>();
        allCus.addAll(libraryCus);
        allCus.add(validatorCu);
        new TypeRegistrar().registerAll(allCus, typeResolver);

        // 6. Detect @Param fields
        var paramFields = findParamFields(validatorClass, typeResolver);

        // 7. Find @Entrypoint method
        var entrypointMethod = findEntrypoint(validatorClass);

        // 8. Compile library static methods to PIR
        var libraryRegistry = new LibraryMethodRegistry();
        if (!libraryCus.isEmpty()) {
            compileLibraryMethods(libraryCus, typeResolver, libraryRegistry);
        }

        // 9. Compose lookup: stdlib + library methods
        var effectiveLookup = libraryRegistry.isEmpty()
                ? stdlibLookup
                : new CompositeStdlibLookup(stdlibLookup, libraryRegistry);

        // 10. Set up symbol table
        var symbolTable = new SymbolTable();
        for (var pf : paramFields) {
            symbolTable.define(pf.name, pf.pirType);
        }
        for (var method : validatorClass.getMethods()) {
            if (method.isStatic()) {
                var mType = computeMethodType(method, typeResolver);
                symbolTable.define(method.getNameAsString(), mType);
            }
        }

        // 11. Generate PIR for helper methods
        var pirGenerator = new PirGenerator(typeResolver, symbolTable, effectiveLookup);
        for (var method : validatorClass.getMethods()) {
            if (method.isStatic() && method.getAnnotationByName("Entrypoint").isEmpty()) {
                var helperPir = pirGenerator.generateMethod(method);
                var mType = computeMethodType(method, typeResolver);
                symbolTable.defineMethod(method.getNameAsString(), mType, helperPir);
            }
        }

        // 12. Generate PIR for the entrypoint method
        var validateFn = pirGenerator.generateMethod(entrypointMethod);

        // 13. Wrap helper methods as Let bindings
        PirTerm body = validateFn;
        var methods = new ArrayList<>(symbolTable.allMethods());
        for (int i = methods.size() - 1; i >= 0; i--) {
            var mi = methods.get(i);
            body = new PirTerm.Let(mi.name(), mi.body(), body);
        }

        // 14. Wrap library methods as outermost Let bindings
        for (var libMethod : libraryRegistry.allMethods()) {
            body = new PirTerm.Let(libMethod.qualifiedName(), libMethod.body(), body);
        }

        // 15. Wrap entrypoint for on-chain
        var wrapper = new ValidatorWrapper();
        int paramCount = entrypointMethod.getParameters().size();
        PirTerm wrappedTerm;
        if (isMinting) {
            wrappedTerm = wrapper.wrapMintingPolicy(body);
        } else {
            wrappedTerm = wrapper.wrapSpendingValidator(body, paramCount);
        }

        // 16. Wrap with outer param lambdas
        for (int i = paramFields.size() - 1; i >= 0; i--) {
            var pf = paramFields.get(i);
            var rawName = pf.name + "__raw";
            var decoded = PirHelpers.wrapDecode(
                    new PirTerm.Var(rawName, new PirType.DataType()), pf.pirType);
            wrappedTerm = new PirTerm.Lam(rawName, new PirType.DataType(),
                    new PirTerm.Let(pf.name, decoded, wrappedTerm));
        }

        // 17. Lower to UPLC
        var uplcGenerator = new UplcGenerator();
        var uplcTerm = uplcGenerator.generate(wrappedTerm);

        // 18. Optimize UPLC
        var optimizer = new UplcOptimizer();
        uplcTerm = optimizer.optimize(uplcTerm);

        // 19. Create Program
        var program = Program.plutusV3(uplcTerm);

        // 20. Build ParamInfo list
        var paramInfos = paramFields.stream()
                .map(pf -> new CompileResult.ParamInfo(pf.name, pf.javaType))
                .toList();

        return new CompileResult(program, diagnostics, paramInfos);
    }

    /**
     * Compile a validator from a source file.
     */
    public CompileResult compile(Path sourceFile) throws IOException {
        return compile(Files.readString(sourceFile));
    }

    /**
     * Compile a validator from a source file with library files.
     */
    public CompileResult compile(Path validatorFile, List<Path> libraryFiles) throws IOException {
        var validatorSource = Files.readString(validatorFile);
        var librarySources = new ArrayList<String>();
        for (var libFile : libraryFiles) {
            librarySources.add(Files.readString(libFile));
        }
        return compile(validatorSource, librarySources);
    }

    /**
     * Compile a validator from a source file.
     */
    public CompileResult compile(File sourceFile) throws IOException {
        return compile(sourceFile.toPath());
    }

    /**
     * Compile a validator with all sibling .java files as libraries.
     * Non-validator/non-minting .java files in the same directory are treated as libraries.
     */
    public CompileResult compileWithSiblings(Path validatorFile) throws IOException {
        var parentDir = validatorFile.getParent();
        if (parentDir == null) {
            return compile(Files.readString(validatorFile));
        }
        var libPaths = new ArrayList<Path>();
        try (var stream = Files.list(parentDir)) {
            stream.filter(p -> p.toString().endsWith(".java") && !p.equals(validatorFile))
                    .forEach(libPaths::add);
        }
        return compile(validatorFile, libPaths);
    }

    /**
     * Compile a PIR expression directly to a UPLC Program (for testing).
     */
    public Program compilePirToProgram(PirTerm pirTerm) {
        var uplcGenerator = new UplcGenerator();
        var uplcTerm = uplcGenerator.generate(pirTerm);
        return Program.plutusV3(uplcTerm);
    }

    // --- Library compilation ---

    private void compileLibraryMethods(List<CompilationUnit> libCus, TypeResolver typeResolver,
                                       LibraryMethodRegistry registry) {
        for (var libCu : libCus) {
            for (var cls : libCu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cls.isInterface()) continue;
                var className = cls.getNameAsString();

                // Set up a local symbol table for methods within this library class
                var libSymbolTable = new SymbolTable();
                for (var method : cls.getMethods()) {
                    if (method.isStatic()) {
                        var mType = computeMethodType(method, typeResolver);
                        libSymbolTable.define(method.getNameAsString(), mType);
                    }
                }

                // Compile each static method
                var libPirGenerator = new PirGenerator(typeResolver, libSymbolTable, stdlibLookup);
                for (var method : cls.getMethods()) {
                    if (method.isStatic()) {
                        var pirBody = libPirGenerator.generateMethod(method);
                        var mType = computeMethodType(method, typeResolver);
                        registry.register(className, method.getNameAsString(), mType, pirBody);
                    }
                }
            }
        }
    }

    // --- Helper methods ---

    private CompilationUnit parseSource(String source, String label) {
        try {
            return StaticJavaParser.parse(source);
        } catch (Exception e) {
            throw new CompilerException("Failed to parse " + label + " source: " + e.getMessage());
        }
    }

    private ClassOrInterfaceDeclaration findAnnotatedClass(CompilationUnit cu) {
        for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.getAnnotationByName("Validator").isPresent() ||
                    type.getAnnotationByName("MintingPolicy").isPresent()) {
                return type;
            }
        }
        throw new CompilerException("No @Validator or @MintingPolicy class found");
    }

    private boolean isAnnotatedMinting(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotationByName("MintingPolicy").isPresent();
    }

    private MethodDeclaration findEntrypoint(ClassOrInterfaceDeclaration cls) {
        for (var method : cls.getMethods()) {
            if (method.getAnnotationByName("Entrypoint").isPresent()) {
                return method;
            }
        }
        throw new CompilerException("No @Entrypoint method found in " + cls.getNameAsString());
    }

    private List<ParamField> findParamFields(ClassOrInterfaceDeclaration cls, TypeResolver typeResolver) {
        var result = new ArrayList<ParamField>();
        for (var field : cls.getFields()) {
            if (field.getAnnotationByName("Param").isPresent()) {
                for (var variable : field.getVariables()) {
                    var name = variable.getNameAsString();
                    var pirType = typeResolver.resolve(field.getCommonType());
                    var javaType = field.getCommonType().asString();
                    result.add(new ParamField(name, pirType, javaType));
                }
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

    private boolean hasErrors(List<CompilerDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(CompilerDiagnostic::isError);
    }
}
