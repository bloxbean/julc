package com.bloxbean.cardano.julc.processor;

import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.ValidatorOutput;
import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;
import com.sun.source.util.Trees;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Annotation processor that compiles {@code @Validator} and {@code @MintingPolicy}
 * annotated classes to UPLC scripts during javac.
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
        "com.bloxbean.cardano.julc.stdlib.annotation.Validator",
        "com.bloxbean.cardano.julc.stdlib.annotation.MintingPolicy",
        "com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator",
        "com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator",
        "com.bloxbean.cardano.julc.stdlib.annotation.WithdrawValidator",
        "com.bloxbean.cardano.julc.stdlib.annotation.CertifyingValidator",
        "com.bloxbean.cardano.julc.stdlib.annotation.VotingValidator",
        "com.bloxbean.cardano.julc.stdlib.annotation.ProposingValidator",
        "com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator"
})
@SupportedSourceVersion(SourceVersion.RELEASE_24)
public class JulcAnnotationProcessor extends AbstractProcessor {

    private Trees trees;
    private StdlibRegistry stdlib;

    /** Same-project @OnchainLibrary sources: simple name → source string */
    private final Map<String, String> sameProjectLibraries = new LinkedHashMap<>();

    /** Classpath library sources: simple name → source string */
    private final Map<String, String> classpathLibraries = new LinkedHashMap<>();

    /** Whether classpath scanning has been done */
    private boolean classpathScanned = false;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.trees = Trees.instance(processingEnv);
        this.stdlib = StdlibRegistry.defaultRegistry();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(
                "com.bloxbean.cardano.julc.stdlib.annotation.Validator",
                "com.bloxbean.cardano.julc.stdlib.annotation.MintingPolicy",
                "com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator",
                "com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator",
                "com.bloxbean.cardano.julc.stdlib.annotation.WithdrawValidator",
                "com.bloxbean.cardano.julc.stdlib.annotation.CertifyingValidator",
                "com.bloxbean.cardano.julc.stdlib.annotation.VotingValidator",
                "com.bloxbean.cardano.julc.stdlib.annotation.ProposingValidator",
                "com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator",
                "com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary"
        );
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // 1. Collect same-project @OnchainLibrary sources
        collectSameProjectLibraries(roundEnv);

        // 2. Scan classpath for META-INF/plutus-sources/ (once)
        if (!classpathScanned) {
            scanClasspathLibraries();
            classpathScanned = true;
        }

        // 3. Process @Validator and @MintingPolicy elements
        for (TypeElement annotation : annotations) {
            String annotationName = annotation.getQualifiedName().toString();
            if (annotationName.endsWith("OnchainLibrary")) {
                continue; // already collected above
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                processElement(element, annotation);
            }
        }
        return true;
    }

    private void collectSameProjectLibraries(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(
                findTypeElement("com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary"))) {
            String simpleName = element.getSimpleName().toString();
            if (sameProjectLibraries.containsKey(simpleName)) {
                continue;
            }
            try {
                var path = trees.getPath(element);
                if (path != null) {
                    String source = path.getCompilationUnit().getSourceFile()
                            .getCharContent(true).toString();
                    sameProjectLibraries.put(simpleName, source);
                }
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Could not read source for @OnchainLibrary class: " + simpleName, element);
            }
        }
    }

    private TypeElement findTypeElement(String qualifiedName) {
        return processingEnv.getElementUtils().getTypeElement(qualifiedName);
    }

    private void scanClasspathLibraries() {
        classpathLibraries.putAll(
                LibrarySourceResolver.scanClasspathSources(getClass().getClassLoader()));
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

            // 2. Resolve library sources needed by this validator
            List<String> librarySources = resolveLibrarySources(source);

            // 3. Compile with JulcCompiler (multi-file if libraries found)
            var compiler = new JulcCompiler(stdlib::lookup);
            var result = compiler.compile(source, librarySources);

            if (result.hasErrors()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Plutus compilation failed for " + className + ": " + result.diagnostics(),
                        element);
                return;
            }

            // 4. Generate output
            var program = result.program();
            var script = JulcScriptAdapter.fromProgram(program);

            String scriptType = resolveScriptType(annotation.getSimpleName().toString());

            // Build params string from CompileResult
            String paramsStr = result.params().stream()
                    .map(p -> p.name() + ":" + p.type())
                    .collect(Collectors.joining(","));

            // For parameterized validators, hash depends on applied params — store empty
            String scriptHash = result.isParameterized()
                    ? "" : JulcScriptAdapter.scriptHash(program);

            int sizeBytes = result.scriptSizeBytes();
            String sizeStr = result.scriptSizeFormatted();

            var output = new ValidatorOutput(scriptType, className,
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

            String libMsg = librarySources.isEmpty() ? "" : " (with " + librarySources.size() + " library file(s))";
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Compiled Plutus validator: " + className + " (hash: " + scriptHash + ", size: " + sizeStr + ")" + libMsg,
                    element);

        } catch (CompilerException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Plutus compilation error: " + e.getMessage(), element);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "I/O error writing compiled script for " + className + ": " + e.getMessage(),
                    element);
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Unexpected compilation error for " + className + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    element);
        }
    }

    /**
     * Resolve library sources needed by the given validator source.
     * Uses import statements and same-package references to find matching
     * {@code @OnchainLibrary} classes, then recursively resolves transitive imports.
     */
    private List<String> resolveLibrarySources(String validatorSource) {
        // Merge same-project + classpath into one pool (same-project takes precedence)
        var pool = new LinkedHashMap<>(classpathLibraries);
        pool.putAll(sameProjectLibraries);

        // Add same-package libraries that aren't explicitly imported
        String validatorPkg = LibrarySourceResolver.extractPackageName(validatorSource);
        if (!validatorPkg.isEmpty()) {
            for (var entry : sameProjectLibraries.entrySet()) {
                String libPkg = LibrarySourceResolver.extractPackageName(entry.getValue());
                if (validatorPkg.equals(libPkg)) {
                    pool.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return LibrarySourceResolver.resolve(validatorSource, pool);
    }

    private String resolveScriptType(String annotationSimpleName) {
        return switch (annotationSimpleName) {
            case "Validator", "SpendingValidator" -> "PlutusScriptV3";
            case "MintingPolicy", "MintingValidator" -> "PlutusScriptV3-Minting";
            case "WithdrawValidator" -> "PlutusScriptV3-Withdraw";
            case "CertifyingValidator" -> "PlutusScriptV3-Certifying";
            case "VotingValidator" -> "PlutusScriptV3-Voting";
            case "ProposingValidator" -> "PlutusScriptV3-Proposing";
            case "MultiValidator" -> "PlutusScriptV3";
            default -> "PlutusScriptV3";
        };
    }
}
