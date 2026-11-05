package org.julclang.tools.java;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import org.julclang.compiler.frontend.javac.SourceOrderScanner;
import org.julclang.compiler.JavaSourceIntrospector;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import org.julclang.tools.model.CheckResponse;
import org.julclang.tools.model.DiagnosticDto;
import org.julclang.tools.model.FieldDto;
import org.julclang.tools.model.VariantDto;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link JavaMetadataExtractor}'s reading on javac's syntax trees (ADR-068 M6): the same validator, purpose,
 * {@code @Param} fields, datum and redeemer, found by the same rules, read from javac's parser: a class or interface declaration is a named {@link ClassTree} of kind {@code CLASS} or
 * {@code INTERFACE}; a record's parameters are its instance fields, which javac's parser lists first among its members;
 * a type prints as {@link JavacSyntax#typeName} prints it, as JavaParser's {@code asString} did.
 * <p>
 * The subset's diagnostics are the javac frontend's ({@link JulcCompiler#subsetDiagnostics}), as a compilation reports them, where
 * the JavaParser reading runs the JavaParser frontend's validator.
 */
final class JavacMetadataReading {

    private JavacMetadataReading() {
    }

    static CheckResponse extract(String source, List<String> validatorAnnotations, JulcCompiler compiler,
                                 List<String> librarySources) {
        var syntax = JavacSyntax.read(source);
        if (syntax.hasErrors()) {
            var diagnostics = new ArrayList<DiagnosticDto>();
            for (var error : syntax.errors()) {
                long at = error.getPosition();
                diagnostics.add(new DiagnosticDto("ERROR", null, error.getMessage(java.util.Locale.ENGLISH),
                        at < 0 ? null : syntax.line(at), at < 0 ? null : syntax.column(at), null, null, null));
            }
            return new CheckResponse(false, null, null, List.of(), null, List.of(), List.of(), List.of(), diagnostics);
        }
        // The subset's diagnostics first, as the JavaParser reading puts its validator's: the javac frontend's, which a
        // compilation reports before it lowers anything, with the same codes and positions.
        var diagnostics = new ArrayList<DiagnosticDto>();
        for (CompilerDiagnostic diagnostic : subset(source, compiler, librarySources)) diagnostics.add(DiagnosticDto.from(diagnostic));

        TreePath validatorClass = null;
        String annotationName = null;
        for (TreePath path : classesOrInterfaces(new TreePath(syntax.unit()))) {
            var type = (ClassTree) path.getLeaf();
            var roleConflict = roleConflictOn(path);
            if (roleConflict.isPresent()) {
                diagnostics.add(new DiagnosticDto("ERROR", null, roleConflict.get().message(),
                        null, null, null, null, null));
                return new CheckResponse(false, null, null, List.of(), null,
                        List.of(), List.of(), List.of(), diagnostics);
            }
            var legacy = annotationSimpleNames(type.getModifiers()).stream()
                    .filter(JavaSourceIntrospector::isLegacyValidatorAnnotation).toList();
            if (!legacy.isEmpty()) {
                diagnostics.add(new DiagnosticDto("ERROR", null,
                        JavaSourceIntrospector.legacyAnnotationMigrationMessage(legacy.getFirst(),
                                type.getSimpleName().toString()),
                        null, null, null, null, null));
                return new CheckResponse(false, null, null, List.of(), null,
                        List.of(), List.of(), List.of(), diagnostics);
            }
            for (var ann : validatorAnnotations) {
                if (annotationSimpleNames(type.getModifiers()).contains(ann)) {
                    validatorClass = path;
                    annotationName = ann;
                    break;
                }
            }
            if (validatorClass != null) break;
        }

        if (validatorClass == null) {
            diagnostics.add(new DiagnosticDto("ERROR", null,
                    "No validator annotation found. Add @SpendingValidator, @MintingValidator, etc.",
                    null, null, null, null, null));
            return new CheckResponse(false, null, null, List.of(), null, List.of(), List.of(), List.of(), diagnostics);
        }

        var cls = (ClassTree) validatorClass.getLeaf();
        String contractName = cls.getSimpleName().toString();
        String purpose = JavaMetadataExtractor.purposeOf(annotationName);
        List<FieldDto> params = extractParams(cls);

        MethodTree entrypoint = findEntrypoint(cls);
        if (entrypoint == null) {
            diagnostics.add(new DiagnosticDto("WARNING", null, "No @Entrypoint method found",
                    null, null, null, null, null));
            boolean hasErrors = diagnostics.stream().anyMatch(d -> "ERROR".equals(d.level()));
            return new CheckResponse(!hasErrors, contractName, purpose, params, null,
                    List.of(), List.of(), List.of(), diagnostics);
        }

        String datumName = null;
        List<FieldDto> datumFields = List.of();
        List<VariantDto> redeemerVariants = List.of();
        List<FieldDto> redeemerFields = List.of();

        var entrypointParams = entrypoint.getParameters();
        boolean isSpending = "SPENDING".equals(purpose);
        String redeemerType = null;
        if (isSpending && entrypointParams.size() >= 3) {
            String datumType = JavacSyntax.typeName(entrypointParams.get(0).getType());
            datumName = datumType;
            datumFields = extractRecordFields(syntax, validatorClass, datumType);
            redeemerType = JavacSyntax.typeName(entrypointParams.get(1).getType());
        } else if (isSpending && entrypointParams.size() == 2) {
            redeemerType = JavacSyntax.typeName(entrypointParams.get(0).getType());
        } else if (!isSpending && entrypointParams.size() >= 2) {
            redeemerType = JavacSyntax.typeName(entrypointParams.get(0).getType());
        }
        if (redeemerType != null) {
            var variants = extractVariants(syntax, validatorClass, redeemerType);
            if (!variants.isEmpty()) {
                redeemerVariants = variants;
            } else {
                redeemerFields = extractRecordFields(syntax, validatorClass, redeemerType);
            }
        }

        boolean hasErrors = diagnostics.stream().anyMatch(d -> "ERROR".equals(d.level()));
        return new CheckResponse(!hasErrors, contractName, purpose, params, datumName,
                datumFields, redeemerVariants, redeemerFields, diagnostics);
    }

    /** What {@code compiler} reports of the subset for {@code source} with {@code librarySources} on javac. */
    static List<CompilerDiagnostic> subset(String source, JulcCompiler compiler, List<String> librarySources) {
        return compiler.subsetDiagnostics(source, librarySources);
    }

    /** JavaParser's {@code findAll(ClassOrInterfaceDeclaration)}: named classes and interfaces, the root too, in pre-order. */
    private static List<TreePath> classesOrInterfaces(TreePath root) {
        return all(root, tree -> tree instanceof ClassTree c && !c.getSimpleName().isEmpty()
                && (c.getKind() == Tree.Kind.CLASS || c.getKind() == Tree.Kind.INTERFACE));
    }

    /** JavaParser's {@code findAll(RecordDeclaration)}, in pre-order. */
    private static List<TreePath> records(TreePath root) {
        return all(root, tree -> tree instanceof ClassTree c && c.getKind() == Tree.Kind.RECORD);
    }

    private static List<TreePath> all(TreePath root, java.util.function.Predicate<Tree> match) {
        var found = new ArrayList<TreePath>();
        if (match.test(root.getLeaf())) found.add(root);
        new SourceOrderScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null && match.test(tree)) found.add(new TreePath(getCurrentPath(), tree));
                return super.scan(tree, unused);
            }
        }.scan(root, null);
        return found;
    }

    private static Optional<JavaSourceIntrospector.RoleConflict> roleConflictOn(TreePath path) {
        var type = (ClassTree) path.getLeaf();
        var names = annotationSimpleNames(type.getModifiers());
        if (!names.contains(JavaSourceIntrospector.ONCHAIN_LIBRARY_ANNOTATION)) return Optional.empty();
        var conflicting = new ArrayList<String>();
        names.stream().filter(JavaSourceIntrospector::isSupportedValidatorAnnotation).forEach(conflicting::add);
        names.stream().filter(JavaSourceIntrospector::isLegacyValidatorAnnotation).forEach(conflicting::add);
        if (conflicting.isEmpty()) return Optional.empty();
        String simpleName = type.getSimpleName().toString();
        return Optional.of(new JavaSourceIntrospector.RoleConflict(simpleName, qualifiedName(path),
                List.copyOf(conflicting)));
    }

    /** JavaParser's fully qualified name ({@link JavacSyntax#qualifiedName}), else the simple name. */
    private static String qualifiedName(TreePath path) {
        return JavacSyntax.qualifiedName(path).orElse(((ClassTree) path.getLeaf()).getSimpleName().toString());
    }

    /** The annotations' simple names, as JuLC's introspector compares them. */
    private static List<String> annotationSimpleNames(ModifiersTree modifiers) {
        return modifiers.getAnnotations().stream().map(a -> {
            String name = writtenName(a);
            return name.substring(name.lastIndexOf('.') + 1);
        }).toList();
    }

    /** JavaParser's {@code getAnnotationByName}: the name as written, compared whole. */
    private static boolean hasAnnotationNamed(ModifiersTree modifiers, String name) {
        return modifiers.getAnnotations().stream().anyMatch(a -> writtenName(a).equals(name));
    }

    private static String writtenName(AnnotationTree annotation) {
        return dotted(annotation.getAnnotationType());
    }

    private static String dotted(Tree tree) {
        return switch (tree) {
            case IdentifierTree id -> id.getName().toString();
            case MemberSelectTree select -> dotted(select.getExpression()) + "." + select.getIdentifier();
            default -> tree.toString();
        };
    }

    /** The class's own fields annotated {@code @Param}, each variable. */
    private static List<FieldDto> extractParams(ClassTree cls) {
        var params = new ArrayList<FieldDto>();
        for (Tree member : cls.getMembers()) {
            if (member instanceof VariableTree field && hasAnnotationNamed(field.getModifiers(), "Param")) {
                params.add(new FieldDto(field.getName().toString(),
                        JavaMetadataExtractor.mapDisplayType(JavacSyntax.typeName(field.getType()))));
            }
        }
        return params;
    }

    /** The class's own first method annotated {@code @Entrypoint}, constructors excluded. */
    private static MethodTree findEntrypoint(ClassTree cls) {
        for (Tree member : cls.getMembers()) {
            if (member instanceof MethodTree method && !method.getName().contentEquals("<init>")
                    && hasAnnotationNamed(method.getModifiers(), "Entrypoint")) {
                return method;
            }
        }
        return null;
    }

    private static List<FieldDto> extractRecordFields(JavacSyntax syntax, TreePath cls, String typeName) {
        for (TreePath path : records(cls)) {
            var record = (ClassTree) path.getLeaf();
            if (record.getSimpleName().contentEquals(typeName)) return components(syntax, record);
        }
        return List.of();
    }

    /** A sealed interface's variants: an interface of the name, at any depth, the first with a member record. */
    private static List<VariantDto> extractVariants(JavacSyntax syntax, TreePath cls, String typeName) {
        for (TreePath path : classesOrInterfaces(cls)) {
            var inner = (ClassTree) path.getLeaf();
            if (!inner.getSimpleName().contentEquals(typeName) || inner.getKind() != Tree.Kind.INTERFACE) continue;
            var variants = new ArrayList<VariantDto>();
            int tag = 0;
            for (Tree member : inner.getMembers()) {
                if (member instanceof ClassTree record && record.getKind() == Tree.Kind.RECORD) {
                    variants.add(new VariantDto(record.getSimpleName().toString(), tag, components(syntax, record)));
                    tag++;
                }
            }
            if (!variants.isEmpty()) return variants;
        }
        return List.of();
    }

    /** A record's components: the instance fields javac's parser lists for them. */
    private static List<FieldDto> components(JavacSyntax syntax, ClassTree record) {
        var fields = new ArrayList<FieldDto>();
        for (Tree member : record.getMembers()) {
            if (member instanceof VariableTree component
                    && !component.getModifiers().getFlags().contains(Modifier.STATIC)) {
                String name = component.getName().toString();
                // JavaParser gives a variable-arity component its element type.
                Tree type = component.getType() instanceof ArrayTypeTree array && syntax.isVarargs(component)
                        ? array.getType() : component.getType();
                fields.add(new FieldDto(name, JavaMetadataExtractor.mapDisplayType(JavacSyntax.typeName(type), name)));
            }
        }
        return fields;
    }
}
