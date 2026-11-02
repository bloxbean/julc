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
 * A subset rule ({@code JULC0101}): a program does not declare a method under a name the JavaParser frontend reads by its
 * spelling ahead of the method's body. Each row is a matcher of {@code PirGenerator}'s method calls, which stands in for
 * the call without lowering the body. A method javac generates has no tree to read at this point, so these rows exempt it,
 * and {@link #checkRecordMembers} reads what the javac frontend needs of one from the elements.
 * <table>
 *   <caption>The matchers, and the declaration each one stands in for</caption>
 *   <tr><td>{@code tryFromPlutusDataIdentity}</td><td>{@code fromPlutusData} of one parameter, on any type</td></tr>
 *   <tr><td>the {@code of} lookup in {@code JulcCompiler}</td><td>{@code of} of one parameter, on a {@code @NewType}</td></tr>
 *   <tr><td>{@code TypeMethodRegistry}, on the type a {@code @NewType} lowers to</td><td>an instance method of a {@code @NewType}</td></tr>
 *   <tr><td>{@code .hash()} and the no-argument fallback on bytes</td><td>an instance method of a {@code @NewType}</td></tr>
 *   <tr><td>a record field read by its accessor's name</td><td>an explicit accessor</td></tr>
 *   <tr><td>{@code TypeMethodRegistry}'s {@code equals}</td><td>{@code equals} of one parameter, on a record or a sealed interface</td></tr>
 *   <tr><td>the {@code toPlutusData} fallback</td><td>{@code toPlutusData} of no parameters, on any type</td></tr>
 *   <tr><td>{@code tryBigIntegerConstant}, {@code tryPlutusDataCast}, the {@code of} lookup</td><td>a call of a declared {@code valueOf}, {@code cast} or {@code of} through a variable of any name: the names of those types are {@code JULC0100}'s</td></tr>
 *   <tr><td>{@code AccumulatorTypeAnalyzer}, which types a loop's accumulator by the names of the calls assigned to it, on any receiver</td><td>a call of a method of a name of {@link #ACCUMULATOR_CALLS} that is not {@code Builtins}' own</td></tr>
 *   <tr><td>{@code TypeMethodRegistry} on the type a {@code @NewType} lowers to</td><td>a component of that name, other than a row that lowers to the receiver</td></tr>
 *   <tr><td>{@code TypeMethodRegistry}'s {@code equals} and the {@code toPlutusData} fallback</td><td>a component of that name, on any record</td></tr>
 *   <tr><td>the no-argument fallback on bytes</td><td>any other component of a {@code @NewType} of {@code byte[]}, and any no-argument call on a {@code byte[]}, or on a ledger hash that is not its accessor or {@code toPlutusData}</td></tr>
 *   <tr><td>{@code TypeMethodRegistry} on the type a {@code @NewType} lowers to, and {@code .length} on bytes</td><td>any instance call on a {@code @NewType} that is not its accessor, an inherited default method included, and a field read on one</td></tr>
 *   <tr><td>{@code resolveRecordFieldAccess}, which reads through an {@code Optional}</td><td>a no-argument call on an {@code Optional} of a record that has a component of that name</td></tr>
 * </table>
 * A {@code @NewType} lowers to bytes, an integer, a string or a boolean. The rows are {@code TypeMethodRegistry}'s, read when
 * a component is checked, so this list cannot drift from them. The other matchers of {@code PirGenerator} and
 * {@code TypeInferenceHelper} read a name after a type: {@code compareTo}, {@code hash}, {@code length}, {@code tail}, the list
 * higher-order methods and the methods of an API type. That type comes from a type name, which {@code JULC0100} binds, and
 * a {@code @NewType} that lowers to it has no instance call here but its accessor. {@code TypeMethodRegistry}'s named rows go
 * by a record's name, which is {@code JULC0100}'s. {@code equals} in a guard
 * and {@code apply}, {@code test}, {@code accept}, {@code get} and {@code run} on a functional variable are read to reject.
 */
public final class SpelledMethods {

    private static final Set<String> SPELLED_CALLS = Set.of("valueOf", "cast", "of");
    /** The calls {@code AccumulatorTypeAnalyzer} reads as {@code Builtins}', by the method's name alone. */
    public static final Set<String> ACCUMULATOR_CALLS = Set.of("mkNilPairData", "unMapData", "tailList", "mkNilData",
            "mkCons", "mkPairData", "headList", "fstPair", "sndPair");
    private static final String BUILTINS = "org.julclang.stdlib.Builtins";
    private static final String OPTIONAL = "java.util.Optional";
    private static final Set<String> ANY_RECORD_COMPONENTS = Set.of("equals", "toPlutusData");
    static final TypeMethodRegistry REGISTRY = TypeMethodRegistry.defaultRegistry();

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

    public static void check(JavacFacts facts, CompilationUnitTree unit) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                if (facts.element(tree) instanceof TypeElement type && type.getKind() == ElementKind.RECORD) {
                    for (var component : facts.components(type)) {
                        if (isReadAheadOfAccessor(facts, type, component)) {
                            throw CompilerTypeDiagnostics.methodNameReadBySpelling(
                                    "The component " + type.getSimpleName() + "." + component.getSimpleName(), facts.location(tree));
                        }
                    }
                }
                return super.visitClass(tree, unused);
            }

            @Override
            public Void visitMethod(MethodTree tree, Void unused) {
                var method = (ExecutableElement) facts.element(tree);
                if (method.getKind() == ElementKind.METHOD && isReadBySpelling(facts, method)) {
                    throw reject(facts, method, tree);
                }
                return super.visitMethod(tree, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                if (facts.elementOrNull(tree) instanceof VariableElement field && field.getKind() == ElementKind.FIELD
                        && !tree.getIdentifier().contentEquals("class") && newTypeOf(facts, tree) != null) {
                    throw CompilerTypeDiagnostics.methodNameReadBySpelling(
                            "The field " + field.getEnclosingElement().getSimpleName() + "." + field, facts.location(tree));
                }
                return super.visitMemberSelect(tree, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                if (tree.getMethodSelect() instanceof MemberSelectTree select) {
                    boolean noArguments = tree.getArguments().isEmpty();
                    if (isOtherCallOnANewType(facts, select, noArguments)
                            || noArguments && (isOtherCallOnBytes(facts, select) || isComponentThroughAnOptional(facts, select))) {
                        throw reject(facts, facts.element(select), tree);
                    }
                }
                if (tree.getMethodSelect() instanceof MemberSelectTree select
                        && SPELLED_CALLS.contains(select.getIdentifier().toString())
                        && select.getExpression() instanceof IdentifierTree qualifier
                        && facts.element(qualifier) instanceof VariableElement
                        && facts.isDeclaredInSource(facts.element(select))) {
                    throw reject(facts, facts.element(select), tree);
                }
                if (facts.elementOrNull(tree.getMethodSelect()) instanceof ExecutableElement method
                        && ACCUMULATOR_CALLS.contains(method.getSimpleName().toString()) && !isBuiltins(facts, method)) {
                    throw reject(facts, method, tree);
                }
                return super.visitMethodInvocation(tree, unused);
            }
        }.scan(unit, null);
    }

    private static boolean isBuiltins(JavacFacts facts, ExecutableElement method) {
        return method.getEnclosingElement() instanceof TypeElement owner && facts.identity(owner).equals(BUILTINS);
    }

    private static RuntimeException reject(JavacFacts facts, Element method, com.sun.source.tree.Tree where) {
        return CompilerTypeDiagnostics.methodNameReadBySpelling(
                "The method " + method.getEnclosingElement().getSimpleName() + "." + method, facts.location(where));
    }

    private static boolean isReadBySpelling(JavacFacts facts, ExecutableElement method) {
        String name = method.getSimpleName().toString();
        int parameters = method.getParameters().size();
        if (name.equals("fromPlutusData") && parameters == 1 || name.equals("toPlutusData") && parameters == 0) return true;
        var owner = (TypeElement) method.getEnclosingElement();
        boolean instance = !method.getModifiers().contains(Modifier.STATIC);
        boolean equals = instance && name.equals("equals") && parameters == 1;
        if (owner.getKind() == ElementKind.INTERFACE) return equals && owner.getModifiers().contains(Modifier.SEALED);
        if (owner.getKind() != ElementKind.RECORD) return false;
        if (isNewType(owner)) return instance || name.equals("of") && parameters == 1;
        return equals || instance && parameters == 0 && isComponent(facts, owner, name);
    }

    private static boolean isReadAheadOfAccessor(JavacFacts facts, TypeElement record, RecordComponentElement component) {
        String name = component.getSimpleName().toString();
        if (ANY_RECORD_COMPONENTS.contains(name)) return true;
        if (!isNewType(record)) return false;
        var lowered = lowered(facts, component.asType());
        return lowered != null && isServed(lowered, name);
    }

    /** What a {@code @NewType} of {@code type} lowers to, or null for a type it may not hold: that is the type's own rejection. */
    private static PirType lowered(JavacFacts facts, TypeMirror type) {
        if (type.getKind() == TypeKind.BOOLEAN) return new PirType.BoolType();
        if (type instanceof ArrayType array) return array.getComponentType().getKind() == TypeKind.BYTE ? new PirType.ByteStringType() : null;
        if (!(type instanceof DeclaredType declared)) return null;
        return switch (facts.identity(declared)) {
            case JavacNames.BIG_INTEGER -> new PirType.IntegerType();
            case JavacNames.STRING -> new PirType.StringType();
            default -> null;
        };
    }

    /** Whether the compiler serves a call of {@code name} on a {@code @NewType} that lowers to {@code type}, ahead of its accessor. */
    private static boolean isServed(PirType type, String name) {
        if (REGISTRY.lowersToTheReceiver(type, name)) return false;
        return type instanceof PirType.ByteStringType || REGISTRY.serves(type, name);
    }

    /** The {@code @NewType} a selection reads from, or null. */
    private static TypeElement newTypeOf(JavacFacts facts, MemberSelectTree select) {
        return facts.type(select.getExpression()) instanceof DeclaredType receiver && receiver.asElement() instanceof TypeElement type
                && type.getKind() == ElementKind.RECORD && isNewType(type) ? type : null;
    }

    /** An instance call on a {@code @NewType} of anything but its accessor: a row of the type it lowers to takes the call. */
    private static boolean isOtherCallOnANewType(JavacFacts facts, MemberSelectTree select, boolean noArguments) {
        var type = newTypeOf(facts, select);
        return type != null && !facts.element(select).getModifiers().contains(Modifier.STATIC)
                && !(noArguments && isComponent(facts, type, select.getIdentifier().toString()));
    }

    /**
     * A no-argument call on what lowers to bytes that is no accessor: the fallback on bytes takes it. {@code @NewType}s are
     * {@link #isOtherCallOnANewType}'s; these are a {@code byte[]} and a ledger hash, whose {@code toPlutusData} has its own row.
     */
    private static boolean isOtherCallOnBytes(JavacFacts facts, MemberSelectTree select) {
        var receiver = facts.type(select.getExpression());
        if (receiver instanceof ArrayType array) return array.getComponentType().getKind() == TypeKind.BYTE;
        String name = select.getIdentifier().toString();
        return receiver instanceof DeclaredType declared && declared.asElement() instanceof TypeElement type
                && TypeRegistry.ledgerHashFqcns().contains(facts.identity(type))
                && !name.equals("toPlutusData") && !isComponent(facts, type, name);
    }

    /** A variable's record field is read through an {@code Optional}, so a method of that name reads the component. */
    private static boolean isComponentThroughAnOptional(JavacFacts facts, MemberSelectTree select) {
        return facts.type(select.getExpression()) instanceof DeclaredType receiver && facts.identity(receiver).equals(OPTIONAL)
                && receiver.getTypeArguments().size() == 1 && receiver.getTypeArguments().getFirst() instanceof DeclaredType element
                && element.asElement() instanceof TypeElement record && record.getKind() == ElementKind.RECORD
                && isComponent(facts, record, select.getIdentifier().toString());
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
