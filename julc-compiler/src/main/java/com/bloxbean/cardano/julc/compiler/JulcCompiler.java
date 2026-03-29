package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.codegen.ValidatorWrapper;
import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.compiler.pir.*;
import com.bloxbean.cardano.julc.compiler.resolve.ImportResolver;
import com.bloxbean.cardano.julc.compiler.resolve.LedgerSourceLoader;
import com.bloxbean.cardano.julc.compiler.resolve.LibraryMethodRegistry;
import com.bloxbean.cardano.julc.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.julc.compiler.resolve.TypeRegistrar;
import com.bloxbean.cardano.julc.compiler.resolve.TypeResolver;
import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.compiler.uplc.UplcOptimizer;
import com.bloxbean.cardano.julc.compiler.util.MethodDependencyResolver;
import com.bloxbean.cardano.julc.compiler.validate.SubsetValidator;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Main compiler facade. Orchestrates the pipeline:
 * parse -> validate -> resolve -> PIR generate -> UPLC generate -> Program
 */
public class JulcCompiler {

    /**
     * The script purpose determined by the annotation on the validator class.
     */
    public enum ScriptPurpose {
        SPENDING, MINTING, WITHDRAW, CERTIFYING, VOTING, PROPOSING, MULTI
    }

    /** All annotation names recognized as validator annotations. */
    private static final List<String> VALIDATOR_ANNOTATIONS = List.of(
            "Validator", "SpendingValidator",
            "MintingPolicy", "MintingValidator",
            "WithdrawValidator", "CertifyingValidator",
            "VotingValidator", "ProposingValidator",
            "MultiValidator"
    );

    private record ParamField(String name, PirType pirType, String javaType) {}
    private record StaticField(String name, PirType pirType, com.github.javaparser.ast.expr.Expression initExpr) {}
    private record CompiledStaticField(String name, PirTerm initPir) {}

    /** Stdlib class FQCNs — must match StdlibRegistry registration keys. */
    private static final Set<String> STDLIB_FQCNS = Set.of(
            "com.bloxbean.cardano.julc.stdlib.Builtins",
            "com.bloxbean.cardano.julc.stdlib.lib.ContextsLib",
            "com.bloxbean.cardano.julc.stdlib.lib.ListsLib",
            "com.bloxbean.cardano.julc.stdlib.lib.MapLib",
            "com.bloxbean.cardano.julc.stdlib.lib.ValuesLib",
            "com.bloxbean.cardano.julc.stdlib.lib.OutputLib",
            "com.bloxbean.cardano.julc.stdlib.lib.MathLib",
            "com.bloxbean.cardano.julc.stdlib.lib.IntervalLib",
            "com.bloxbean.cardano.julc.stdlib.lib.CryptoLib",
            "com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib",
            "com.bloxbean.cardano.julc.stdlib.lib.BitwiseLib",
            "com.bloxbean.cardano.julc.stdlib.lib.AddressLib"
    );

    /** Typed Data subtypes that must not be used with @Param. */
    private static final Set<String> BANNED_PARAM_TYPES = Set.of(
            "PlutusData.BytesData", "BytesData",
            "PlutusData.MapData", "MapData",
            "PlutusData.ListData", "ListData",
            "PlutusData.IntData", "IntData"
    );

    private final StdlibLookup stdlibLookup;
    private final CompilerOptions options;

    public JulcCompiler() {
        this(null, new CompilerOptions());
    }

    public JulcCompiler(StdlibLookup stdlibLookup) {
        this(stdlibLookup, new CompilerOptions());
    }

    public JulcCompiler(StdlibLookup stdlibLookup, CompilerOptions options) {
        this.stdlibLookup = stdlibLookup;
        this.options = options != null ? options : new CompilerOptions();
    }

    /**
     * Compile a single-file validator to a UPLC Program.
     * Auto-discovers @OnchainLibrary sources from classpath (META-INF/plutus-sources/).
     */
    public CompileResult compile(String javaSource) {
        var availableLibs = LibrarySourceResolver.scanClasspathSources(
                JulcCompiler.class.getClassLoader());
        if (availableLibs.isEmpty()) {
            return compile(javaSource, List.of());
        }
        var resolvedLibs = LibrarySourceResolver.resolve(javaSource, availableLibs);
        return compile(javaSource, resolvedLibs);
    }

    /**
     * Compile a single-file validator, capturing PIR and UPLC intermediate representations.
     * The returned {@link CompileResult} will have non-null {@code pirTerm()} and {@code uplcTerm()}.
     */
    public CompileResult compileWithDetails(String javaSource) {
        var availableLibs = LibrarySourceResolver.scanClasspathSources(
                JulcCompiler.class.getClassLoader());
        if (availableLibs.isEmpty()) {
            return compileWithDetails(javaSource, List.of());
        }
        var resolvedLibs = LibrarySourceResolver.resolve(javaSource, availableLibs);
        return compileWithDetails(javaSource, resolvedLibs);
    }

    /**
     * Compile a validator with library sources, capturing PIR and UPLC intermediate representations.
     */
    public CompileResult compileWithDetails(String validatorSource, List<String> librarySources) {
        return doCompile(validatorSource, librarySources, true);
    }

    /**
     * Compile a validator with library sources to a UPLC Program.
     *
     * @param validatorSource the validator Java source (must contain a validator annotation)
     * @param librarySources  library Java sources (must NOT contain validator annotations)
     * @return the compile result containing the UPLC Program
     */
    public CompileResult compile(String validatorSource, List<String> librarySources) {
        return doCompile(validatorSource, librarySources, false);
    }

    private CompileResult doCompile(String validatorSource, List<String> librarySources, boolean captureDetails) {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

        // 1. Parse all sources
        options.logf("Parsing %d source(s) (1 validator + %d libraries)",
                1 + librarySources.size(), librarySources.size());
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
        options.log("Subset validation passed");

        // 3. Validate: library CUs must not contain validator annotations
        for (var libCu : libraryCus) {
            for (var type : libCu.findAll(ClassOrInterfaceDeclaration.class)) {
                for (var ann : VALIDATOR_ANNOTATIONS) {
                    if (type.getAnnotationByName(ann).isPresent()) {
                        throw new CompilerException("Library source must not contain @" + ann + ": "
                                + type.getNameAsString());
                    }
                }
            }
        }

        // 4. Find annotated validator class and determine purpose
        var validatorClass = findAnnotatedClass(validatorCu);
        var scriptPurpose = getScriptPurpose(validatorClass);
        options.logf("Found @%sValidator: %s", scriptPurpose.name().charAt(0)
                + scriptPurpose.name().substring(1).toLowerCase(), validatorClass.getNameAsString());

        // 5. Register types from ALL sources (topo-sorted)
        var typeResolver = new TypeResolver();

        // 5a. Register ledger types dynamically from bundled sources
        var ledgerCus = LedgerSourceLoader.loadLedgerSources(
                JulcCompiler.class.getClassLoader());
        new TypeRegistrar().registerAll(ledgerCus, typeResolver);
        // Add flat-FQCN aliases for inner variant types (backward compat with ImportResolver)
        typeResolver.addFlatVariantAliases("com.bloxbean.cardano.julc.ledger");
        options.logf("Registered %d ledger type(s) dynamically", ledgerCus.size());

        // 5b. Register user-defined types from validator + library sources
        var allCus = new ArrayList<CompilationUnit>();
        allCus.addAll(libraryCus);
        allCus.add(validatorCu);
        registerTypesWithDiagnostics(allCus, typeResolver, diagnostics);
        options.logf("Registered types from %d compilation unit(s)", allCus.size());

        // 5c. Build knownFqcns for ImportResolver (types + library classes + stdlib classes)
        var knownFqcns = new LinkedHashSet<String>();
        knownFqcns.addAll(typeResolver.allRegisteredFqcns());
        knownFqcns.addAll(TypeResolver.ledgerHashFqcns());
        knownFqcns.addAll(STDLIB_FQCNS);
        for (var libCu : libraryCus) {
            for (var cls : libCu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!cls.isInterface()) {
                    knownFqcns.add(cls.getFullyQualifiedName().orElse(cls.getNameAsString()));
                }
            }
        }

        // 5b. Auto-register .of() for @NewType records as identity lookups
        var newTypeNames = typeResolver.getNewTypeNames();
        StdlibLookup effectiveStdlibLookup = newTypeNames.isEmpty()
                ? stdlibLookup
                : wrapWithNewTypeLookup(stdlibLookup, newTypeNames);

        // 5d. Set import resolver for validator CU (used for type resolution)
        var validatorImportResolver = new ImportResolver(validatorCu, knownFqcns);
        typeResolver.setCurrentImportResolver(validatorImportResolver);

        // 6. Detect @Param fields
        var paramFields = findParamFields(validatorClass, typeResolver, diagnostics);
        if (hasErrors(diagnostics)) {
            throw new CompilerException(diagnostics);
        }

        if (!paramFields.isEmpty()) {
            options.logf("Found %d @Param field(s): %s", paramFields.size(),
                    paramFields.stream().map(pf -> pf.name + ": " + pf.javaType).toList());
        }

        // 6b. Detect static fields with initializers (non-@Param)
        var staticFields = findStaticFields(validatorClass, typeResolver);

        // 7. Find @Entrypoint method(s) and validate
        // For MULTI purpose, discover all entrypoints and their purposes
        var entrypointInfos = new ArrayList<EntrypointInfo>();
        MethodDeclaration entrypointMethod = null;

        if (scriptPurpose == ScriptPurpose.MULTI) {
            entrypointInfos = findMultiEntrypoints(validatorClass);
        } else {
            entrypointMethod = findEntrypoint(validatorClass);
            // 7b. Validate parameter count matches script purpose
            validateEntrypointParams(entrypointMethod, scriptPurpose, validatorClass);
        }

        // 8. Compile library static methods to PIR (progressive lookup: each library sees previous)
        var libraryRegistry = new LibraryMethodRegistry(options);
        if (!libraryCus.isEmpty()) {
            options.logf("Compiling %d library source(s)", libraryCus.size());
            compileLibraryMethods(libraryCus, typeResolver, libraryRegistry, effectiveStdlibLookup, knownFqcns);
            options.logf("Compiled %d library method(s)", libraryRegistry.allMethods().size());
        }

        // Restore validator's import resolver after library compilation
        typeResolver.setCurrentImportResolver(validatorImportResolver);

        // 9. Compose lookup: stdlib + library methods
        var effectiveLookup = libraryRegistry.isEmpty()
                ? effectiveStdlibLookup
                : new CompositeStdlibLookup(effectiveStdlibLookup, libraryRegistry);

        // 10. Set up symbol table
        var symbolTable = new SymbolTable();
        for (var pf : paramFields) {
            symbolTable.define(pf.name, pf.pirType);
        }
        for (var sf : staticFields) {
            symbolTable.define(sf.name, sf.pirType);
        }
        for (var method : validatorClass.getMethods()) {
            if (method.isStatic()) {
                var mType = computeMethodType(method, typeResolver);
                symbolTable.define(method.getNameAsString(), mType);
            }
        }

        // 11. Generate PIR for helper methods
        var pirGenerator = new PirGenerator(typeResolver, symbolTable, effectiveLookup,
                TypeMethodRegistry.defaultRegistry(), null, options);

        // 11b. Compile static field initializers
        var compiledStaticFields = new ArrayList<CompiledStaticField>();
        for (var sf : staticFields) {
            var initPir = pirGenerator.generateExpression(sf.initExpr);
            compiledStaticFields.add(new CompiledStaticField(sf.name, initPir));
        }

        for (var method : validatorClass.getMethods()) {
            if (method.isStatic() && method.getAnnotationByName("Entrypoint").isEmpty()) {
                var helperPir = pirGenerator.generateMethod(method);
                var mType = computeMethodType(method, typeResolver);
                symbolTable.defineMethod(method.getNameAsString(), mType, helperPir);
            }
        }

        // Enable boolean return guard for entrypoint only (not helpers — they may legitimately return false)
        if (options.isSourceMapEnabled()) {
            pirGenerator.setBooleanReturnGuard(true);
        }

        // 12. Generate PIR for entrypoint(s)
        PirTerm validateFn = null;
        Map<Integer, PirTerm> multiHandlers = null;
        Map<Integer, Integer> multiParamCounts = null;
        boolean isMultiAutoDispatch = false;

        boolean isExplicitPurpose = !entrypointInfos.isEmpty()
                && entrypointInfos.stream().noneMatch(e -> e.purposeName().equals("DEFAULT"));
        if (scriptPurpose == ScriptPurpose.MULTI && isExplicitPurpose) {
            // Mode 1: Auto-dispatch — entrypoints with explicit purposes
            isMultiAutoDispatch = true;
            multiHandlers = new LinkedHashMap<>();
            multiParamCounts = new LinkedHashMap<>();
            for (var ei : entrypointInfos) {
                var handlerPir = pirGenerator.generateMethod(ei.method());
                multiHandlers.put(ei.tag(), handlerPir);
                multiParamCounts.put(ei.tag(), ei.method().getParameters().size());
            }
            options.logf("PIR generation complete (%d auto-dispatch entrypoints)", entrypointInfos.size());
        } else if (scriptPurpose == ScriptPurpose.MULTI) {
            // Mode 2: Manual dispatch — single DEFAULT entrypoint
            entrypointMethod = entrypointInfos.get(0).method();
            validateFn = pirGenerator.generateMethod(entrypointMethod);
            options.log("PIR generation complete (manual dispatch)");
        } else {
            validateFn = pirGenerator.generateMethod(entrypointMethod);
            options.log("PIR generation complete");
        }

        // Disable guard after entrypoint generation
        pirGenerator.setBooleanReturnGuard(false);

        // 12b. Collect non-fatal errors from PIR generation
        diagnostics.addAll(pirGenerator.getCollectedErrors());
        if (hasErrors(diagnostics)) {
            throw new CompilerException(diagnostics);
        }

        // 13. Wrap helper methods as Let bindings
        // For auto-dispatch, wrap each handler; for single entrypoint, wrap the single body
        PirTerm body;
        if (isMultiAutoDispatch) {
            // Wrap helper methods around each handler (dependency-ordered)
            for (var entry : multiHandlers.entrySet()) {
                PirTerm handlerBody = wrapMethodBindings(symbolTable.allMethods(), entry.getValue());
                // Wrap static field initializers
                for (int i = compiledStaticFields.size() - 1; i >= 0; i--) {
                    var sf = compiledStaticFields.get(i);
                    handlerBody = new PirTerm.Let(sf.name(), sf.initPir(), handlerBody);
                }
                multiHandlers.put(entry.getKey(), handlerBody);
            }
            body = null; // Not used; wrapper handles it
        } else {
            body = wrapMethodBindings(symbolTable.allMethods(), validateFn);

            // 13b. Wrap static field initializers as Let bindings (reverse order for correct scoping)
            for (int i = compiledStaticFields.size() - 1; i >= 0; i--) {
                var sf = compiledStaticFields.get(i);
                body = new PirTerm.Let(sf.name(), sf.initPir(), body);
            }
        }

        // 14. Wrap library methods as Let bindings (topologically sorted: dependencies outermost)
        var sortedLibMethods = topoSortLibraryMethods(libraryRegistry.allMethods());
        if (isMultiAutoDispatch) {
            for (var entry : multiHandlers.entrySet()) {
                PirTerm handlerBody = entry.getValue();
                for (var libMethod : sortedLibMethods) {
                    if (containsVarRef(libMethod.body(), libMethod.qualifiedName())) {
                        handlerBody = new PirTerm.LetRec(
                                List.of(new PirTerm.Binding(libMethod.qualifiedName(), libMethod.body())),
                                handlerBody);
                    } else {
                        handlerBody = new PirTerm.Let(libMethod.qualifiedName(), libMethod.body(), handlerBody);
                    }
                }
                multiHandlers.put(entry.getKey(), handlerBody);
            }
        } else {
            for (var libMethod : sortedLibMethods) {
                if (containsVarRef(libMethod.body(), libMethod.qualifiedName())) {
                    body = new PirTerm.LetRec(
                            List.of(new PirTerm.Binding(libMethod.qualifiedName(), libMethod.body())),
                            body);
                } else {
                    body = new PirTerm.Let(libMethod.qualifiedName(), libMethod.body(), body);
                }
            }
        }

        // 15. Wrap entrypoint for on-chain
        var wrapper = new ValidatorWrapper();
        PirTerm wrappedTerm;
        if (isMultiAutoDispatch) {
            // Build datumOptional flags for auto-dispatch handlers
            Map<Integer, Boolean> multiDatumOptional = new LinkedHashMap<>();
            for (var ei : entrypointInfos) {
                boolean datumOpt = false;
                if (ei.tag() == 1 && ei.method().getParameters().size() == 3) {
                    var firstParamType = ei.method().getParameter(0).getTypeAsString();
                    datumOpt = firstParamType.startsWith("Optional");
                }
                multiDatumOptional.put(ei.tag(), datumOpt);
            }
            wrappedTerm = wrapper.wrapMultiValidator(multiHandlers, multiParamCounts, multiDatumOptional);
        } else if (scriptPurpose == ScriptPurpose.SPENDING) {
            int paramCount = entrypointMethod.getParameters().size();
            boolean datumIsOptional = false;
            if (paramCount == 3) {
                var firstParamType = entrypointMethod.getParameter(0).getTypeAsString();
                datumIsOptional = firstParamType.startsWith("Optional");
            }
            wrappedTerm = wrapper.wrapSpendingValidator(body, paramCount, datumIsOptional);
        } else {
            // Minting, Withdraw, Certifying, Voting, Proposing, Multi(manual) all use 2-param wrapper
            wrappedTerm = wrapper.wrapMintingPolicy(body);
        }

        // 15b. Register wrapper Error terms in source map (points to @Entrypoint method)
        if (options.isSourceMapEnabled() && !wrapper.getErrorTerms().isEmpty()) {
            var pirPositions = pirGenerator.getPirPositions();
            // For multi-dispatch, use the first entrypoint; for single, use the entrypoint method
            var epMethod = entrypointMethod != null ? entrypointMethod : entrypointInfos.getFirst().method();
            epMethod.getRange().ifPresent(range -> {
                var fileName = epMethod.findCompilationUnit()
                        .flatMap(cu -> cu.getStorage().map(s -> s.getFileName()))
                        .orElse(null);
                var loc = new SourceLocation(fileName, range.begin.line, range.begin.column,
                        "validator returned false");
                for (var errorTerm : wrapper.getErrorTerms()) {
                    pirPositions.put(errorTerm, loc);
                }
            });
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

        // 17. Capture PIR if details requested
        PirTerm capturedPir = captureDetails ? wrappedTerm : null;

        // 18. Lower to UPLC (with source map support)
        var uplcGenerator = options.isSourceMapEnabled()
                ? new UplcGenerator(pirGenerator.getPirPositions())
                : new UplcGenerator();
        var uplcTerm = uplcGenerator.generate(wrappedTerm);

        // 19. Optimize UPLC (skip when source maps enabled to preserve Term identity)
        SourceMap sourceMap = null;
        if (options.isSourceMapEnabled()) {
            sourceMap = SourceMap.of(uplcGenerator.getUplcPositions());
            options.logf("Source map generated: %d entries (optimization skipped)", sourceMap.size());
        } else {
            var optimizer = new UplcOptimizer();
            uplcTerm = optimizer.optimize(uplcTerm);
            options.log("UPLC optimization complete");
        }

        // 20. Capture UPLC if details requested
        Term capturedUplc = captureDetails ? uplcTerm : null;

        // 21. Create Program
        var program = Program.plutusV3(uplcTerm);

        // 22. Build ParamInfo list
        var paramInfos = paramFields.stream()
                .map(pf -> new CompileResult.ParamInfo(pf.name, pf.javaType))
                .toList();

        var result = new CompileResult(program, diagnostics, paramInfos, capturedPir, capturedUplc, sourceMap);
        options.logf("Compilation complete: %s", result.scriptSizeFormatted());
        return result;
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

    // --- Method compilation (for expression evaluation) ---

    /**
     * Compile a single static method to a UPLC Program.
     * No {@code @Validator} annotation required. The method becomes a lambda accepting Data arguments.
     * Auto-discovers {@code @OnchainLibrary} sources from classpath.
     *
     * @param javaSource the Java source containing the method
     * @param methodName the name of the static method to compile
     * @return the compile result containing the UPLC Program
     */
    public CompileResult compileMethod(String javaSource, String methodName) {
        var availableLibs = LibrarySourceResolver.scanClasspathSources(
                Thread.currentThread().getContextClassLoader());
        if (availableLibs.isEmpty()) {
            return compileMethod(javaSource, methodName, List.of());
        }
        var resolvedLibs = LibrarySourceResolver.resolve(javaSource, availableLibs);
        return compileMethod(javaSource, methodName, resolvedLibs);
    }

    /**
     * Compile a single static method to a UPLC Program with explicit library sources.
     *
     * @param javaSource     the Java source containing the method
     * @param methodName     the name of the static method to compile
     * @param librarySources library Java sources (must NOT contain validator annotations)
     * @return the compile result containing the UPLC Program
     */
    public CompileResult compileMethod(String javaSource, String methodName, List<String> librarySources) {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

        // 1. Parse all sources
        var cu = parseSource(javaSource, "method source");
        var libraryCus = new ArrayList<CompilationUnit>();
        for (int i = 0; i < librarySources.size(); i++) {
            libraryCus.add(parseSource(librarySources.get(i), "library[" + i + "]"));
        }

        // 2. Validate subset
        var subsetValidator = new SubsetValidator();
        var diagnostics = new ArrayList<>(subsetValidator.validate(cu));
        for (var libCu : libraryCus) {
            diagnostics.addAll(subsetValidator.validate(libCu));
        }
        if (hasErrors(diagnostics)) {
            throw new CompilerException(diagnostics);
        }

        // 3. Find the target class (first non-interface class)
        var targetClass = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> !c.isInterface())
                .findFirst()
                .orElseThrow(() -> new CompilerException("No class found in source"));

        // 4. Find the target method
        var targetMethod = targetClass.getMethods().stream()
                .filter(m -> m.isStatic() && m.getNameAsString().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new CompilerException(
                        "No static method '" + methodName + "' found in " + targetClass.getNameAsString()));

        // 5. Register types (ledger + user-defined)
        var typeResolver = new TypeResolver();

        var ledgerCus = LedgerSourceLoader.loadLedgerSources(
                JulcCompiler.class.getClassLoader());
        new TypeRegistrar().registerAll(ledgerCus, typeResolver);
        typeResolver.addFlatVariantAliases("com.bloxbean.cardano.julc.ledger");

        var allCus = new ArrayList<CompilationUnit>();
        allCus.addAll(libraryCus);
        allCus.add(cu);
        registerTypesWithDiagnostics(allCus, typeResolver, diagnostics);

        // 6. Build knownFqcns and import resolver
        var knownFqcns = new LinkedHashSet<String>();
        knownFqcns.addAll(typeResolver.allRegisteredFqcns());
        knownFqcns.addAll(TypeResolver.ledgerHashFqcns());
        knownFqcns.addAll(STDLIB_FQCNS);
        for (var libCu : libraryCus) {
            for (var cls : libCu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!cls.isInterface()) {
                    knownFqcns.add(cls.getFullyQualifiedName().orElse(cls.getNameAsString()));
                }
            }
        }

        var newTypeNames = typeResolver.getNewTypeNames();
        StdlibLookup effectiveStdlibLookup = newTypeNames.isEmpty()
                ? stdlibLookup
                : wrapWithNewTypeLookup(stdlibLookup, newTypeNames);

        var importResolver = new ImportResolver(cu, knownFqcns);
        typeResolver.setCurrentImportResolver(importResolver);

        // 7. Detect @Param fields
        var paramFields = findParamFields(targetClass, typeResolver, diagnostics);
        if (hasErrors(diagnostics)) {
            throw new CompilerException(diagnostics);
        }

        // 7b. Detect static fields
        var staticFields = findStaticFields(targetClass, typeResolver);

        // 8. Compile libraries (if any)
        var libraryRegistry = new LibraryMethodRegistry(options);
        if (!libraryCus.isEmpty()) {
            compileLibraryMethods(libraryCus, typeResolver, libraryRegistry, effectiveStdlibLookup, knownFqcns);
        }
        typeResolver.setCurrentImportResolver(importResolver);

        var effectiveLookup = libraryRegistry.isEmpty()
                ? effectiveStdlibLookup
                : new CompositeStdlibLookup(effectiveStdlibLookup, libraryRegistry);

        // 9. Set up symbol table
        var symbolTable = new SymbolTable();
        for (var pf : paramFields) {
            symbolTable.define(pf.name, pf.pirType);
        }
        for (var sf : staticFields) {
            symbolTable.define(sf.name, sf.pirType);
        }
        for (var method : targetClass.getMethods()) {
            if (method.isStatic()) {
                var mType = computeMethodType(method, typeResolver);
                symbolTable.define(method.getNameAsString(), mType);
            }
        }

        // 10. Generate PIR for helper methods
        var pirGenerator = new PirGenerator(typeResolver, symbolTable, effectiveLookup,
                TypeMethodRegistry.defaultRegistry(), null, options);

        var compiledStaticFields = new ArrayList<CompiledStaticField>();
        for (var sf : staticFields) {
            var initPir = pirGenerator.generateExpression(sf.initExpr);
            compiledStaticFields.add(new CompiledStaticField(sf.name, initPir));
        }

        // Compile ALL static methods (including target) so cross-references work
        for (var method : targetClass.getMethods()) {
            if (method.isStatic()) {
                var helperPir = pirGenerator.generateMethod(method);
                var mType = computeMethodType(method, typeResolver);
                symbolTable.defineMethod(method.getNameAsString(), mType, helperPir);
            }
        }

        diagnostics.addAll(pirGenerator.getCollectedErrors());
        if (hasErrors(diagnostics)) {
            throw new CompilerException(diagnostics);
        }

        // 11. Build body: Data-accepting lambda that calls target method by Var reference
        var paramTypes = new ArrayList<PirType>();
        for (var param : targetMethod.getParameters()) {
            paramTypes.add(typeResolver.resolve(param.getType()));
        }

        // Build application: Var("method") applied to decoded args
        PirTerm application = new PirTerm.Var(methodName, computeMethodType(targetMethod, typeResolver));
        for (int i = 0; i < paramTypes.size(); i++) {
            var decodedName = targetMethod.getParameter(i).getNameAsString() + "__dec";
            application = new PirTerm.App(application,
                    new PirTerm.Var(decodedName, paramTypes.get(i)));
        }

        // Wrap with outer Lam + decode for each param (inside-out)
        PirTerm body = application;
        for (int i = paramTypes.size() - 1; i >= 0; i--) {
            var decodedName = targetMethod.getParameter(i).getNameAsString() + "__dec";
            var rawName = targetMethod.getParameter(i).getNameAsString() + "__raw";
            var decoded = PirHelpers.wrapDecode(
                    new PirTerm.Var(rawName, new PirType.DataType()), paramTypes.get(i));
            body = new PirTerm.Let(decodedName, decoded, body);
            body = new PirTerm.Lam(rawName, new PirType.DataType(), body);
        }

        // 12. Wrap ALL methods as Let/LetRec bindings (target + helpers, dependency-ordered)
        body = wrapMethodBindings(symbolTable.allMethods(), body);

        // 14. Wrap static field initializers
        for (int i = compiledStaticFields.size() - 1; i >= 0; i--) {
            var sf = compiledStaticFields.get(i);
            body = new PirTerm.Let(sf.name(), sf.initPir(), body);
        }

        // 15. Wrap library methods as Let bindings
        var sortedLibMethods = topoSortLibraryMethods(libraryRegistry.allMethods());
        for (var libMethod : sortedLibMethods) {
            if (containsVarRef(libMethod.body(), libMethod.qualifiedName())) {
                body = new PirTerm.LetRec(
                        List.of(new PirTerm.Binding(libMethod.qualifiedName(), libMethod.body())),
                        body);
            } else {
                body = new PirTerm.Let(libMethod.qualifiedName(), libMethod.body(), body);
            }
        }

        // 16. Wrap with outer @Param lambdas
        for (int i = paramFields.size() - 1; i >= 0; i--) {
            var pf = paramFields.get(i);
            var rawName = pf.name + "__raw";
            var decoded = PirHelpers.wrapDecode(
                    new PirTerm.Var(rawName, new PirType.DataType()), pf.pirType);
            body = new PirTerm.Lam(rawName, new PirType.DataType(),
                    new PirTerm.Let(pf.name, decoded, body));
        }

        // 17. Lower to UPLC (with source map support)
        var uplcGenerator = options.isSourceMapEnabled()
                ? new UplcGenerator(pirGenerator.getPirPositions())
                : new UplcGenerator();
        var uplcTerm = uplcGenerator.generate(body);

        SourceMap sourceMap = null;
        if (options.isSourceMapEnabled()) {
            sourceMap = SourceMap.of(uplcGenerator.getUplcPositions());
        } else {
            var optimizer = new UplcOptimizer();
            uplcTerm = optimizer.optimize(uplcTerm);
        }

        var paramInfos = paramFields.stream()
                .map(pf -> new CompileResult.ParamInfo(pf.name, pf.javaType))
                .toList();
        var program = Program.plutusV3(uplcTerm);
        return new CompileResult(program, diagnostics, paramInfos, body, uplcTerm, sourceMap);
    }

    // --- Library compilation ---

    private void compileLibraryMethods(List<CompilationUnit> libCus, TypeResolver typeResolver,
                                       LibraryMethodRegistry registry, StdlibLookup effectiveLookup,
                                       Set<String> knownFqcns) {
        new LibraryCompiler(options).compile(libCus, typeResolver, registry, effectiveLookup, knownFqcns);
    }

    /** Delegate to PirHelpers for self-recursion detection. */
    private static boolean containsVarRef(PirTerm term, String name) {
        return PirHelpers.containsVarRef(term, name);
    }

    /**
     * Wrap class/validator methods as Let/LetRec bindings in dependency order.
     * Uses Tarjan's SCC to handle forward references and mutual recursion.
     */
    private static PirTerm wrapMethodBindings(java.util.Collection<SymbolTable.MethodInfo> methods, PirTerm body) {
        if (methods.isEmpty()) return body;

        var bindings = new ArrayList<MethodDependencyResolver.NamedBinding>();
        for (var mi : methods) {
            bindings.add(new MethodDependencyResolver.NamedBinding(mi.name(), mi.body()));
        }

        var groups = MethodDependencyResolver.resolveDependencyOrder(
                bindings, JulcCompiler::containsVarRef);

        // Wrap from first (innermost) to last (outermost)
        for (var group : groups) {
            if (group.isSingle()) {
                var b = group.bindings().get(0);
                if (containsVarRef(b.body(), b.name())) {
                    body = new PirTerm.LetRec(
                            List.of(new PirTerm.Binding(b.name(), b.body())), body);
                } else {
                    body = new PirTerm.Let(b.name(), b.body(), body);
                }
            } else if (group.bindings().size() == 2) {
                // Mutual recursion — multi-binding LetRec (Bekic's theorem in UplcGenerator)
                var pirBindings = new ArrayList<PirTerm.Binding>();
                for (var b : group.bindings()) {
                    pirBindings.add(new PirTerm.Binding(b.name(), b.body()));
                }
                body = new PirTerm.LetRec(pirBindings, body);
            } else {
                var names = group.bindings().stream()
                        .map(MethodDependencyResolver.NamedBinding::name)
                        .toList();
                throw new CompilerException(
                        "Mutual recursion with more than 2 methods is not supported: " + names);
            }
        }

        return body;
    }

    /**
     * Topologically sort library methods so dependencies are outermost in the Let chain.
     * Methods with no library dependencies come last (outermost); dependent methods come first (innermost).
     * The iteration order of the result is suitable for direct use in the Let wrapping loop:
     * first item = innermost, last item = outermost.
     */
    private static List<LibraryMethodRegistry.LibraryMethod> topoSortLibraryMethods(
            java.util.Collection<LibraryMethodRegistry.LibraryMethod> methods) {
        var methodList = new ArrayList<>(methods);
        var methodMap = new java.util.LinkedHashMap<String, LibraryMethodRegistry.LibraryMethod>();
        for (var m : methodList) methodMap.put(m.qualifiedName(), m);

        // Build dependency graph: name -> set of library method names referenced in body
        var deps = new java.util.LinkedHashMap<String, java.util.Set<String>>();
        for (var m : methodList) {
            var myDeps = new java.util.LinkedHashSet<String>();
            for (var other : methodList) {
                if (!other.qualifiedName().equals(m.qualifiedName())
                        && containsVarRef(m.body(), other.qualifiedName())) {
                    myDeps.add(other.qualifiedName());
                }
            }
            deps.put(m.qualifiedName(), myDeps);
        }

        // Kahn's algorithm: emit methods whose dependencies are already emitted
        var emitted = new java.util.LinkedHashSet<String>();
        var result = new ArrayList<LibraryMethodRegistry.LibraryMethod>();
        var remaining = new java.util.LinkedHashSet<>(deps.keySet());
        while (!remaining.isEmpty()) {
            String next = null;
            for (String name : remaining) {
                var d = new java.util.LinkedHashSet<>(deps.get(name));
                d.removeAll(emitted);
                if (d.isEmpty()) {
                    next = name;
                    break;
                }
            }
            if (next == null) {
                // Cycle — add remaining in original order
                for (var m : methodList) {
                    if (remaining.contains(m.qualifiedName())) {
                        result.add(m);
                    }
                }
                break;
            }
            remaining.remove(next);
            emitted.add(next);
            result.add(methodMap.get(next));
        }

        // Kahn's produces dependencies-first order (leaves first).
        // For Let wrapping, we need dependents first (innermost), dependencies last (outermost).
        java.util.Collections.reverse(result);
        return result;
    }

    // --- Helper methods ---

    private CompilationUnit parseSource(String source, String label) {
        try {
            var cu = StaticJavaParser.parse(source);
            // Set synthetic storage so source maps can resolve filenames
            cu.findFirst(ClassOrInterfaceDeclaration.class)
                    .ifPresent(cls -> cu.setStorage(Path.of(cls.getNameAsString() + ".java")));
            return cu;
        } catch (Exception e) {
            throw new CompilerException("Failed to parse " + label + " source: " + e.getMessage());
        }
    }

    /**
     * Wrap a StdlibLookup with identity handlers for @NewType .of() calls.
     */
    private static StdlibLookup wrapWithNewTypeLookup(StdlibLookup base, Set<String> newTypeNames) {
        return new StdlibLookup() {
            @Override
            public java.util.Optional<PirTerm> lookup(String className, String methodName, java.util.List<PirTerm> args) {
                if (methodName.equals("of") && newTypeNames.contains(className)) {
                    if (args.size() != 1) {
                        throw new CompilerException(className + ".of() requires exactly 1 argument, got " + args.size());
                    }
                    return java.util.Optional.of(args.get(0));
                }
                return base != null ? base.lookup(className, methodName, args) : java.util.Optional.empty();
            }

            @Override
            public boolean hasMethodsForClass(String className) {
                return base != null && base.hasMethodsForClass(className);
            }
        };
    }

    private ClassOrInterfaceDeclaration findAnnotatedClass(CompilationUnit cu) {
        for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (var ann : VALIDATOR_ANNOTATIONS) {
                if (type.getAnnotationByName(ann).isPresent()) {
                    return type;
                }
            }
        }
        throw new CompilerException("No validator annotation found (e.g. @SpendingValidator, @MintingValidator)");
    }

    private ScriptPurpose getScriptPurpose(ClassOrInterfaceDeclaration cls) {
        // Count how many validator annotations are present
        var foundAnnotations = new ArrayList<String>();
        for (var ann : VALIDATOR_ANNOTATIONS) {
            if (cls.getAnnotationByName(ann).isPresent()) {
                foundAnnotations.add(ann);
            }
        }
        if (foundAnnotations.size() > 1) {
            throw new CompilerException("Class " + cls.getNameAsString()
                    + " has multiple validator annotations: " + foundAnnotations
                    + ". Only one validator annotation is allowed per class.");
        }

        if (cls.getAnnotationByName("Validator").isPresent()
                || cls.getAnnotationByName("SpendingValidator").isPresent()) {
            return ScriptPurpose.SPENDING;
        }
        if (cls.getAnnotationByName("MintingPolicy").isPresent()
                || cls.getAnnotationByName("MintingValidator").isPresent()) {
            return ScriptPurpose.MINTING;
        }
        if (cls.getAnnotationByName("WithdrawValidator").isPresent()) {
            return ScriptPurpose.WITHDRAW;
        }
        if (cls.getAnnotationByName("CertifyingValidator").isPresent()) {
            return ScriptPurpose.CERTIFYING;
        }
        if (cls.getAnnotationByName("VotingValidator").isPresent()) {
            return ScriptPurpose.VOTING;
        }
        if (cls.getAnnotationByName("ProposingValidator").isPresent()) {
            return ScriptPurpose.PROPOSING;
        }
        if (cls.getAnnotationByName("MultiValidator").isPresent()) {
            return ScriptPurpose.MULTI;
        }
        throw new CompilerException("No validator annotation found on " + cls.getNameAsString());
    }

    private MethodDeclaration findEntrypoint(ClassOrInterfaceDeclaration cls) {
        for (var method : cls.getMethods()) {
            if (method.getAnnotationByName("Entrypoint").isPresent()) {
                return method;
            }
        }
        throw new CompilerException("No @Entrypoint method found in " + cls.getNameAsString());
    }

    /** Map Purpose enum name to ScriptInfo tag. */
    private static final Map<String, Integer> PURPOSE_TAG_MAP = Map.of(
            "MINT", 0,
            "SPEND", 1,
            "WITHDRAW", 2,
            "CERTIFY", 3,
            "VOTE", 4,
            "PROPOSE", 5
    );

    /**
     * Find all @Entrypoint methods in a @MultiValidator class and extract their purposes.
     * Returns a list of (method, purposeName, tag) tuples.
     * <p>
     * Validates:
     * <ul>
     *   <li>No mixing of DEFAULT and explicit purposes</li>
     *   <li>No duplicate purposes</li>
     *   <li>SPEND: 2 or 3 params; all others: 2 params</li>
     *   <li>DEFAULT mode: exactly 1 entrypoint with 2 params</li>
     * </ul>
     */
    private record EntrypointInfo(MethodDeclaration method, String purposeName, int tag) {}

    private ArrayList<EntrypointInfo> findMultiEntrypoints(ClassOrInterfaceDeclaration cls) {
        var entrypoints = new ArrayList<EntrypointInfo>();

        for (var method : cls.getMethods()) {
            var annOpt = method.getAnnotationByName("Entrypoint");
            if (annOpt.isEmpty()) continue;

            var ann = annOpt.get();
            String purposeName = "DEFAULT";

            // Extract purpose from annotation
            if (ann instanceof NormalAnnotationExpr normalAnn) {
                for (MemberValuePair pair : normalAnn.getPairs()) {
                    if (pair.getNameAsString().equals("purpose")) {
                        var value = pair.getValue();
                        if (value instanceof FieldAccessExpr fae) {
                            purposeName = fae.getNameAsString();
                        } else {
                            purposeName = value.toString();
                            // Handle "Purpose.MINT" string form
                            if (purposeName.contains(".")) {
                                purposeName = purposeName.substring(purposeName.lastIndexOf('.') + 1);
                            }
                        }
                    }
                }
            }
            // MarkerAnnotationExpr and SingleMemberAnnotationExpr default to "DEFAULT"

            int tag = purposeName.equals("DEFAULT") ? -1 : PURPOSE_TAG_MAP.getOrDefault(purposeName, -1);
            if (!purposeName.equals("DEFAULT") && tag == -1) {
                throw new CompilerException("Unknown purpose '" + purposeName + "' on @Entrypoint method "
                        + method.getNameAsString() + " in " + cls.getNameAsString()
                        + ". Valid values: MINT, SPEND, WITHDRAW, CERTIFY, VOTE, PROPOSE");
            }

            entrypoints.add(new EntrypointInfo(method, purposeName, tag));
        }

        if (entrypoints.isEmpty()) {
            throw new CompilerException("No @Entrypoint method found in @MultiValidator " + cls.getNameAsString());
        }

        // Check for mixing DEFAULT and explicit purposes
        boolean hasDefault = entrypoints.stream().anyMatch(e -> e.purposeName.equals("DEFAULT"));
        boolean hasExplicit = entrypoints.stream().anyMatch(e -> !e.purposeName.equals("DEFAULT"));
        if (hasDefault && hasExplicit) {
            throw new CompilerException("@MultiValidator " + cls.getNameAsString()
                    + " mixes DEFAULT and explicit purposes. "
                    + "Either use a single @Entrypoint (manual dispatch) or "
                    + "annotate all @Entrypoint methods with explicit purposes.");
        }

        if (hasDefault) {
            // Mode 2: manual dispatch — exactly 1 entrypoint, 2 params
            if (entrypoints.size() > 1) {
                throw new CompilerException("@MultiValidator " + cls.getNameAsString()
                        + " has multiple @Entrypoint methods with DEFAULT purpose. "
                        + "Use explicit purposes (e.g. Purpose.MINT) for auto-dispatch, "
                        + "or use a single @Entrypoint for manual dispatch.");
            }
            int paramCount = entrypoints.get(0).method.getParameters().size();
            if (paramCount != 2) {
                throw new CompilerException("@MultiValidator manual dispatch entrypoint must have 2 parameters "
                        + "(redeemer, scriptContext), found " + paramCount
                        + " in " + cls.getNameAsString() + "." + entrypoints.get(0).method.getNameAsString() + "()");
            }
        } else {
            // Mode 1: auto-dispatch — check for duplicates and param counts
            var seenPurposes = new HashSet<String>();
            for (var ep : entrypoints) {
                if (!seenPurposes.add(ep.purposeName)) {
                    throw new CompilerException("Duplicate purpose " + ep.purposeName
                            + " in @MultiValidator " + cls.getNameAsString());
                }
                int paramCount = ep.method.getParameters().size();
                if (ep.purposeName.equals("SPEND")) {
                    if (paramCount < 2 || paramCount > 3) {
                        throw new CompilerException("@Entrypoint(purpose=Purpose.SPEND) must have 2 or 3 parameters, "
                                + "found " + paramCount + " in " + cls.getNameAsString() + "." + ep.method.getNameAsString() + "()");
                    }
                } else {
                    if (paramCount != 2) {
                        throw new CompilerException("@Entrypoint(purpose=Purpose." + ep.purposeName + ") must have 2 parameters "
                                + "(redeemer, scriptContext), found " + paramCount
                                + " in " + cls.getNameAsString() + "." + ep.method.getNameAsString() + "()");
                    }
                }
            }
        }

        return entrypoints;
    }

    private void validateEntrypointParams(MethodDeclaration entrypoint, ScriptPurpose purpose,
                                          ClassOrInterfaceDeclaration cls) {
        int paramCount = entrypoint.getParameters().size();
        if (purpose == ScriptPurpose.SPENDING) {
            // Spending validators: 2 params (redeemer, ctx) or 3 params (datum, redeemer, ctx)
            if (paramCount < 2 || paramCount > 3) {
                throw new CompilerException("@SpendingValidator entrypoint must have 2 or 3 parameters "
                        + "(datum, redeemer, scriptContext), found " + paramCount
                        + " in " + cls.getNameAsString() + "." + entrypoint.getNameAsString() + "()");
            }
        } else {
            // Non-spending validators: 2 params (redeemer, scriptContext)
            if (paramCount != 2) {
                String annotation = switch (purpose) {
                    case MINTING -> "@MintingValidator/@MintingPolicy";
                    case WITHDRAW -> "@WithdrawValidator";
                    case CERTIFYING -> "@CertifyingValidator";
                    case VOTING -> "@VotingValidator";
                    case PROPOSING -> "@ProposingValidator";
                    default -> "@" + purpose.name();
                };
                String hint = paramCount == 3 ? " Did you mean @SpendingValidator?" : "";
                throw new CompilerException(annotation + " entrypoint must have 2 parameters "
                        + "(redeemer, scriptContext), found " + paramCount
                        + " in " + cls.getNameAsString() + "." + entrypoint.getNameAsString() + "()."
                        + hint);
            }
        }
    }

    private List<ParamField> findParamFields(ClassOrInterfaceDeclaration cls, TypeResolver typeResolver,
                                              List<CompilerDiagnostic> diagnostics) {
        var result = new ArrayList<ParamField>();
        for (var field : cls.getFields()) {
            if (field.getAnnotationByName("Param").isPresent()) {
                var javaType = field.getCommonType().asString();
                if (BANNED_PARAM_TYPES.contains(javaType)) {
                    int line = 0, col = 0;
                    var range = field.getRange();
                    if (range.isPresent()) {
                        line = range.get().begin.line;
                        col = range.get().begin.column;
                    }
                    diagnostics.add(new CompilerDiagnostic(
                            CompilerDiagnostic.Level.ERROR,
                            "@Param type '" + javaType + "' is not allowed. "
                                    + "@Param values are always raw Data at runtime; using a typed Data subtype "
                                    + "causes the compiler to misinterpret the runtime representation.",
                            "<source>", line, col,
                            "Use @Param PlutusData instead of @Param " + javaType));
                    continue;
                }
                for (var variable : field.getVariables()) {
                    var name = variable.getNameAsString();
                    var pirType = typeResolver.resolve(field.getCommonType());
                    result.add(new ParamField(name, pirType, javaType));
                }
            }
        }
        return result;
    }

    /**
     * Find static fields with initializers that are NOT @Param.
     * These are compiled as Let bindings around the body.
     */
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

    /** Register types with error collection instead of immediate failure. */
    private void registerTypesWithDiagnostics(List<CompilationUnit> allCus, TypeResolver typeResolver,
                                              List<CompilerDiagnostic> diagnostics) {
        try {
            new TypeRegistrar().registerAll(allCus, typeResolver);
        } catch (CompilerException e) {
            diagnostics.addAll(e.diagnostics());
            if (e.diagnostics().isEmpty()) {
                diagnostics.add(new CompilerDiagnostic(
                        CompilerDiagnostic.Level.ERROR, e.getMessage(), "<source>", 0, 0));
            }
            if (hasErrors(diagnostics)) {
                throw new CompilerException(diagnostics);
            }
        }
    }

    private boolean hasErrors(List<CompilerDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(CompilerDiagnostic::isError);
    }
}
