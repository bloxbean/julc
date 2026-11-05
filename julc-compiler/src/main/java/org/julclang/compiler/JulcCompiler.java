package org.julclang.compiler;

import org.julclang.compiler.codegen.ValidatorAssembly;
import org.julclang.compiler.corpus.CorpusRecorder;
import org.julclang.compiler.debug.DebugMetadataCollector;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticInfo;
import org.julclang.compiler.frontend.javac.JavacCompilation;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacRuntime;
import org.julclang.compiler.pir.*;
import org.julclang.compiler.resolve.LibraryMethodRegistry;
import org.julclang.compiler.schema.ContractCompileResult;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.compiler.uplc.UplcGenerator;
import org.julclang.compiler.uplc.UplcTargetValidator;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.source.SourceLocation;
import org.julclang.core.debug.DebugMetadata;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.julclang.compiler.error.DiagnosticCodes.ENTRYPOINT_MISSING;
import static org.julclang.compiler.error.DiagnosticCodes.ENTRYPOINT_WRONG_PARAMETER_COUNT;
import static org.julclang.compiler.error.DiagnosticCodes.PARAM_RAW_PLUTUS_DATA;
import static org.julclang.compiler.error.DiagnosticCodes.VALIDATOR_ANNOTATION_MISSING;

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
    private record CompilationOutcome(CompileResult compileResult, ContractSchema contractSchema,
                                      DebugMetadata debugMetadata) {}

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
     * The library sources a source compiles with when the caller names none: what its imports resolve to in the class
     * path's scan, on both frontends, as JAR sources (ADR-068 D8).
     */
    private List<String> classpathLibraries(String source, ClassLoader classLoader, CompilationContext context) {
        var libraries = resolvedLibraries(source, classLoader);
        context.markJarSources(libraries);
        return libraries;
    }

    private static List<String> resolvedLibraries(String source, ClassLoader classLoader) {
        var available = LibrarySourceResolver.scanClasspathSources(classLoader);
        return available.isEmpty() ? List.of() : LibrarySourceResolver.resolve(source, available);
    }

    /**
     * ADR-068: the one place a compilation leaves for the javac frontend.
     * The call stays inside {@link JavacRuntime#call}, which is what makes a runtime without the compiler API
     * {@code JULC0089} (I6): {@code JavacCompilation} cannot link there.
     */
    private CompilationOutcome onJavac(JavacFrontend.Unit root, List<JavacFrontend.Unit> pool, boolean captureDetails,
                                       boolean captureContractSchema, boolean collectDebugMetadata,
                                       CompilationContext context) {
        var request = new JavacCompilation.Request(captureDetails, captureContractSchema,
                collectDebugMetadata ? pool.stream().map(JavacFrontend.Unit::text).toList() : null);
        JavacCompilation.Compiled compiled;
        try {
            compiled = JavacRuntime.call(() -> JavacCompilation.validator(root, pool, stdlibLookup, context, request));
        } catch (CompilerException e) {
            throw withExcerpts(e, root, pool);
        }
        var result = compiled.result();
        if (context.isVerbose() && result.program() != null) {
            context.logf("Compilation complete (javac frontend): %s", result.scriptSizeFormatted());
        }
        DebugMetadata metadata = compiled.debug() == null ? null : compiled.debug().finish(result, options,
                compiled.lowered().debug().emittedBinders(), compiled.lowered().debug().exactUplcPositions());
        return new CompilationOutcome(result, compiled.schema(), metadata);
    }

    /**
     * What a compilation of {@code source} with {@code librarySources} reports of the subset on the javac frontend, before
     * it lowers anything ({@link JavacCompilation#subset}): javac's rejection, or the subset's rules and scan, with the
     * same codes at the same positions, and the libraries ranked as a compilation ranks them (D8). For JuLC's tools that
     * check a source without compiling it (ADR-068 M6), whatever frontend this compiler compiles with. Empty when the
     * source keeps to the subset.
     */
    public List<CompilerDiagnostic> subsetDiagnostics(String source, List<String> librarySources) {
        var context = beginCompilation();
        var root = JavacFrontend.Unit.of(source);
        var pool = libraryUnits(librarySources, context);
        try {
            return DiagnosticExcerpts.attach(JavacRuntime.call(() -> JavacCompilation.subset(root, pool, context)), root, pool);
        } catch (CompilerException e) {
            return withExcerpts(e, root, pool).diagnostics();
        }
    }

    private CompilationContext beginCompilation() {
        var context = CompilationContext.resolve(options);
        context.logf("Compiler target: %s", context.target().profileId());
        context.logf("Compiler version: %s", CompilerVersion.VERSION);
        context.logf("Optimization level: %s", context.optimizationLevel());
        if (context.optimizationCostProfile() != null) {
            context.logf("Configured compatibility cost profile (unused by compilation): %s (sha256=%s)",
                    context.optimizationCostProfile().profileId(),
                    context.optimizationCostProfile().parameterHash());
        }
        return context;
    }

    /**
     * Compile a single-file validator to a UPLC Program.
     * Auto-discovers @OnchainLibrary sources from classpath (META-INF/plutus-sources/).
     */
    public CompileResult compile(String javaSource) {
        var context = beginCompilation();
        var resolvedLibs = classpathLibraries(javaSource, JulcCompiler.class.getClassLoader(), context);
        if (CorpusRecorder.ACTIVE != null) recordCorpus("compile", null, javaSource, resolvedLibs, context);
        return doCompile(javaSource, resolvedLibs, false, context);
    }

    /**
     * Compile a single-file validator, capturing PIR and UPLC intermediate representations.
     * The returned {@link CompileResult} will have non-null {@code pirTerm()} and {@code uplcTerm()}.
     */
    public CompileResult compileWithDetails(String javaSource) {
        var context = beginCompilation();
        var resolvedLibs = classpathLibraries(javaSource, JulcCompiler.class.getClassLoader(), context);
        if (CorpusRecorder.ACTIVE != null) recordCorpus("compileWithDetails", null, javaSource, resolvedLibs, context);
        return doCompile(javaSource, resolvedLibs, true, context);
    }

    /**
     * Compile a validator with library sources, capturing PIR and UPLC intermediate representations.
     */
    public CompileResult compileWithDetails(String validatorSource, List<String> librarySources) {
        var context = beginCompilation();
        if (CorpusRecorder.ACTIVE != null) {
            recordCorpus("compileWithDetails", null, validatorSource, librarySources, context);
        }
        return doCompile(validatorSource, librarySources, true, context);
    }

    /**
     * Compile the source-debug artifact with optional binding metadata collection.
     * This operation is deliberately separate from every normal/source-map compile API.
     */
    public DebugCompileResult compileForDebug(String validatorSource, List<String> librarySources) {
        requireSourceMap();
        return compileForDebug(validatorSource, librarySources, beginCompilation());
    }

    private void requireSourceMap() {
        if (!options.isSourceMapEnabled()) {
            throw new IllegalStateException("Java locals metadata requires source-map compilation");
        }
    }

    private DebugCompileResult compileForDebug(String validatorSource, List<String> librarySources,
                                               CompilationContext context) {
        if (CorpusRecorder.ACTIVE != null) {
            recordCorpus("compileForDebug", null, validatorSource, librarySources, context);
        }
        var outcome = doCompileOutcome(
                validatorSource, librarySources, true, false, context, true);
        if (outcome.debugMetadata() == null) {
            throw new IllegalStateException("Java locals metadata was not collected");
        }
        return new DebugCompileResult(outcome.compileResult(), outcome.debugMetadata());
    }

    /** Compile a source-debug artifact with classpath-discovered libraries. */
    public DebugCompileResult compileForDebug(String validatorSource) {
        return compileForDebug(validatorSource, JulcCompiler.class.getClassLoader());
    }

    /** {@link #compileForDebug(String)}, with the libraries of {@code classLoader}: they are found before anything else. */
    DebugCompileResult compileForDebug(String validatorSource, ClassLoader classLoader) {
        var libraries = resolvedLibraries(validatorSource, classLoader);
        requireSourceMap();
        var context = beginCompilation();
        context.markJarSources(libraries);
        return compileForDebug(validatorSource, libraries, context);
    }

    /**
     * Compile a validator with library sources to a UPLC Program.
     *
     * @param validatorSource the validator Java source (must contain a validator annotation)
     * @param librarySources  library Java sources (must NOT contain validator annotations)
     * @return the compile result containing the UPLC Program
     */
    public CompileResult compile(String validatorSource, List<String> librarySources) {
        var context = beginCompilation();
        if (CorpusRecorder.ACTIVE != null) recordCorpus("compile", null, validatorSource, librarySources, context);
        return doCompile(validatorSource, librarySources, false, context);
    }

    /**
     * Compile a validator and capture its resolved datum, redeemer, and parameter types.
     *
     * <p>The returned program is produced by the same lowering path as {@link #compile(String)}.
     * Schema capture is observational and is intended for blueprint generation.</p>
     */
    public ContractCompileResult compileContract(String validatorSource) {
        var context = beginCompilation();
        var resolvedLibs = classpathLibraries(validatorSource, JulcCompiler.class.getClassLoader(), context);
        if (CorpusRecorder.ACTIVE != null) {
            recordCorpus("compileContract", null, validatorSource, resolvedLibs, context);
        }
        var outcome = doCompileOutcome(validatorSource, resolvedLibs, false, true, context);
        return new ContractCompileResult(outcome.compileResult(), outcome.contractSchema());
    }

    /** Compile a validator with library sources and capture its resolved contract schema. */
    public ContractCompileResult compileContract(
            String validatorSource, List<String> librarySources) {
        var context = beginCompilation();
        if (CorpusRecorder.ACTIVE != null) {
            recordCorpus("compileContract", null, validatorSource, librarySources, context);
        }
        var outcome = doCompileOutcome(
                validatorSource, librarySources, false, true, context);
        return new ContractCompileResult(outcome.compileResult(), outcome.contractSchema());
    }

    /** Compile with PIR/UPLC inspection details and resolved contract schema metadata. */
    public ContractCompileResult compileContractWithDetails(String validatorSource) {
        var context = beginCompilation();
        var resolvedLibs = classpathLibraries(validatorSource, JulcCompiler.class.getClassLoader(), context);
        if (CorpusRecorder.ACTIVE != null) {
            recordCorpus("compileContractWithDetails", null, validatorSource, resolvedLibs, context);
        }
        var outcome = doCompileOutcome(validatorSource, resolvedLibs, true, true, context);
        return new ContractCompileResult(outcome.compileResult(), outcome.contractSchema());
    }

    /** Compile with PIR/UPLC inspection details and resolved contract schema metadata. */
    public ContractCompileResult compileContractWithDetails(
            String validatorSource, List<String> librarySources) {
        var context = beginCompilation();
        if (CorpusRecorder.ACTIVE != null) {
            recordCorpus("compileContractWithDetails", null, validatorSource, librarySources, context);
        }
        var outcome = doCompileOutcome(
                validatorSource, librarySources, true, true, context);
        return new ContractCompileResult(outcome.compileResult(), outcome.contractSchema());
    }

    private CompileResult doCompile(
            String validatorSource,
            List<String> librarySources,
            boolean captureDetails,
            CompilationContext context) {
        return doCompileOutcome(
                validatorSource, librarySources, captureDetails, false, context).compileResult();
    }

    private CompilationOutcome doCompileOutcome(
            String validatorSource,
            List<String> librarySources,
            boolean captureDetails,
            boolean captureContractSchema,
            CompilationContext context) {
        return doCompileOutcome(validatorSource, librarySources, captureDetails,
                captureContractSchema, context, false);
    }

    private CompilationOutcome doCompileOutcome(
            String validatorSource,
            List<String> librarySources,
            boolean captureDetails,
            boolean captureContractSchema,
            CompilationContext context,
            boolean collectDebugMetadata) {
        return onJavac(JavacFrontend.Unit.of(validatorSource), libraryUnits(librarySources, context),
                captureDetails, captureContractSchema, collectDebugMetadata, context);
    }

    private JavacFrontend.Unit libraryUnit(String text) {
        return new JavacFrontend.Unit(text, null, options.getJarLibrarySources().contains(text)
                ? JavacFrontend.Origin.JAR : JavacFrontend.Origin.PROJECT);
    }

    /** The javac frontend's pool: a source the class-path scan found is a JAR source, whichever way it got here. */
    private List<JavacFrontend.Unit> libraryUnits(List<String> sources, CompilationContext context) {
        return sources.stream().map(text -> context.isJarSource(text) ? JavacFrontend.Unit.jar(text) : libraryUnit(text)).toList();
    }

    /**
     * Records a string entry point's inputs for the ADR-068 corpus (test-only; see {@link CorpusRecorder}), on either
     * frontend: a case is its inputs, which the I1 tool compiles on both, and the frontend is a marker beside it (M5).
     */
    private void recordCorpus(String entry, String methodName, String source, List<String> librarySources,
                              CompilationContext context) {
        CorpusRecorder.ACTIVE.record(entry, methodName, new CorpusRecorder.Source(source, null),
                librarySources.stream().map(text -> new CorpusRecorder.Source(text, null)).toList(),
                stdlibLookup, CorpusRecorder.Options.of(context));
    }

    /**
     * Records a path entry point's inputs, with each file's name, for the ADR-068 corpus (test-only). A library file
     * that cannot be read is not recorded here; the compilation reports it as before.
     */
    private void recordCorpusPaths(String entry, Path validatorFile, String validatorText, List<Path> libraryFiles,
                                   CompilationContext context) {
        var libraries = new ArrayList<CorpusRecorder.Source>();
        for (var libraryFile : libraryFiles) {
            try {
                libraries.add(new CorpusRecorder.Source(Files.readString(libraryFile), fileName(libraryFile)));
            } catch (IOException e) {
                return;
            }
        }
        CorpusRecorder.ACTIVE.record(entry, null, new CorpusRecorder.Source(validatorText, fileName(validatorFile)),
                libraries, stdlibLookup, CorpusRecorder.Options.of(context));
    }

    private static String fileName(Path file) {
        var name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }

    /**
     * Compile a validator from a source file.
     * The original file path is preserved in the CompilationUnit for diagnostics and source maps.
     */
    public CompileResult compile(Path sourceFile) throws IOException {
        return compileFromPaths(sourceFile, List.of());
    }

    /**
     * Compile a validator from a source file with library files.
     * Original file paths are preserved in CompilationUnits for diagnostics and source maps.
     */
    public CompileResult compile(Path validatorFile, List<Path> libraryFiles) throws IOException {
        return compileFromPaths(validatorFile, libraryFiles);
    }

    /** Compile a validator source file and capture resolved contract schema metadata. */
    public ContractCompileResult compileContract(Path sourceFile) throws IOException {
        return compileContract(sourceFile, List.of());
    }

    /** Compile a validator and library source files and capture contract schema metadata. */
    public ContractCompileResult compileContract(
            Path validatorFile, List<Path> libraryFiles) throws IOException {
        var outcome = compileOutcomeFromPaths(
                validatorFile, libraryFiles, true, beginCompilation());
        return new ContractCompileResult(outcome.compileResult(), outcome.contractSchema());
    }

    private CompileResult compileFromPaths(Path validatorFile, List<Path> libraryFiles) throws IOException {
        return compileOutcomeFromPaths(
                validatorFile, libraryFiles, false, beginCompilation()).compileResult();
    }

    private CompilationOutcome compileOutcomeFromPaths(
            Path validatorFile,
            List<Path> libraryFiles,
            boolean captureContractSchema,
            CompilationContext context)
            throws IOException {
        context.logf("Parsing %d source(s) (1 validator + %d libraries)",
                1 + libraryFiles.size(), libraryFiles.size());
        var validatorText = Files.readString(validatorFile);
        if (CorpusRecorder.ACTIVE != null) {
            recordCorpusPaths(captureContractSchema ? "compileContractPath" : "compilePath",
                    validatorFile, validatorText, libraryFiles, context);
        }
        var pool = new ArrayList<JavacFrontend.Unit>();
        for (var libFile : libraryFiles) {
            var text = Files.readString(libFile);
            pool.add(new JavacFrontend.Unit(text, libFile, options.getJarLibrarySources().contains(text)
                    ? JavacFrontend.Origin.JAR : JavacFrontend.Origin.PROJECT));
        }
        return onJavac(new JavacFrontend.Unit(validatorText, validatorFile), pool, false, captureContractSchema,
                false, context);
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
        var context = beginCompilation();
        var parentDir = validatorFile.getParent();
        if (parentDir == null) {
            return compileOutcomeFromPaths(
                    validatorFile, List.of(), false, context).compileResult();
        }
        var libPaths = new ArrayList<Path>();
        try (var stream = Files.list(parentDir)) {
            stream.filter(p -> p.toString().endsWith(".java") && !p.equals(validatorFile))
                    .forEach(libPaths::add);
        }
        return compileOutcomeFromPaths(
                validatorFile, libPaths, false, context).compileResult();
    }

    /**
     * Compile a PIR expression directly to a UPLC Program (for testing).
     */
    public Program compilePirToProgram(PirTerm pirTerm) {
        var context = beginCompilation();
        var uplcGenerator = new UplcGenerator(context, null);
        var values = new ValueLiteralFoldPass(context, null).lower(pirTerm).term();
        var folded = new ArrayLiteralFoldPass(context, null).lower(values).term();
        var shared = new ValueConversionSharingPass(context, null).lower(folded).term();
        var promoted = new ListIndexPromotionPass(context, null).lower(shared).term();
        var uplcTerm = uplcGenerator.generate(new PairDestructuringPass(context, null).lower(promoted).term());
        var program = createProgram(context, uplcTerm);
        UplcTargetValidator.validate(program, context, "UPLC lowering");
        return program;
    }

    // --- Method compilation (for expression evaluation) ---

    /**
     * Compile a single static method to a UPLC Program.
     * No validator annotation required. The method becomes a lambda accepting Data arguments.
     * Auto-discovers {@code @OnchainLibrary} sources from classpath.
     *
     * @param javaSource the Java source containing the method
     * @param methodName the name of the static method to compile
     * @return the compile result containing the UPLC Program
     */
    public CompileResult compileMethod(String javaSource, String methodName) {
        var context = beginCompilation();
        var resolvedLibs = classpathLibraries(javaSource, Thread.currentThread().getContextClassLoader(), context);
        return compileMethod(javaSource, methodName, resolvedLibs, context);
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
        return compileMethod(javaSource, methodName, librarySources, beginCompilation());
    }

    private CompileResult compileMethod(
            String javaSource,
            String methodName,
            List<String> librarySources,
            CompilationContext context) {
        if (CorpusRecorder.ACTIVE != null) recordCorpus("compileMethod", methodName, javaSource, librarySources, context);
        var pool = libraryUnits(librarySources, context);
        var root = JavacFrontend.Unit.of(javaSource);
        try {
            return JavacRuntime.call(() -> JavacCompilation.method(root, pool, methodName, stdlibLookup, context));
        } catch (CompilerException e) {
            throw withExcerpts(e, root, pool);
        }
    }

    private static CompilerException withExcerpts(CompilerException e, JavacFrontend.Unit root, List<JavacFrontend.Unit> pool) {
        e.attachExcerpts(diagnostics -> DiagnosticExcerpts.attach(diagnostics, root, pool));
        return e;
    }

    private static Program createProgram(CompilationContext context, Term term) {
        var version = context.target().uplcVersion();
        return new Program(version.major(), version.minor(), version.patch(), term);
    }

    private ContractSchema.Purpose schemaPurpose(ScriptPurpose purpose) {
        return switch (purpose) {
            case MINTING -> ContractSchema.Purpose.MINT;
            case SPENDING -> ContractSchema.Purpose.SPEND;
            case WITHDRAW -> ContractSchema.Purpose.WITHDRAW;
            case CERTIFYING -> ContractSchema.Purpose.CERTIFY;
            case VOTING -> ContractSchema.Purpose.VOTE;
            case PROPOSING -> ContractSchema.Purpose.PROPOSE;
            case MULTI -> throw new IllegalArgumentException(
                    "Multi-purpose schemas require an entrypoint purpose");
        };
    }

    private ContractSchema.Purpose schemaPurpose(String purposeName) {
        try {
            return ContractSchema.Purpose.valueOf(purposeName);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown schema purpose: " + purposeName, ex);
        }
    }

    /** System property that enables the ADR-060 binder-namespace check (set by the test tasks). */
    public static final String VERIFY_BINDER_NAMESPACE = "julc.verifyBinderNamespace";
}
