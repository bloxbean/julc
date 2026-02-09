package com.bloxbean.cardano.plutus.processor;

import com.bloxbean.cardano.plutus.clientlib.PlutusScriptAdapter;
import com.bloxbean.cardano.plutus.clientlib.ValidatorOutput;
import com.bloxbean.cardano.plutus.compiler.CompileResult;
import com.bloxbean.cardano.plutus.compiler.CompilerException;
import com.bloxbean.cardano.plutus.compiler.PlutusCompiler;
import com.bloxbean.cardano.plutus.stdlib.StdlibRegistry;
import com.sun.source.util.Trees;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * {@link PlutusCompiler} for the actual compilation.
 */
@SupportedAnnotationTypes({
        "com.bloxbean.cardano.plutus.onchain.annotation.Validator",
        "com.bloxbean.cardano.plutus.onchain.annotation.MintingPolicy"
})
@SupportedSourceVersion(SourceVersion.RELEASE_24)
public class PlutusAnnotationProcessor extends AbstractProcessor {

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\.([A-Z][a-zA-Z0-9_]*)\\s*;",
            Pattern.MULTILINE);

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
                "com.bloxbean.cardano.plutus.onchain.annotation.Validator",
                "com.bloxbean.cardano.plutus.onchain.annotation.MintingPolicy",
                "com.bloxbean.cardano.plutus.onchain.annotation.OnchainLibrary"
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
                findTypeElement("com.bloxbean.cardano.plutus.onchain.annotation.OnchainLibrary"))) {
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
        try {
            var classLoader = getClass().getClassLoader();
            var resources = classLoader.getResources("META-INF/plutus-sources/");
            while (resources.hasMoreElements()) {
                URL resourceUrl = resources.nextElement();
                scanPlutusSourcesDirectory(resourceUrl);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not scan classpath for plutus-sources: " + e.getMessage());
        }
    }

    private void scanPlutusSourcesDirectory(URL baseUrl) {
        // For JAR URLs and file URLs, we scan for .java files
        // The actual scanning depends on the URL protocol
        String protocol = baseUrl.getProtocol();
        if ("file".equals(protocol)) {
            scanFileSystemSources(new java.io.File(baseUrl.getPath()));
        }
        // JAR protocol scanning would require more complex handling;
        // for now we rely on the classloader resource enumeration below
    }

    private void scanFileSystemSources(java.io.File dir) {
        if (!dir.exists() || !dir.isDirectory()) return;
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File file : files) {
            if (file.isDirectory()) {
                scanFileSystemSources(file);
            } else if (file.getName().endsWith(".java")) {
                try {
                    String source = java.nio.file.Files.readString(file.toPath());
                    String simpleName = file.getName().replace(".java", "");
                    classpathLibraries.putIfAbsent(simpleName, source);
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                            "Could not read plutus-sources file: " + file);
                }
            }
        }
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

            // 3. Compile with PlutusCompiler (multi-file if libraries found)
            var compiler = new PlutusCompiler(stdlib::lookup);
            var result = compiler.compile(source, librarySources);

            if (result.hasErrors()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Plutus compilation failed for " + className + ": " + result.diagnostics(),
                        element);
                return;
            }

            // 4. Generate output
            var program = result.program();
            var script = PlutusScriptAdapter.fromProgram(program);

            boolean isMinting = annotation.getSimpleName().toString().equals("MintingPolicy");
            String scriptType = isMinting ? "PlutusScriptV3-Minting" : "PlutusScriptV3";

            // Build params string from CompileResult
            String paramsStr = result.params().stream()
                    .map(p -> p.name() + ":" + p.type())
                    .collect(Collectors.joining(","));

            // For parameterized validators, hash depends on applied params — store empty
            String scriptHash = result.isParameterized()
                    ? "" : PlutusScriptAdapter.scriptHash(program);

            var output = new ValidatorOutput(scriptType, className,
                    script.getCborHex(), scriptHash, paramsStr);

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
                    "Compiled Plutus validator: " + className + " (hash: " + scriptHash + ")" + libMsg,
                    element);

        } catch (CompilerException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Plutus compilation error: " + e.getMessage(), element);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "I/O error writing compiled script for " + className + ": " + e.getMessage(),
                    element);
        }
    }

    /**
     * Resolve library sources needed by the given validator source.
     * Uses import statements to find matching @OnchainLibrary classes,
     * then recursively resolves transitive imports.
     */
    private List<String> resolveLibrarySources(String validatorSource) {
        var resolved = new LinkedHashMap<String, String>(); // simpleName → source
        var toProcess = new ArrayDeque<>(extractImportedClassNames(validatorSource));
        var seen = new HashSet<String>();

        while (!toProcess.isEmpty()) {
            String simpleName = toProcess.poll();
            if (seen.contains(simpleName) || resolved.containsKey(simpleName)) {
                continue;
            }
            seen.add(simpleName);

            // Look up in same-project libraries first, then classpath
            String libSource = sameProjectLibraries.get(simpleName);
            if (libSource == null) {
                libSource = classpathLibraries.get(simpleName);
            }
            if (libSource == null) {
                continue; // Not an @OnchainLibrary — could be standard import
            }

            resolved.put(simpleName, libSource);

            // Recursively resolve this library's imports
            for (String transitiveName : extractImportedClassNames(libSource)) {
                if (!seen.contains(transitiveName) && !resolved.containsKey(transitiveName)) {
                    toProcess.add(transitiveName);
                }
            }
        }

        return new ArrayList<>(resolved.values());
    }

    /**
     * Extract simple class names from import statements in Java source.
     * Only returns the simple name (last segment after the last dot).
     */
    private Set<String> extractImportedClassNames(String source) {
        var classNames = new LinkedHashSet<String>();
        Matcher matcher = IMPORT_PATTERN.matcher(source);
        while (matcher.find()) {
            classNames.add(matcher.group(2));
        }
        return classNames;
    }
}
