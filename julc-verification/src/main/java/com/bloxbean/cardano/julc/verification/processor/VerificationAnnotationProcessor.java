package com.bloxbean.cardano.julc.verification.processor;

import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;
import com.bloxbean.cardano.julc.verification.annotation.Monotonic;
import com.bloxbean.cardano.julc.verification.annotation.PreservesValue;
import com.bloxbean.cardano.julc.verification.annotation.ControlledMint;

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
@SupportedAnnotationTypes({
        "com.bloxbean.cardano.julc.verification.annotation.RequiresSigner",
        "com.bloxbean.cardano.julc.verification.annotation.Monotonic",
        "com.bloxbean.cardano.julc.verification.annotation.PreservesValue",
        "com.bloxbean.cardano.julc.verification.annotation.ControlledMint"
})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class VerificationAnnotationProcessor extends AbstractProcessor {
    private static final Pattern DATUM_PATH =
            Pattern.compile("datum\\.[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern REDEEMER_PATH =
            Pattern.compile("redeemer\\.[A-Za-z_$][A-Za-z0-9_$]*");

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
            if (!DATUM_PATH.matcher(value).matches()) {
                error("@RequiresSigner path must be exactly datum.<field>; found '"
                        + value + "'", element, mirror);
            }
        }
        var profileElements = new java.util.LinkedHashSet<Element>();
        profileElements.addAll(roundEnv.getElementsAnnotatedWith(Monotonic.class));
        profileElements.addAll(roundEnv.getElementsAnnotatedWith(PreservesValue.class));
        for (Element element : profileElements) {
            AnnotationMirror profileMirror = element.getAnnotationMirrors().stream()
                    .filter(candidate -> Set.of("Monotonic", "PreservesValue").contains(
                            candidate.getAnnotationType().asElement()
                                    .getSimpleName().toString()))
                    .findFirst().orElse(null);
            if (!hasAnnotation(element, "SpendingValidator")) {
                error("Stateful verification profile requires @SpendingValidator",
                        element, profileMirror);
            }
            if (element.getAnnotation(Monotonic.class) == null
                    || element.getAnnotation(PreservesValue.class) == null
                    || element.getAnnotation(RequiresSigner.class) == null) {
                error("Stateful verification profile requires @RequiresSigner, "
                        + "@Monotonic, and @PreservesValue", element, profileMirror);
                continue;
            }
            Monotonic monotonic = element.getAnnotation(Monotonic.class);
            if (!DATUM_PATH.matcher(monotonic.current()).matches()) {
                error("@Monotonic current must be exactly datum.<field>",
                        element, profileMirror);
            }
            if (!REDEEMER_PATH.matcher(monotonic.next()).matches()) {
                error("@Monotonic next must be exactly redeemer.<field>",
                        element, profileMirror);
            }
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(ControlledMint.class)) {
            AnnotationMirror mirror = annotationMirror(element, "ControlledMint");
            if (!hasAnnotation(element, "MintingValidator")) {
                error("@ControlledMint requires @MintingValidator", element, mirror);
            }
            ControlledMint controlled = element.getAnnotation(ControlledMint.class);
            if (!controlled.authority().matches("(?i)[0-9a-f]{56}")) {
                error("@ControlledMint authority must be exactly 28 hexadecimal bytes",
                        element, mirror);
            }
            if (!controlled.tokenName().matches("(?i)(?:[0-9a-f]{2}){0,32}")) {
                error("@ControlledMint tokenName must be 0 to 32 hexadecimal bytes",
                        element, mirror);
            }
            if (controlled.quantity() <= 0) {
                error("@ControlledMint quantity must be strictly positive", element, mirror);
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
        return annotationMirror(element, "RequiresSigner");
    }

    private AnnotationMirror annotationMirror(Element element, String name) {
        return element.getAnnotationMirrors().stream()
                .filter(mirror -> mirror.getAnnotationType().asElement()
                        .getSimpleName().contentEquals(name))
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
