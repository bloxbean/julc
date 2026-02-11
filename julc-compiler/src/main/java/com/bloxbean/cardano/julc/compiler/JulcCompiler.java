package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.codegen.ValidatorWrapper;
import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;
import com.bloxbean.cardano.julc.compiler.pir.*;
import com.bloxbean.cardano.julc.compiler.resolve.LibraryMethodRegistry;
import com.bloxbean.cardano.julc.compiler.resolve.SymbolTable;
import com.bloxbean.cardano.julc.compiler.resolve.TypeRegistrar;
import com.bloxbean.cardano.julc.compiler.resolve.TypeResolver;
import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.compiler.uplc.UplcOptimizer;
import com.bloxbean.cardano.julc.compiler.validate.SubsetValidator;
import com.bloxbean.cardano.julc.core.Program;
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
public class JulcCompiler {

    /**
     * The script purpose determined by the annotation on the validator class.
     */
    public enum ScriptPurpose {
        SPENDING, MINTING, WITHDRAW, CERTIFYING, VOTING, PROPOSING
    }

    /** All annotation names recognized as validator annotations. */
    private static final List<String> VALIDATOR_ANNOTATIONS = List.of(
            "Validator", "SpendingValidator",
            "MintingPolicy", "MintingValidator",
            "WithdrawValidator", "CertifyingValidator",
            "VotingValidator", "ProposingValidator"
    );

    private record ParamField(String name, PirType pirType, String javaType) {}

    private final StdlibLookup stdlibLookup;

    public JulcCompiler() {
        this(null);
    }

    public JulcCompiler(StdlibLookup stdlibLookup) {
        this.stdlibLookup = stdlibLookup;
    }

    /**
     * Compile a single-file validator to a UPLC Program.
     * Auto-discovers @OnchainLibrary sources from classpath (META-INF/plutus-sources/).
     */
    public CompileResult compile(String javaSource) {
        var availableLibs = LibrarySourceResolver.scanClasspathSources(
                Thread.currentThread().getContextClassLoader());
        if (availableLibs.isEmpty()) {
            return compile(javaSource, List.of());
        }
        var resolvedLibs = LibrarySourceResolver.resolve(javaSource, availableLibs);
        return compile(javaSource, resolvedLibs);
    }

    /**
     * Compile a validator with library sources to a UPLC Program.
     *
     * @param validatorSource the validator Java source (must contain a validator annotation)
     * @param librarySources  library Java sources (must NOT contain validator annotations)
     * @return the compile result containing the UPLC Program
     */
    public CompileResult compile(String validatorSource, List<String> librarySources) {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

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

        // 5. Register types from ALL sources (topo-sorted)
        var typeResolver = new TypeResolver();
        com.bloxbean.cardano.julc.compiler.resolve.LedgerTypeRegistry.registerAll(typeResolver);
        var allCus = new ArrayList<CompilationUnit>();
        allCus.addAll(libraryCus);
        allCus.add(validatorCu);
        new TypeRegistrar().registerAll(allCus, typeResolver);

        // 6. Detect @Param fields
        var paramFields = findParamFields(validatorClass, typeResolver);

        // 7. Find @Entrypoint method
        var entrypointMethod = findEntrypoint(validatorClass);

        // 7b. Validate parameter count matches script purpose
        validateEntrypointParams(entrypointMethod, scriptPurpose, validatorClass);

        // 8. Compile library static methods to PIR (progressive lookup: each library sees previous)
        var libraryRegistry = new LibraryMethodRegistry();
        if (!libraryCus.isEmpty()) {
            compileLibraryMethods(libraryCus, typeResolver, libraryRegistry, stdlibLookup);
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

        // 14. Wrap library methods as Let bindings (topologically sorted: dependencies outermost)
        var sortedLibMethods = topoSortLibraryMethods(libraryRegistry.allMethods());
        for (var libMethod : sortedLibMethods) {
            if (containsVarRef(libMethod.body(), libMethod.qualifiedName())) {
                // Self-recursive method: wrap in LetRec (Z-combinator)
                body = new PirTerm.LetRec(
                        List.of(new PirTerm.Binding(libMethod.qualifiedName(), libMethod.body())),
                        body);
            } else {
                body = new PirTerm.Let(libMethod.qualifiedName(), libMethod.body(), body);
            }
        }

        // 15. Wrap entrypoint for on-chain
        var wrapper = new ValidatorWrapper();
        int paramCount = entrypointMethod.getParameters().size();
        PirTerm wrappedTerm;
        if (scriptPurpose == ScriptPurpose.SPENDING) {
            wrappedTerm = wrapper.wrapSpendingValidator(body, paramCount);
        } else {
            // Minting, Withdraw, Certifying, Voting, Proposing all use 2-param wrapper
            wrappedTerm = wrapper.wrapMintingPolicy(body);
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
                                       LibraryMethodRegistry registry, StdlibLookup effectiveLookup) {
        // Multi-pass compilation: retry CUs that fail due to unresolved cross-library references.
        // Each pass compiles CUs whose dependencies are already available, progressively growing
        // the registry until all CUs are compiled or no further progress can be made.
        var remaining = new ArrayList<>(libCus);
        boolean progress = true;
        while (progress && !remaining.isEmpty()) {
            progress = false;
            var nextRemaining = new ArrayList<CompilationUnit>();
            for (var libCu : remaining) {
                try {
                    compileSingleLibraryCu(libCu, typeResolver, registry, effectiveLookup);
                    progress = true;
                } catch (Exception e) {
                    nextRemaining.add(libCu);
                }
            }
            remaining = nextRemaining;
        }
        // Final pass: compile any remaining CUs (will throw with proper error if unresolvable)
        for (var libCu : remaining) {
            compileSingleLibraryCu(libCu, typeResolver, registry, effectiveLookup);
        }
    }

    private void compileSingleLibraryCu(CompilationUnit libCu, TypeResolver typeResolver,
                                         LibraryMethodRegistry registry, StdlibLookup effectiveLookup) {
        for (var cls : libCu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cls.isInterface()) continue;
            var className = cls.getNameAsString();

            // Set up a local symbol table with qualified names for intra-library calls
            var libSymbolTable = new SymbolTable();
            for (var method : cls.getMethods()) {
                if (method.isStatic()) {
                    var mType = computeMethodType(method, typeResolver);
                    libSymbolTable.define(className + "." + method.getNameAsString(), mType);
                }
            }

            // Use progressive lookup: stdlib + already-compiled libraries from previous CUs
            var composedLookup = new CompositeStdlibLookup(effectiveLookup, registry);

            // Compile each static method (pass className for qualified name resolution)
            var libPirGenerator = new PirGenerator(typeResolver, libSymbolTable, composedLookup,
                    TypeMethodRegistry.defaultRegistry(), className);
            for (var method : cls.getMethods()) {
                if (method.isStatic()) {
                    var pirBody = libPirGenerator.generateMethod(method);
                    var mType = computeMethodType(method, typeResolver);
                    registry.register(className, method.getNameAsString(), mType, pirBody);
                }
            }
        }
    }

    /** Check if a PIR term contains a Var reference with the given name (for self-recursion detection). */
    private static boolean containsVarRef(PirTerm term, String name) {
        return switch (term) {
            case PirTerm.Var v -> v.name().equals(name);
            case PirTerm.Let l -> containsVarRef(l.value(), name) || containsVarRef(l.body(), name);
            case PirTerm.LetRec lr -> lr.bindings().stream().anyMatch(b -> containsVarRef(b.value(), name))
                    || containsVarRef(lr.body(), name);
            case PirTerm.Lam lam -> containsVarRef(lam.body(), name);
            case PirTerm.App app -> containsVarRef(app.function(), name) || containsVarRef(app.argument(), name);
            case PirTerm.IfThenElse ite -> containsVarRef(ite.cond(), name)
                    || containsVarRef(ite.thenBranch(), name) || containsVarRef(ite.elseBranch(), name);
            case PirTerm.DataConstr dc -> dc.fields().stream().anyMatch(f -> containsVarRef(f, name));
            case PirTerm.DataMatch dm -> containsVarRef(dm.scrutinee(), name)
                    || dm.branches().stream().anyMatch(b -> containsVarRef(b.body(), name));
            case PirTerm.Trace t -> containsVarRef(t.message(), name) || containsVarRef(t.body(), name);
            case PirTerm.Const _, PirTerm.Builtin _, PirTerm.Error _ -> false;
        };
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
            return StaticJavaParser.parse(source);
        } catch (Exception e) {
            throw new CompilerException("Failed to parse " + label + " source: " + e.getMessage());
        }
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
