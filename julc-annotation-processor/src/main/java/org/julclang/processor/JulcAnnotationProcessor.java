package org.julclang.processor;

import org.julclang.blueprint.BlueprintConfig;
import org.julclang.blueprint.BlueprintGenerator;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.ValidatorOutput;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.CompilerTargetRegistry;
import org.julclang.compiler.JavaSourceIntrospector;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.ScriptPurposeMetadata;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.OptimizationConfiguration;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.source.SourceMapSerializer;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.OptimizationCostProfile;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Annotation processor that compiles supported JuLC validator annotations to
 * UPLC scripts during javac.
 * <p>
 * The compiled script is written to {@code META-INF/plutus/<ClassName>.plutus.json}
 * in the class output directory, making it available on the classpath at runtime.
 * <p>
 * Library discovery:
 * <ol>
 *   <li>Same-project: {@code @OnchainLibrary} classes discovered via {@code roundEnv}; a class of this compilation
 *       that a validator names and that is an on-chain library through a framework's marker annotation is admitted by
 *       its exact name (ADR-071 D1)</li>
 *   <li>Classpath: Sources bundled in {@code META-INF/plutus-sources/} in dependency JARs</li>
 *   <li>Usage-based: Only libraries imported by the validator are included in compilation</li>
 *   <li>Transitive: Recursively resolves library imports until no new libraries are found</li>
 * </ol>
 * <p>
 * Uses {@link Trees} to read the original source file and delegates to
 * {@link JulcCompiler} for the actual compilation.
 */
@SupportedOptions({"julc.projectName", "julc.projectVersion", "julc.sourceMap", "julc.blueprint", "julc.target", "julc.optimization", "julc.costProfile", "julc.frontend"})
public class JulcAnnotationProcessor extends AbstractProcessor {

    private static final String ONCHAIN_LIBRARY = "org.julclang.stdlib.annotation.OnchainLibrary";

    private static final Set<String> VALIDATOR_ANNOTATIONS = Set.of(
            "org.julclang.stdlib.annotation.SpendingValidator",
            "org.julclang.stdlib.annotation.MintingValidator",
            "org.julclang.stdlib.annotation.WithdrawValidator",
            "org.julclang.stdlib.annotation.CertifyingValidator",
            "org.julclang.stdlib.annotation.VotingValidator",
            "org.julclang.stdlib.annotation.ProposingValidator",
            "org.julclang.stdlib.annotation.MultiValidator");

    private Trees trees;
    private StdlibRegistry stdlib;
    private boolean sourceMapEnabled;
    private boolean blueprintEnabled;
    private boolean processingFailed;
    private CompilerTarget compilerTarget;
    private OptimizationLevel optimizationLevel;
    private OptimizationCostProfile optimizationCostProfile;

    /** Accumulated compiled validators for CIP-57 blueprint generation. */
    private final List<BlueprintGenerator.CompiledValidator> compiledValidators = new ArrayList<>();

    /** Same-project @OnchainLibrary sources: FQCN -> source metadata */
    private final Map<String, LibrarySource> sameProjectLibraries = new LinkedHashMap<>();

    /** Nested library classes already reported, so a class admitted for several validators is reported once. */
    private final Set<Element> reportedNestedLibraries = new HashSet<>();

    /** Marker annotations already reported as having no source. */
    private final Set<String> reportedMarkers = new HashSet<>();

    /** Project libraries already reported as compiled from a jar's copy. */
    private final Set<String> reportedJarCopies = new HashSet<>();

    /** Classpath library sources: FQCN -> source metadata */
    private final Map<String, LibrarySource> classpathLibraries = new LinkedHashMap<>();

    /** Whether classpath scanning has been done */
    private boolean classpathScanned = false;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.trees = Trees.instance(processingEnv);
        this.stdlib = StdlibRegistry.defaultRegistry();
        this.sourceMapEnabled = "true".equalsIgnoreCase(
                processingEnv.getOptions().get("julc.sourceMap"));
        this.blueprintEnabled = !"false".equalsIgnoreCase(
                processingEnv.getOptions().get("julc.blueprint"));
        var targetProfile = processingEnv.getOptions().getOrDefault(
                "julc.target", CompilerTarget.PLUTUS_V3_PV11.profileId());
        try {
            this.compilerTarget = CompilerTargetRegistry.targetForProfileId(targetProfile);
            CompilerOptions.requireJavacFrontend(processingEnv.getOptions().get("julc.frontend"));
            var optimizationOptions = OptimizationConfiguration.apply(
                    new CompilerOptions().setTarget(compilerTarget),
                    processingEnv.getOptions().getOrDefault(
                            "julc.optimization", OptimizationLevel.DEFAULT_PROFILE_ID),
                    processingEnv.getOptions().get("julc.costProfile"));
            this.optimizationLevel = optimizationOptions.getOptimizationLevel();
            this.optimizationCostProfile = optimizationOptions.getOptimizationCostProfile();
        } catch (CompilerException e) {
            this.processingFailed = true;
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR, e.getMessage());
        }
    }

    /** JuLC reads the sources with its own javac, so the build's source level asks nothing of this processor. */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        var supported = new LinkedHashSet<>(VALIDATOR_ANNOTATIONS);
        supported.add(ONCHAIN_LIBRARY);
        return supported;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (processingFailed) {
            return true;
        }
        // 1. Collect same-project on-chain libraries
        collectSameProjectLibraries(roundEnv);

        // 2. Process validator elements
        for (TypeElement annotation : annotations) {
            if (!VALIDATOR_ANNOTATIONS.contains(annotation.getQualifiedName().toString())) {
                continue; // @OnchainLibrary: already collected above
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                processElement(element, annotation);
            }
        }

        // 3. Generate CIP-57 blueprint on final round
        if (roundEnv.processingOver() && blueprintEnabled && !processingFailed
                && !roundEnv.errorRaised() && !compiledValidators.isEmpty()) {
            generateBlueprint();
        }

        return true;
    }

    private void collectSameProjectLibraries(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(findTypeElement(ONCHAIN_LIBRARY))) {
            if (!(element instanceof TypeElement typeElement)) {
                continue;
            }
            String simpleName = typeElement.getSimpleName().toString();
            if (typeElement.getNestingKind() != NestingKind.TOP_LEVEL) {
                if (reportedNestedLibraries.add(element)) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, nestedLibraryMessage(typeElement),
                            element);
                }
                continue;
            }
            String fqcn = typeElement.getQualifiedName().toString();
            if (sameProjectLibraries.containsKey(fqcn)) {
                continue;
            }
            var libraryPath = trees.getPath(element);
            if (libraryPath != null) {
                rejectNestedLibrariesInFile(libraryPath, true);   // ADR-071 D1: marked classes nested in this file
            }
            requireMarkerSources(typeElement);
            try {
                var path = trees.getPath(element);
                if (path != null) {
                    String source = path.getCompilationUnit().getSourceFile()
                            .getCharContent(true).toString();
                    sameProjectLibraries.put(fqcn, LibrarySourceResolver.librarySourceFromSimpleName(simpleName, source));
                }
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Could not read source for @OnchainLibrary class: " + simpleName, element);
            }
        }
    }

    /** Adds {@code element} and the types nested in it that are on-chain libraries, directly or through a marker. */
    private static void collectLibraries(Element element, Set<Element> into) {
        if (!(element instanceof TypeElement type)) return;
        if (JavacFacts.libraryMarker(type).isPresent()) into.add(type);
        for (Element member : type.getEnclosedElements()) collectLibraries(member, into);
    }

    private static String nestedLibraryMessage(TypeElement type) {
        var marker = JavacFacts.libraryMarker(type).orElseThrow();
        if (marker.getQualifiedName().contentEquals(ONCHAIN_LIBRARY)) {
            return "@OnchainLibrary must be declared on a top-level class. Nested on-chain libraries are not supported.";
        }
        return "@" + marker.getSimpleName() + " makes " + type.getSimpleName() + " an on-chain library, and an on-chain"
                + " library must be a top-level class. Move " + type.getSimpleName() + " into a file of its own.";
    }

    private TypeElement findTypeElement(String qualifiedName) {
        return processingEnv.getElementUtils().getTypeElement(qualifiedName);
    }

    /**
     * Reads the class path's library sources once, when the first validator needs them. An index or source it cannot
     * read costs only that entry, and is reported as a note (ADR-071 D2): a note, not a warning, so a build with
     * {@code -Werror} behaves as before.
     */
    private void scanClasspathLibraries() {
        if (classpathScanned) return;
        classpathScanned = true;
        classpathLibraries.putAll(LibrarySourceResolver.scanClasspathSources(getClass().getClassLoader(),
                problem -> processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "JuLC skipped an on-chain library source on the processor path: " + problem)));
    }

    private void processElement(Element element, TypeElement annotation) {
        String className = element.getSimpleName().toString();
        String source = "";
        List<String> librarySources = List.of();
        try {
            // 1. Read source file via Trees API
            var path = trees.getPath(element);
            if (path == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Cannot find source for " + className, element);
                return;
            }
            source = path.getCompilationUnit().getSourceFile().getCharContent(true).toString();

            // 2. Resolve library sources needed by this validator
            scanClasspathLibraries();
            librarySources = resolveLibrarySources(source);

            // 3. Compile with JulcCompiler (multi-file if libraries found)
            var options = new CompilerOptions()
                    .setTarget(compilerTarget)
                    .setOptimizationLevel(optimizationLevel);
            if (optimizationCostProfile != null) {
                options.setOptimizationCostProfile(optimizationCostProfile);
            }
            if (sourceMapEnabled) {
                options.setSourceMapEnabled(true);
            }
            // The class path's sources are JAR sources: the javac gate ranks them below this project's (ADR-068 D8).
            options.setJarLibrarySources(LibrarySourceResolver.jarSourceTexts(libraryPool(), classpathLibraries));
            var compiler = new JulcCompiler(stdlib, options);
            var contractResult = blueprintEnabled
                    ? compiler.compileContract(source, librarySources)
                    : null;
            var result = blueprintEnabled
                    ? contractResult.compileResult()
                    : compiler.compile(source, librarySources);

            if (result.hasErrors()) {
                processingFailed = true;
                String failure = String.valueOf(result.diagnostics());
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Plutus compilation failed for " + className + ": " + failure + importHint(failure, source, librarySources),
                        element);
                return;
            }

            // 4. Generate output
            var program = result.program();
            var script = JulcScriptAdapter.fromProgram(program);

            var scriptPurpose = JavaSourceIntrospector.scriptPurpose(annotation.getSimpleName().toString());
            String scriptType = ScriptPurposeMetadata.textEnvelopeType();
            String purpose = ScriptPurposeMetadata.jsonPurpose(scriptPurpose);

            // Build params string from CompileResult
            String paramsStr = result.params().stream()
                    .map(p -> p.name() + ":" + p.type())
                    .collect(Collectors.joining(","));

            // For parameterized validators, hash depends on applied params — store empty
            String scriptHash = result.isParameterized()
                    ? "" : JulcScriptAdapter.scriptHash(program);

            int sizeBytes = result.scriptSizeBytes();
            String sizeStr = result.scriptSizeFormatted();

            var output = new ValidatorOutput(scriptType, purpose, className,
                    script.getCborHex(), scriptHash, paramsStr, sizeBytes);

            // 5. Write to META-INF/plutus/<ClassName>.plutus.json
            var filer = processingEnv.getFiler();
            var resource = filer.createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    "META-INF/plutus/" + className + ".plutus.json",
                    element);

            try (Writer writer = resource.openWriter()) {
                writer.write(output.toJson());
            }

            // 6. Write source map if enabled and available
            if (sourceMapEnabled && result.hasSourceMap()) {
                var indexed = result.sourceMap().toIndexed(program.term());
                String sourceMapJson = SourceMapSerializer.toJson(indexed, className);
                var smResource = filer.createResource(
                        StandardLocation.CLASS_OUTPUT,
                        "",
                        "META-INF/plutus/" + className + ".sourcemap.json",
                        element);
                try (Writer smWriter = smResource.openWriter()) {
                    smWriter.write(sourceMapJson);
                }
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "Generated source map for " + className
                                + " (" + indexed.size() + " entries, optimization skipped)",
                        element);
            }

            // Accumulate for CIP-57 blueprint generation
            if (blueprintEnabled) {
                compiledValidators.add(new BlueprintGenerator.CompiledValidator(
                        className, result, contractResult.contractSchema()));
            }

            String libMsg = librarySources.isEmpty() ? "" : " (with " + librarySources.size() + " library file(s))";
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Compiled Plutus validator: " + className + " (target: "
                            + result.target().profileId() + ", optimization: "
                            + result.optimizationReport().level().profileId() + ", hash: " + scriptHash
                            + ", size: " + sizeStr + ")" + libMsg,
                    element);

        } catch (CompilerException e) {
            processingFailed = true;
            String failure = e.describe();
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Plutus compilation error: " + failure + importHint(failure, source, librarySources), element);
        } catch (IOException e) {
            processingFailed = true;
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "I/O error writing compiled script for " + className + ": " + e.getMessage(),
                    element);
        } catch (Exception e) {
            processingFailed = true;
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Unexpected compilation error for " + className + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    element);
        }
    }

    /** Same-project and class-path libraries, merged by primary name (same-project takes precedence). */
    private Map<String, LibrarySource> libraryPool() {
        var pool = new LinkedHashMap<>(classpathLibraries);
        pool.putAll(sameProjectLibraries);
        return pool;
    }

    /**
     * Resolve library sources needed by the given validator source.
     * Uses import statements and same-package references to find matching
     * {@code @OnchainLibrary} classes, then recursively resolves transitive imports.
     */
    private List<String> resolveLibrarySources(String validatorSource) {
        var pool = libraryPool();

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool, new LibrarySourceResolver.CompilationHost(
                this::libraryOfThisCompilation, this::isLibraryMarker));
        noteJarCopiesOfProjectLibraries(resolved);
        return resolved;
    }

    /**
     * A class-path library source compiled for a class this compilation also declares as an on-chain library: JuLC
     * compiles the jar's source, as before ADR-071, while javac checked the project's class. Typically a stale jar of the
     * project's own module on the processor path. Reported as a note, once per class; the resolution is unchanged.
     */
    private void noteJarCopiesOfProjectLibraries(List<String> resolved) {
        for (var jarSource : classpathLibraries.values()) {
            if (resolved.stream().noneMatch(source -> source == jarSource.source())) continue;
            String fqcn = jarSource.fqcn();
            if (sameProjectLibraries.containsKey(fqcn) || !reportedJarCopies.add(fqcn)) continue;
            TypeElement type;
            try {
                type = processingEnv.getElementUtils().getTypeElement(fqcn);
            } catch (RuntimeException e) {
                continue;
            }
            if (type == null || trees.getPath(type) == null || JavacFacts.libraryMarker(type).isEmpty()) continue;
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "JuLC compiles " + fqcn + " from the on-chain"
                    + " library source " + jarSource.resourcePath() + " on the annotation processor path, not from this"
                    + " project's " + fqcn + ". If that jar is a stale copy of this project, remove it from the processor"
                    + " path.", type);
        }
    }

    private static final Pattern MISSING_SYMBOL = Pattern.compile("symbol:\\s+(?:variable|class)\\s+(\\w+)");
    private static final Pattern MISSING_LOCATION = Pattern.compile("location:\\s+class\\s+([\\w.]+)");

    /**
     * After JuLC's compilation of a validator failed: for each name it could not find that is a class of this
     * compilation marked through a framework's annotation, or a marker, in the package of a source of the compilation or
     * a package one imports with a wildcard, the import that makes JuLC find it (ADR-071 D1). Such classes are found
     * only through a single-type import. Runs only for a failed compilation, so asking javac costs a passing build
     * nothing.
     */
    private String importHint(String failure, String validatorSource, List<String> librarySources) {
        var packages = new LinkedHashSet<String>();
        Matcher location = MISSING_LOCATION.matcher(failure);
        while (location.find()) {
            String owner = location.group(1);
            packages.add(owner.contains(".") ? owner.substring(0, owner.lastIndexOf('.')) : "");
        }
        var wildcards = new LinkedHashSet<String>();
        var sources = new ArrayList<String>(librarySources);
        sources.add(validatorSource);
        for (String compiled : sources) {
            packages.add(LibrarySourceResolver.extractPackageName(compiled));
            wildcards.addAll(LibrarySourceResolver.extractWildcardImportPackages(compiled));
        }
        packages.addAll(wildcards);
        var hints = new LinkedHashSet<String>();
        Matcher symbol = MISSING_SYMBOL.matcher(failure);
        while (symbol.find()) {
            for (String pkg : packages) {
                if (pkg.isEmpty()) continue;   // a class of the default package cannot be imported
                String fqcn = pkg + "." + symbol.group(1);
                TypeElement type;
                try {
                    type = processingEnv.getElementUtils().getTypeElement(fqcn);
                } catch (RuntimeException e) {
                    continue;
                }
                if (type == null || trees.getPath(type) == null && !isLibraryMarker(fqcn)) continue;
                var marker = JavacFacts.libraryMarker(type);
                if (isLibraryMarker(fqcn)) {
                    // A marker is followed from its own package already; only a wildcard import needs replacing.
                    if (!wildcards.contains(pkg)) continue;
                    hints.add("@" + type.getSimpleName() + " is a library marker; JuLC follows a marker only through a"
                            + " single-type import, a qualified name or its own package: add `import " + fqcn + ";`.");
                } else if (marker.isPresent() && !marker.get().getQualifiedName().contentEquals(ONCHAIN_LIBRARY)) {
                    hints.add(type.getSimpleName() + " is an on-chain library through @" + marker.get().getSimpleName()
                            + "; JuLC finds such a class only through a single-type import, even from its own"
                            + " package: add `import " + fqcn + ";`.");
                }
            }
        }
        return hints.isEmpty() ? "" : "\n" + String.join("\n", hints);
    }

    /** Whether javac's class {@code fqcn} is a library marker: an annotation interface carrying {@code @OnchainLibrary}. */
    private boolean isLibraryMarker(String fqcn) {
        TypeElement type;
        try {
            type = processingEnv.getElementUtils().getTypeElement(fqcn);
        } catch (RuntimeException e) {
            return false;
        }
        return type != null && type.getKind() == ElementKind.ANNOTATION_TYPE && JavacFacts.libraryMarker(type)
                .filter(marker -> marker.getQualifiedName().contentEquals(ONCHAIN_LIBRARY)).isPresent();
    }

    /**
     * The library javac names {@code fqcn}, if it is a top-level on-chain library class of this compilation (ADR-071 D1).
     * javac calls this processor only from the first round that holds one of its annotations, so a class that carries
     * only a framework's marker, entered in an earlier round or in a round whose annotations another processor claimed,
     * is never collected. The resolver asks for the exact names a source refers to that the pool lacks.
     * <p>
     * Any top-level class of this compilation that is asked for has its nested library classes rejected, as
     * {@link #collectSameProjectLibraries} rejects a nested {@code @OnchainLibrary}; a library whose marker JuLC has no
     * source for is reported with the remedy.
     */
    private LibrarySource libraryOfThisCompilation(String fqcn) {
        TypeElement type;
        try {
            type = processingEnv.getElementUtils().getTypeElement(fqcn);
        } catch (RuntimeException e) {
            return null;
        }
        if (type == null) {
            return null;
        }
        var path = trees.getPath(type);
        if (path == null) {
            return null;   // a class-path class: its source comes from META-INF/plutus-sources, if anywhere
        }
        rejectNestedLibrariesInFile(path, false);
        if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
            return null;
        }
        if (JavacFacts.libraryMarker(type).isEmpty()) {
            return null;
        }
        requireMarkerSources(type);
        try {
            return LibrarySourceResolver.librarySourceFromQualifiedName(fqcn,
                    path.getCompilationUnit().getSourceFile().getCharContent(true).toString());
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not read source for on-chain library " + fqcn + ": " + e.getMessage(), type);
            return null;
        }
    }

    /**
     * Every top-level class of the file has its nested library classes rejected. Round collection reports a nested
     * {@code @OnchainLibrary} itself, so from there only those marked through a marker are reported here.
     */
    private void rejectNestedLibrariesInFile(TreePath path, boolean markedOnly) {
        for (var declaration : path.getCompilationUnit().getTypeDecls()) {
            if (trees.getElement(TreePath.getPath(path.getCompilationUnit(), declaration)) instanceof TypeElement top) {
                rejectNestedLibraries(top, markedOnly);
            }
        }
    }

    private void rejectNestedLibraries(TypeElement type, boolean markedOnly) {
        var nested = new LinkedHashSet<Element>();
        for (Element member : type.getEnclosedElements()) collectLibraries(member, nested);
        for (Element element : nested) {
            if (markedOnly && JavacFacts.libraryMarker(element).orElseThrow().getQualifiedName()
                    .contentEquals(ONCHAIN_LIBRARY)) {
                continue;
            }
            if (reportedNestedLibraries.add(element)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        nestedLibraryMessage((TypeElement) element), element);
            }
        }
    }

    /**
     * JuLC compiles a marker annotation's source with the library that carries it. A marker of this compilation, or one
     * whose jar ships its source in {@code META-INF/plutus-sources}, has one; for any other, JuLC's own compilation could
     * only report that it cannot find the annotation, so say why here.
     */
    private void requireMarkerSources(TypeElement library) {
        for (var mirror : library.getAnnotationMirrors()) {
            var annotation = (TypeElement) mirror.getAnnotationType().asElement();
            if (JavacFacts.libraryMarker(annotation).filter(meta -> meta.getQualifiedName().contentEquals(ONCHAIN_LIBRARY))
                    .isPresent()) {
                requireMarkerSource(library, annotation);
            }
        }
    }

    private void requireMarkerSource(TypeElement library, TypeElement marker) {
        String markerName = marker.getQualifiedName().toString();
        if (!markerName.equals(ONCHAIN_LIBRARY) && trees.getPath(marker) == null) {
            scanClasspathLibraries();
        }
        if (markerName.equals(ONCHAIN_LIBRARY) || sameProjectLibraries.containsKey(markerName)
                || classpathLibraries.containsKey(markerName) || trees.getPath(marker) != null
                || !reportedMarkers.add(markerName)) {
            return;
        }
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@" + marker.getSimpleName() + " makes "
                + library.getSimpleName() + " an on-chain library, but JuLC has no source for " + markerName + ". JuLC"
                + " compiles a marker annotation with the libraries that carry it: ship its source in its jar's"
                + " META-INF/plutus-sources, listed in META-INF/plutus-sources/index.txt, as for an on-chain library.",
                library);
    }

    /**
     * Generate aggregated CIP-57 blueprint from all compiled validators.
     */
    private void generateBlueprint() {
        try {
            String projectName = processingEnv.getOptions().getOrDefault("julc.projectName", "julc-project");
            String projectVersion = processingEnv.getOptions().getOrDefault("julc.projectVersion", "0.0.0");

            var config = new BlueprintConfig(projectName, projectVersion);
            var blueprint = BlueprintGenerator.generate(config, compiledValidators);

            var filer = processingEnv.getFiler();
            var resource = filer.createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    "META-INF/plutus/plutus.json");

            try (Writer writer = resource.openWriter()) {
                writer.write(blueprint.toJson());
            }

            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Generated CIP-57 blueprint: META-INF/plutus/plutus.json ("
                            + compiledValidators.size() + " validator(s))");
        } catch (IOException | IllegalArgumentException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Could not generate CIP-57 blueprint: " + e.getMessage());
        }
    }
}
