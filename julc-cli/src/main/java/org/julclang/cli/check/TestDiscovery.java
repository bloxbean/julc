package org.julclang.cli.check;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import org.julclang.compiler.JavaSourceIntrospector;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import org.julclang.compiler.frontend.javac.SourceOrderScanner;

import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers @Test-annotated static methods in test/ source files.
 */
public final class TestDiscovery {

    private TestDiscovery() {}

    public record TestMethod(String sourceFile, String className, String methodName, String source) {}
    /**
     * Scan a directory for test methods.
     * A test method is a static boolean method annotated with @Test.
     */
    public static List<TestMethod> discover(Path testDir) throws IOException {
        var results = new ArrayList<TestMethod>();

        if (!Files.isDirectory(testDir)) {
            return results;
        }

        try (Stream<Path> paths = Files.walk(testDir)) {
            paths.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            results.addAll(discover(p, Files.readString(p)));
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read " + p, e);
                        }
                    });
        }

        return results;
    }

    /**
     * The test methods of one source, in source order: the classes and interfaces at any depth, the outer first, and
     * each one's own methods. Throws {@link IllegalArgumentException} when a source that names {@code @Test} cannot
     * be read.
     */
    public static List<TestMethod> discover(Path path, String source) {
        if (!JavaSourceIntrospector.mightContainTestAnnotation(source)) return List.of();
        return discoverWithJavac(path, source);
    }

    /**
     * javac's reading: the class and interface declarations (not records, enums or annotation types, and no anonymous
     * class), with their explicit modifiers, as written, in source order. A source javac's parser rejects is an
     * error, unless its only errors are of a later Java and no method in it carries {@code @Test}: then it only mentions
     * the annotation, as the JavaParser reading's lenient fallback decided.
     */
    private static List<TestMethod> discoverWithJavac(Path p, String source) {
        var syntax = JavacSyntax.read(source);
        var results = new ArrayList<TestMethod>();
        new SourceOrderScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree cls, Void unused) {
                boolean classOrInterface = cls.getKind() == Tree.Kind.CLASS || cls.getKind() == Tree.Kind.INTERFACE;
                if (classOrInterface && !cls.getSimpleName().isEmpty()) {
                    for (Tree member : cls.getMembers()) {
                        if (member instanceof MethodTree method && isTest(method)) {
                            results.add(new TestMethod(p.toString(), cls.getSimpleName().toString(),
                                    method.getName().toString(), source));
                        }
                    }
                }
                return super.visitClass(cls, unused);
            }
        }.scan(syntax.unit(), null);
        if (syntax.hasErrors()) {
            // Later Java in a source with no Test method only mentions the annotation: skip it, as the project scan does.
            if (syntax.onlyLanguageLevelErrors() && results.isEmpty() && !declaresTestMethod(syntax)) return List.of();
            throw new IllegalArgumentException("Could not parse @Test candidate " + p + ": " + syntax.errorMessages());
        }
        return results;
    }

    /** Whether any method, of any type or none, carries an annotation named {@code Test}. */
    private static boolean declaresTestMethod(JavacSyntax syntax) {
        boolean[] found = {false};
        new SourceOrderScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree method, Void unused) {
                if (method.getModifiers().getAnnotations().stream().anyMatch(TestDiscovery::isTestAnnotation)) found[0] = true;
                return super.visitMethod(method, unused);
            }
        }.scan(syntax.unit(), null);
        return found[0];
    }

    private static boolean isTest(MethodTree method) {
        var flags = method.getModifiers().getFlags();
        return flags.contains(Modifier.PUBLIC) && flags.contains(Modifier.STATIC)
                && method.getReturnType() instanceof PrimitiveTypeTree type
                && type.getPrimitiveTypeKind() == TypeKind.BOOLEAN
                && method.getModifiers().getAnnotations().stream().anyMatch(TestDiscovery::isTestAnnotation);
    }

    private static boolean isTestAnnotation(AnnotationTree annotation) {
        String name = annotation.getAnnotationType().toString();
        return name.substring(name.lastIndexOf('.') + 1).equals("Test");
    }
}
