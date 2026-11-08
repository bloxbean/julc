package org.julclang.compiler;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import org.julclang.compiler.JavaSourceIntrospector.AnnotatedType;
import org.julclang.compiler.JavaSourceIntrospector.RoleConflict;
import org.julclang.compiler.JavaSourceIntrospector.SourceInfo;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import org.julclang.compiler.frontend.javac.SourceOrderScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link JavaSourceIntrospector}'s reading on javac's parser (ADR-068 M6): the same {@link SourceInfo} from javac's syntax
 * trees. A type declaration is a named {@link ClassTree} of any kind (class, interface, enum, record, annotation type),
 * at any depth; an anonymous class is none. An annotation is matched by its simple name, as written.
 */
final class JavacSourceIntrospection {

    private JavacSourceIntrospection() {
    }

    static SourceInfo inspect(String source) {
        var syntax = JavacSyntax.read(source);
        if (syntax.hasErrors()) {
            throw new JavaSourceIntrospector.SourceParseException(syntax.errorMessages());
        }
        CompilationUnitTree unit = syntax.unit();
        String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        var topLevel = new ArrayList<ClassTree>();
        for (Tree declaration : unit.getTypeDecls()) {
            if (declaration instanceof ClassTree type) topLevel.add(type);
        }
        var topLevelTypeNames = topLevel.stream().map(t -> t.getSimpleName().toString()).toList();
        var all = types(unit);
        var validatorType = firstAnnotated(all, topLevel, packageName,
                JavaSourceIntrospector.SUPPORTED_VALIDATOR_ANNOTATIONS);
        var legacyValidatorType = firstAnnotated(all, topLevel, packageName,
                JavaSourceIntrospector.LEGACY_VALIDATOR_ANNOTATIONS);
        var topLevelOnchainLibrary = firstAnnotated(all, topLevel, packageName,
                JavaSourceIntrospector.LIBRARY_ANNOTATIONS);

        var nestedOnchainLibraries = new ArrayList<AnnotatedType>();
        var roleConflicts = new ArrayList<RoleConflict>();
        for (TreePath path : all) {
            var type = (ClassTree) path.getLeaf();
            var names = annotationNames(type);
            var library = names.stream().filter(JavaSourceIntrospector::isLibraryAnnotation).findFirst();
            if (!topLevel.contains(type) && library.isPresent()) {
                nestedOnchainLibraries.add(annotatedType(path, packageName, library.get(), false));
            }
            if (library.isPresent()) {
                var conflicting = new ArrayList<String>();
                names.stream().filter(JavaSourceIntrospector::isSupportedValidatorAnnotation).forEach(conflicting::add);
                names.stream().filter(JavaSourceIntrospector::isLegacyValidatorAnnotation).forEach(conflicting::add);
                if (!conflicting.isEmpty()) {
                    String simpleName = type.getSimpleName().toString();
                    roleConflicts.add(new RoleConflict(simpleName, qualifiedName(path).orElse(simpleName),
                            List.copyOf(conflicting)));
                }
            }
        }
        return new SourceInfo(packageName, topLevelTypeNames, validatorType, legacyValidatorType,
                topLevelOnchainLibrary, List.copyOf(nestedOnchainLibraries), List.copyOf(roleConflicts));
    }

    /**
     * Whether a source javac rejects plainly declares no JuLC role: only its language level is wrong, and no type in it
     * carries a validator or library annotation. False when that cannot be decided, so the caller keeps the error.
     */
    static boolean declaresNoJulcRole(String source) {
        var syntax = JavacSyntax.read(source);
        if (!syntax.hasErrors() || !syntax.onlyLanguageLevelErrors()) return false;
        return types(syntax.unit()).stream()
                .flatMap(path -> annotationNames((ClassTree) path.getLeaf()).stream())
                .noneMatch(name -> JavaSourceIntrospector.isSupportedValidatorAnnotation(name)
                        || JavaSourceIntrospector.isLegacyValidatorAnnotation(name)
                        || JavaSourceIntrospector.isLibraryAnnotation(name));
    }


    /** Every named type declaration, the unit's first, in pre-order. */
    private static List<TreePath> types(CompilationUnitTree unit) {
        var found = new ArrayList<TreePath>();
        new SourceOrderScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                if (!tree.getSimpleName().isEmpty()) found.add(getCurrentPath());
                return super.visitClass(tree, unused);
            }
        }.scan(unit, null);
        return found;
    }

    /** The first top-level type, in source order, that carries one of {@code names}, with the first of them it carries. */
    private static Optional<AnnotatedType> firstAnnotated(List<TreePath> all, List<ClassTree> topLevel, String packageName,
                                                          List<String> names) {
        for (ClassTree type : topLevel) {
            var carried = annotationNames(type);
            for (String name : names) {
                if (carried.contains(name)) {
                    TreePath path = all.stream().filter(p -> p.getLeaf() == type).findFirst().orElseThrow();
                    return Optional.of(annotatedType(path, packageName, name, true));
                }
            }
        }
        return Optional.empty();
    }

    private static AnnotatedType annotatedType(TreePath path, String packageName, String annotationName,
                                               boolean topLevel) {
        var type = (ClassTree) path.getLeaf();
        String simpleName = type.getSimpleName().toString();
        String fqcn = qualifiedName(path)
                .orElse(packageName.isBlank() ? simpleName : packageName + "." + simpleName);
        return new AnnotatedType(simpleName, packageName, fqcn, annotationName, annotationNames(type), topLevel);
    }

    private static Optional<String> qualifiedName(TreePath path) {
        return JavacSyntax.qualifiedName(path);
    }

    /** The simple names of a type's annotations, in source order. */
    private static List<String> annotationNames(ClassTree type) {
        return type.getModifiers().getAnnotations().stream().map(JavacSourceIntrospection::simpleName).toList();
    }

    private static String simpleName(AnnotationTree annotation) {
        String name = annotation.getAnnotationType().toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
