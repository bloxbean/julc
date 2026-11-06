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
import org.julclang.compiler.OptimizationConfiguration;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.source.SourceMapSerializer;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.OptimizationCostProfile;
import com.sun.source.util.Trees;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
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
 *   <li>Same-project: {@code @OnchainLibrary} classes discovered via {@code roundEnv}</li>
 *   <li>Classpath: Sources bundled in {@code META-INF/plutus-sources/} in dependency JARs</li>
 *   <li>Usage-based: Only libraries imported by the validator are included in compilation</li>
 *   <li>Transitive: Recursively resolves library imports until no new libraries are found</li>
 * </ol>
 * <p>
 * Uses {@link Trees} to read the original source file and delegates to
 * {@link JulcCompiler} for the actual compilation.
 */
@SupportedAnnotationTypes({
        "org.julclang.stdlib.annotation.SpendingValidator",
        "org.julclang.stdlib.annotation.MintingValidator",
        "org.julclang.stdlib.annotation.WithdrawValidator",
        "org.julclang.stdlib.annotation.CertifyingValidator",
        "org.julclang.stdlib.annotation.VotingValidator",
        "org.julclang.stdlib.annotation.ProposingValidator",
        "org.julclang.stdlib.annotation.MultiValidator"
})
@SupportedOptions({"julc.projectName", "julc.projectVersion", "julc.sourceMap", "julc.blueprint", "julc.target", "julc.optimization", "julc.costProfile", "julc.frontend"})
public class JulcAnnotationProcessor extends AbstractProcessor {

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

    /**
     * Libraries marked by a meta-annotated annotation: library FQCN -> marker annotation FQCN (ADR-071 D1). JuLC's own
     * compilation must see the marker's source too, which the validator need not reference.
     */
    private final Map<String, String> metaMarkers = new LinkedHashMap<>();

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
        return Set.of(
                "org.julclang.stdlib.annotation.SpendingValidator",
                "org.julclang.stdlib.annotation.MintingValidator",
                "org.julclang.stdlib.annotation.WithdrawValidator",
                "org.julclang.stdlib.annotation.CertifyingValidator",
                "org.julclang.stdlib.annotation.VotingValidator",
                "org.julclang.stdlib.annotation.ProposingValidator",
                "org.julclang.stdlib.annotation.MultiValidator",
                "org.julclang.stdlib.annotation.OnchainLibrary"
        );
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (processingFailed) {
            return true;
        }
        // 1. Collect same-project @OnchainLibrary sources
        collectSameProjectLibraries(roundEnv);

        // 2. Scan classpath for META-INF/plutus-sources/ (once)
        if (!classpathScanned) {
            scanClasspathLibraries();
            classpathScanned = true;
        }

        // 3. Process validator elements
        for (TypeElement annotation : annotations) {
            String annotationName = annotation.getQualifiedName().toString();
            if (annotationName.endsWith("OnchainLibrary")) {
                continue; // already collected above
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                processElement(element, annotation);
            }
        }

        // 4. Generate CIP-57 blueprint on final round
        if (roundEnv.processingOver() && blueprintEnabled && !processingFailed
                && !roundEnv.errorRaised() && !compiledValidators.isEmpty()) {
            generateBlueprint();
        }

        return true;
    }

    private void collectSameProjectLibraries(RoundEnvironment roundEnv) {
        var candidates = new LinkedHashSet<Element>(roundEnv.getElementsAnnotatedWith(
                findTypeElement(ONCHAIN_LIBRARY)));
        // ADR-071 D1: also classes carrying an annotation that is itself annotated @OnchainLibrary.
        for (Element root : roundEnv.getRootElements()) {
            collectMetaAnnotated(root, candidates);
        }
        for (Element element : candidates) {
            if (!(element instanceof TypeElement typeElement)) {
                continue;
            }
            String simpleName = typeElement.getSimpleName().toString();
            if (typeElement.getNestingKind() != NestingKind.TOP_LEVEL) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        onchainLibraryMarker(typeElement) + " makes " + simpleName + " an on-chain library, and"
                                + " @OnchainLibrary must be declared on a top-level class. Nested on-chain libraries are"
                                + " not supported.",
                        element);
                continue;
            }
            String fqcn = typeElement.getQualifiedName().toString();
            if (sameProjectLibraries.containsKey(fqcn)) {
                continue;
            }
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

    private static final String ONCHAIN_LIBRARY = "org.julclang.stdlib.annotation.OnchainLibrary";

    /** Adds {@code element} and its nested types that are meta-annotated on-chain libraries (ADR-071 D1). */
    private void collectMetaAnnotated(Element element, Set<Element> into) {
        if (!(element instanceof TypeElement type)) return;
        String marker = onchainLibraryMarker(type);
        if (marker != null && !marker.equals("@OnchainLibrary")) {
            into.add(type);
            recordMarker(type);
        }
        for (Element member : type.getEnclosedElements()) collectMetaAnnotated(member, into);
    }

    /** Remembers the meta-annotated marker of {@code type}, and its source if it is in this compilation. */
    private void recordMarker(TypeElement type) {
        for (var mirror : type.getAnnotationMirrors()) {
            var annotation = (TypeElement) mirror.getAnnotationType().asElement();
            boolean meta = annotation.getAnnotationMirrors().stream().anyMatch(m ->
                    ((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().contentEquals(ONCHAIN_LIBRARY));
            if (!meta) continue;
            String markerFqcn = annotation.getQualifiedName().toString();
            metaMarkers.put(type.getQualifiedName().toString(), markerFqcn);
            var path = trees.getPath(annotation);
            if (path != null && !sameProjectLibraries.containsKey(markerFqcn)) {
                try {
                    sameProjectLibraries.put(markerFqcn, LibrarySourceResolver.librarySourceFromSimpleName(
                            annotation.getSimpleName().toString(),
                            path.getCompilationUnit().getSourceFile().getCharContent(true).toString()));
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                            "Could not read source for annotation " + markerFqcn + ": " + e.getMessage(), annotation);
                }
            }
        }
    }

    /**
     * The annotation that makes {@code type} an on-chain library, as source text ({@code @OnchainLibrary} or
     * {@code @StateContract}), or null. One level of meta-annotation (ADR-071 D1).
     */
    private static String onchainLibraryMarker(TypeElement type) {
        for (var mirror : type.getAnnotationMirrors()) {
            var annotation = (TypeElement) mirror.getAnnotationType().asElement();
            if (annotation.getQualifiedName().contentEquals(ONCHAIN_LIBRARY)) return "@OnchainLibrary";
            for (var meta : annotation.getAnnotationMirrors()) {
                if (((TypeElement) meta.getAnnotationType().asElement()).getQualifiedName().contentEquals(ONCHAIN_LIBRARY)) {
                    return "@" + annotation.getSimpleName();
                }
            }
        }
        return null;
    }

    /**
     * On-demand discovery (ADR-071 D1): classes of this compilation that {@code source} refers to (imports and
     * same-package references), and that are on-chain libraries, are added to the same-project pool, transitively.
     * A processor is only invoked in a round that holds one of its annotations, so a library whose only marker is a
     * framework annotation may have been entered in a round this processor never saw.
     */
    private void discoverReferencedLibraries(String source) {
        var queue = new ArrayDeque<String>();
        queue.add(source);
        var seen = new HashSet<String>();
        while (!queue.isEmpty()) {
            String text = queue.poll();
            String pkg = LibrarySourceResolver.extractPackageName(text);
            var names = new LinkedHashSet<String>(LibrarySourceResolver.extractImportPaths(text).values());
            for (String simple : LibrarySourceResolver.extractReferencedClassNames(text)) {
                names.add(pkg.isEmpty() ? simple : pkg + "." + simple);
            }
            for (String fqcn : names) {
                if (!seen.add(fqcn) || sameProjectLibraries.containsKey(fqcn)) continue;
                TypeElement type = processingEnv.getElementUtils().getTypeElement(fqcn);
                if (type == null || type.getNestingKind() != NestingKind.TOP_LEVEL || onchainLibraryMarker(type) == null) {
                    continue;
                }
                var path = trees.getPath(type);
                if (path == null) continue;   // not a source of this compilation (class-path libraries come from jars)
                try {
                    String librarySource = path.getCompilationUnit().getSourceFile().getCharContent(true).toString();
                    sameProjectLibraries.put(fqcn, LibrarySourceResolver.librarySourceFromSimpleName(
                            type.getSimpleName().toString(), librarySource));
                    recordMarker(type);
                    queue.add(librarySource);
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                            "Could not read source for on-chain library " + fqcn + ": " + e.getMessage(), type);
                }
            }
        }
    }

    private TypeElement findTypeElement(String qualifiedName) {
        return processingEnv.getElementUtils().getTypeElement(qualifiedName);
    }

    private void scanClasspathLibraries() {
        classpathLibraries.putAll(LibrarySourceResolver.scanClasspathSources(getClass().getClassLoader(),
                problem -> processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "JuLC skipped an on-chain library source on the processor path: " + problem)));
    }

    private void processElement(Element element, TypeElement annotation) {
        String className = element.getSimpleName().toString();
        try {
            // 1. Read source file via Trees API
            var path = trees.getPath(element);
            if (path == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Cannot find source for " + className, element);
                return;
            }
            String source = path.getCompilationUnit().getSourceFile().getCharContent(true).toString();

            // 2. Resolve library sources needed by this validator (discovering meta-annotated ones on demand)
            discoverReferencedLibraries(source);
            List<String> librarySources = resolveLibrarySources(source);

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
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Plutus compilation failed for " + className + ": " + result.diagnostics(),
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
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Plutus compilation error: " + e.describe(), element);
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
        var resolved = new ArrayList<>(LibrarySourceResolver.resolveLibrarySources(validatorSource, pool));
        // ADR-071 D1: a meta-annotated library's marker annotation, which nothing may reference by import.
        var names = new HashSet<String>();
        resolved.forEach(library -> names.add(library.fqcn()));
        for (var library : List.copyOf(resolved)) {
            String marker = metaMarkers.get(library.fqcn());
            if (marker != null && names.add(marker) && pool.containsKey(marker)) resolved.add(pool.get(marker));
        }
        return resolved.stream().map(LibrarySource::source).toList();
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
