package com.bloxbean.cardano.julc.verification.processor;

import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Set;
import java.util.regex.Pattern;

/** Fast javac diagnostics; {@code julc verify} performs authoritative PIR resolution. */
@SupportedAnnotationTypes("com.bloxbean.cardano.julc.verification.annotation.RequiresSigner")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class VerificationAnnotationProcessor extends AbstractProcessor {
    private static final Pattern C5_PATH =
            Pattern.compile("datum\\.[A-Za-z_$][A-Za-z0-9_$]*");

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(RequiresSigner.class)) {
            AnnotationMirror mirror = requiresSignerMirror(element);
            if (!(element.getKind().isClass() || element.getKind() == ElementKind.RECORD)) {
                error("@RequiresSigner may annotate only a validator type", element, mirror);
                continue;
            }
            if (!hasAnnotation(element, "SpendingValidator")) {
                error("@RequiresSigner requires @SpendingValidator", element, mirror);
            }
            String value = element.getAnnotation(RequiresSigner.class).value();
            if (!C5_PATH.matcher(value).matches()) {
                error("@RequiresSigner path must be exactly datum.<field>; found '"
                        + value + "'", element, mirror);
            }
        }
        return false;
    }

    private boolean hasAnnotation(Element element, String simpleName) {
        return element.getAnnotationMirrors().stream()
                .map(mirror -> mirror.getAnnotationType().asElement().getSimpleName().toString())
                .anyMatch(simpleName::equals);
    }

    private AnnotationMirror requiresSignerMirror(Element element) {
        return element.getAnnotationMirrors().stream()
                .filter(mirror -> mirror.getAnnotationType().asElement()
                        .getSimpleName().contentEquals("RequiresSigner"))
                .findFirst().orElse(null);
    }

    private void error(String message, Element element, AnnotationMirror mirror) {
        if (mirror == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
        } else {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR, message, element, mirror);
        }
    }
}
