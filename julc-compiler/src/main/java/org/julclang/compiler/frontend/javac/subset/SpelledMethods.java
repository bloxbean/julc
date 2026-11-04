package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.JavacFacts;
import org.julclang.compiler.frontend.javac.JavacNames;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.TypeMethodRegistry;
import org.julclang.compiler.resolve.TypeRegistry;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.Set;

/**
 * A subset rule ({@code JULC0101}): JuLC compiles a record as its components, so a declared {@code toPlutusData} or an
 * explicit accessor is never what a script's Data is ({@link #checkDeclarations}), and a generated accessor that implements
 * {@code ToPlutusData.toPlutusData} has no tree to lower ({@link #checkRecordMembers}, which reads the elements).
 */
public final class SpelledMethods {
    private SpelledMethods() {
    }

    /**
     * The part of this rule that holds on every frontend: JuLC compiles a record as its components, so a declared
     * {@code toPlutusData} or accessor is never what a script's Data is, wherever the record is built, stored or returned.
     */
    public static void checkDeclarations(JavacFacts facts, CompilationUnitTree unit) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree tree, Void unused) {
                var method = (ExecutableElement) facts.element(tree);
                if (method.getKind() == ElementKind.METHOD && isReadAsComponents(facts, method)) throw reject(facts, method, tree);
                return super.visitMethod(tree, unused);
            }
        }.scan(unit, null);
    }

    private static boolean isReadAsComponents(JavacFacts facts, ExecutableElement method) {
        String name = method.getSimpleName().toString();
        if (!method.getParameters().isEmpty() || method.getModifiers().contains(Modifier.STATIC)) return false;
        if (name.equals("toPlutusData")) return true;
        var owner = (TypeElement) method.getEnclosingElement();
        return owner.getKind() == ElementKind.RECORD && isComponent(facts, owner, name);
    }

    /**
     * What {@link #checkDeclarations} cannot read, on the javac frontend, whose records the gate's component rule does not
     * reach: a component whose generated accessor implements {@link JavacNames#TO_PLUTUS_DATA}'s method. Java's Data for such
     * a record is that component, and JuLC's is the record of its components. Only that method changes a record's encoding: a
     * generated accessor of any other method is the component it reads.
     */
    public static void checkRecordMembers(JavacFacts facts, CompilationUnitTree unit) {
        var type = facts.elements().getTypeElement(JavacNames.TO_PLUTUS_DATA);
        if (type == null) return;
        var data = ElementFilter.methodsIn(type.getEnclosedElements()).getFirst();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                if (facts.element(tree) instanceof TypeElement record && record.getKind() == ElementKind.RECORD) {
                    for (var component : facts.components(record)) {
                        if (facts.elements().overrides(component.getAccessor(), data, record)) {
                            throw CompilerTypeDiagnostics.methodNameReadBySpelling(
                                    "The component " + record.getSimpleName() + "." + component.getSimpleName(), facts.location(tree));
                        }
                    }
                }
                return super.visitClass(tree, unused);
            }
        }.scan(unit, null);
    }

    private static RuntimeException reject(JavacFacts facts, Element method, com.sun.source.tree.Tree where) {
        return CompilerTypeDiagnostics.methodNameReadBySpelling(
                "The method " + method.getEnclosingElement().getSimpleName() + "." + method, facts.location(where));
    }

    private static boolean isComponent(JavacFacts facts, TypeElement record, String name) {
        return facts.components(record).stream().anyMatch(component -> component.getSimpleName().contentEquals(name));
    }

    /** By simple name, as the JavaParser frontend reads an annotation. */
    static boolean isNewType(Element element) {
        return element.getAnnotationMirrors().stream().anyMatch(mirror ->
                mirror.getAnnotationType().asElement().getSimpleName().contentEquals("NewType"));
    }
}
