package com.bloxbean.cardano.plutus.processor;

import com.bloxbean.cardano.plutus.clientlib.PlutusScriptAdapter;
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
import java.io.IOException;
import java.io.Writer;
import java.util.Set;

/**
 * Annotation processor that compiles {@code @Validator} and {@code @MintingPolicy}
 * annotated classes to UPLC scripts during javac.
 * <p>
 * The compiled script is written to {@code META-INF/plutus/<ClassName>.plutus.json}
 * in the class output directory, making it available on the classpath at runtime.
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

    private Trees trees;
    private StdlibRegistry stdlib;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.trees = Trees.instance(processingEnv);
        this.stdlib = StdlibRegistry.defaultRegistry();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                processElement(element, annotation);
            }
        }
        return true;
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

            // 2. Compile with PlutusCompiler
            var compiler = new PlutusCompiler(stdlib::lookup);
            var result = compiler.compile(source);

            if (result.hasErrors()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Plutus compilation failed for " + className + ": " + result.diagnostics(),
                        element);
                return;
            }

            // 3. Generate output
            var program = result.program();
            var script = PlutusScriptAdapter.fromProgram(program);
            String scriptHash = PlutusScriptAdapter.scriptHash(program);

            boolean isMinting = annotation.getSimpleName().toString().equals("MintingPolicy");
            String scriptType = isMinting ? "PlutusScriptV3-Minting" : "PlutusScriptV3";

            var output = new ValidatorOutput(scriptType, className,
                    script.getCborHex(), scriptHash);

            // 4. Write to META-INF/plutus/<ClassName>.plutus.json
            var filer = processingEnv.getFiler();
            var resource = filer.createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    "META-INF/plutus/" + className + ".plutus.json",
                    element);

            try (Writer writer = resource.openWriter()) {
                writer.write(output.toJson());
            }

            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Compiled Plutus validator: " + className + " (hash: " + scriptHash + ")",
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
}
