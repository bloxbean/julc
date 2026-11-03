package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFacts;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ADR-067's rules on a record's declaration, on the javac frontend (ADR-068 D11), at any depth of a unit javac read:
 * <ul>
 *   <li>A record has at most one sealed parent ({@code JULC0079}, ADR-067 D6). A Data value has one constructor tag, so a
 *       record that is a variant of two sealed interfaces is read as another variant by one of them.</li>
 *   <li>A record's body holds only nested type declarations ({@code JULC0080}, ADR-067 D9). JuLC compiles a record as its
 *       components, so a declared accessor, {@code equals}, constructor, method, static field or initializer is never what
 *       the script runs.</li>
 * </ul>
 * What javac wrote into a record (its component fields, a canonical constructor it generated) has no source text and is not
 * a member here. An API type comes in as a class file, so a ledger type is never read. The rules that hold on both frontends
 * ({@code JULC0101} for a declared {@code toPlutusData} or accessor, {@code JULC0099} for a constructor's statements,
 * {@code JULC0095} for a field) run first, from {@code SubsetRules.check}; this reads the rest.
 */
public final class RecordDeclarations {

    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString");

    private RecordDeclarations() {
    }

    /** @throws org.julclang.compiler.CompilerException at the first record of {@code unit}, in source order, that breaks a rule */
    public static void check(JavacFacts facts, CompilationUnitTree unit) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                if (tree.getKind() == Tree.Kind.RECORD) {
                    requireOneSealedParent(facts, tree);
                    requireOnlyNestedTypes(facts, tree);
                }
                return super.visitClass(tree, unused);
            }
        }.scan(unit, null);
    }

    private static void requireOneSealedParent(JavacFacts facts, ClassTree tree) {
        var record = (TypeElement) facts.element(tree);
        var sealed = new ArrayList<TypeElement>();
        for (var parent : record.getInterfaces()) {
            var element = facts.typeElement(parent);
            if (element.getModifiers().contains(Modifier.SEALED)) sealed.add(element);
        }
        if (sealed.size() > 1) {
            throw facts.rejection(DiagnosticCodes.RECORD_MULTIPLE_SEALED_PARENTS, tree, record.getSimpleName(),
                    sealed.get(0).getSimpleName(), sealed.get(1).getSimpleName());
        }
    }

    /**
     * {@code JULC0079} at a use of {@code record}, a variant of the sealed interfaces {@code parents}, which are two or more:
     * a {@code new}, an {@code instanceof} or a {@code case} reads one tag, and a record has one only under one parent. The
     * gate reports a record the program declares at its declaration first; this is the same rule where the lowering meets it.
     */
    public static CompilerException twoSealedParents(JavacFacts facts, Tree where, TypeElement record, List<TypeElement> parents) {
        return facts.rejection(DiagnosticCodes.RECORD_MULTIPLE_SEALED_PARENTS, where, record.getSimpleName(),
                parents.get(0).getSimpleName(), parents.get(1).getSimpleName());
    }

    /**
     * {@code JULC0127} at a {@code new}, an {@code instanceof} or a {@code case} of {@code variant}, a variant of {@code parent}
     * that is a class or a {@code @NewType}: a value of a sum is a constructor of a record's components, and these have none.
     */
    public static CompilerException notPlainRecord(JavacFacts facts, Tree where, TypeElement variant, TypeElement parent) {
        return facts.rejection(DiagnosticCodes.VARIANT_NOT_PLAIN_RECORD, where, variant.getSimpleName(), parent.getSimpleName());
    }

    private static void requireOnlyNestedTypes(JavacFacts facts, ClassTree tree) {
        var record = (TypeElement) facts.element(tree);
        for (var member : tree.getMembers()) {
            if (member instanceof ClassTree || facts.isGenerated(member)) continue;
            String kind = kind(facts, member);
            if (kind != null) {
                throw facts.rejection(DiagnosticCodes.RECORD_MEMBER_UNSUPPORTED, member, record.getSimpleName(), kind);
            }
        }
    }

    /** What {@code member} is, in ADR-067 D9's words, or null for what javac wrote: a component's field. */
    private static String kind(JavacFacts facts, Tree member) {
        return switch (member) {
            case MethodTree method -> method(facts, method);
            case VariableTree field -> facts.element(field).getModifiers().contains(Modifier.STATIC)
                    ? "a static field " + field.getName() : null;
            case BlockTree ignored -> "an initializer block";
            default -> "a " + member.getKind();
        };
    }

    private static String method(JavacFacts facts, MethodTree tree) {
        var method = (ExecutableElement) facts.element(tree);
        if (method.getKind() == ElementKind.CONSTRUCTOR) {
            if (facts.elements().isCompactConstructor(method)) return "a compact canonical constructor";
            return facts.elements().isCanonicalConstructor(method) ? "a canonical constructor" : "a constructor";
        }
        String name = tree.getName().toString();
        if (method.getModifiers().contains(Modifier.STATIC)) return "a static method " + name;
        if (OBJECT_METHODS.contains(name)) return name;
        boolean accessor = tree.getParameters().isEmpty() && method.getEnclosingElement() instanceof TypeElement record
                && facts.components(record).stream().anyMatch(component -> component.getSimpleName().contentEquals(name));
        return accessor ? "an explicit accessor " + name : "an instance method " + name;
    }
}
