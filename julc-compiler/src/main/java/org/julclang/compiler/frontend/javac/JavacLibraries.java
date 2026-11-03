package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.OnchainOverloads;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.lower.Lowering;
import org.julclang.compiler.frontend.javac.lower.Scope;
import org.julclang.compiler.frontend.javac.subset.SubsetScan;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The libraries of a compilation on the javac frontend (ADR-068 D8), lowered whole as the reference's
 * {@code LibraryCompiler} lowers them: every static method of every class of a unit the reference compiles as a library
 * ({@link JavacProgram#offered}), a class's static fields bound around each of them. A generic method is a template, which
 * only a language-neutral producer specializes (ADR-059). No other method of a library is lowered.
 * <p>
 * The units are lowered in qualified-name order (I4), so a unit's first rejection does not depend on the host. The
 * {@code let}s they become follow the reference's registration ({@link LibraryRegistration}), which reads the units in the
 * host's order, so the bytes are the reference's for the same pool.
 */
final class JavacLibraries {

    /** A static method of a library class, lowered or stopped: what I1b compares (ADR-068 I1). */
    record Attempt(String binder, LibraryMethod lowered, String construct) {
    }

    private final JavacProgram program;
    private final JavacFacts facts;
    private final JavacDiscovery discovery;
    private final Lowering lowering;
    /** Each lowered method's binder, to the method it stands for: I13 reads the library lets by it. */
    private final Map<String, Element> binders = new LinkedHashMap<>();

    JavacLibraries(JavacProgram program, JavacFacts facts, JavacDiscovery discovery, Lowering lowering) {
        this.program = program;
        this.facts = facts;
        this.discovery = discovery;
        this.lowering = lowering;
    }

    /** The units, in the host's order. */
    List<CompilationUnitTree> units() {
        return program.offered();
    }

    /** The classes of {@code unit} the reference compiles: every one that is no interface, record or enum, outer first. */
    List<ClassTree> classes(CompilationUnitTree unit) {
        return JavacDiscovery.types(unit).stream().filter(type -> type.getKind() == Tree.Kind.CLASS).toList();
    }

    /** Step 2, after the root's: the subset scan of each unit, where its classes' static methods and fields are lowered. */
    List<CompilerDiagnostic> scan() {
        var warnings = new ArrayList<CompilerDiagnostic>();
        for (var unit : units()) warnings.addAll(SubsetScan.check(facts, unit, lowered(unit)));
        return warnings;
    }

    /** What lowering {@code unit} as a library reaches: its classes' static methods and fields; none if it is no library. */
    Set<Tree> lowered(CompilationUnitTree unit) {
        Set<Tree> lowered = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!units().contains(unit)) return lowered;
        for (var type : classes(unit)) lowered.addAll(discovery.lowered(type));
        return lowered;
    }

    /** Each lowered library method's binder, to the method it stands for. */
    Map<String, Element> binders() {
        return binders;
    }

    /** Step 3 of a validator's compilation: no type of a library unit is a validator. */
    void requireNoValidators() {
        for (var unit : units()) {
            for (var type : JavacDiscovery.types(unit)) {
                discovery.rejectRoleConflict(type);
                var validators = discovery.validatorAnnotations(type);
                if (!validators.isEmpty()) {
                    throw new CompilerException("Library source must not contain validator annotation @"
                            + validators.getFirst() + ": " + type.getSimpleName());
                }
            }
        }
    }

    /**
     * Step 8: the library methods, in the order the reference registers them.
     *
     * @throws CompilerException at the first rejection, in qualified-name order, or where the reference cannot register
     */
    List<LibraryMethod> lower() {
        var lowered = new IdentityHashMap<CompilationUnitTree, List<LibraryRegistration.Method>>();
        for (var unit : program.offeredByName()) lowered.put(unit, lowering.terms().unlocated(() -> lowerUnit(unit)));
        return LibraryRegistration.order(units().stream().map(lowered::get).toList());
    }

    /** I1b: each method lowered on its own, so one is compared even where another stops. A template is not lowered. */
    List<Attempt> attempts() {
        var attempts = new ArrayList<Attempt>();
        for (var unit : program.offeredByName()) {
            for (var type : classes(unit)) {
                ClassLowering lowered;
                try {
                    lowered = new ClassLowering(type);
                } catch (CompilerException e) {
                    String owner = facts.identity((TypeElement) facts.element(type));
                    for (var method : discovery.methods(type)) {
                        if (discovery.isStatic(method) && method.getTypeParameters().isEmpty()) {
                            attempts.add(new Attempt(owner + "." + method.getName(), null, stopped(e)));
                        }
                    }
                    continue;
                }
                for (var method : lowered.methods) {
                    if (lowered.isTemplate(method)) continue;
                    try {
                        attempts.add(new Attempt(lowered.binder(method), lowered.method(method).lowered(), null));
                    } catch (CompilerException e) {
                        attempts.add(new Attempt(lowered.binder(method), null, stopped(e)));
                    }
                }
            }
        }
        return attempts;
    }

    private static String stopped(CompilerException e) {
        return e instanceof NotPorted notPorted ? notPorted.construct() : JavacCompilation.REJECTED;
    }

    private List<LibraryRegistration.Method> lowerUnit(CompilationUnitTree unit) {
        var methods = new ArrayList<LibraryRegistration.Method>();
        for (var type : classes(unit)) {
            var lowered = new ClassLowering(type);
            var overloads = new OnchainOverloads(lowered.owner, Set.of());
            for (var method : lowered.methods) {
                var registered = lowered.method(method);
                if (registered.lowered() != null) {
                    var library = registered.lowered();
                    overloads.check(library.methodName(), library.type(), library.body(), () -> facts.location(method));
                }
                methods.add(registered);
            }
            // The reference's generator holds its warnings with its errors, so a warning rejects a library class.
            var warnings = lowering.drainWarnings();
            if (!warnings.isEmpty()) throw new CompilerException(warnings);
        }
        return methods;
    }

    /** One class: its static fields, bound once and around each method, and its static methods in source order. */
    private final class ClassLowering {
        final String owner;
        final List<MethodTree> methods;
        private final List<VariableTree> fields;
        private final Scope scope;
        private final List<PirTerm> values = new ArrayList<>();

        ClassLowering(ClassTree type) {
            owner = facts.identity((TypeElement) facts.element(type));
            methods = discovery.methods(type).stream().filter(discovery::isStatic).toList();
            fields = discovery.staticFields(type);
            var bound = Scope.empty();
            for (var field : fields) {
                var element = facts.element(field);
                bound = bound.bind(element, facts.pirType(element.asType(), field.getType()));
            }
            scope = bound;
            lowering.classBegins();
            for (var method : methods) {
                if (!isTemplate(method)) lowering.methodDeclared(binder(method));
            }
            lowering.drainLibraryCalls();
            for (var field : fields) values.add(lowering.initializer(field.getInitializer(), scope));
        }

        String binder(MethodTree method) {
            return owner + "." + method.getName();
        }

        boolean isTemplate(MethodTree method) {
            return !method.getTypeParameters().isEmpty();
        }

        /** A method as the reference registers it: its body under the class's fields, and the library calls it makes. */
        LibraryRegistration.Method method(MethodTree method) {
            if (isTemplate(method)) return new LibraryRegistration.Method(null, List.of());
            lowering.drainLibraryCalls();
            PirTerm body = lowering.method(method, scope, false);
            for (int i = fields.size() - 1; i >= 0; i--) {
                var field = facts.element(fields.get(i));
                var name = scope.lookup(field).name();
                body = new PirTerm.Let(name, values.get(i), body);
                lowering.terms().assembled(body, name, field);
            }
            var element = (ExecutableElement) facts.element(method);
            binders.putIfAbsent(binder(method), facts.binderMethod(element));
            var lowered = new LibraryMethod(owner, method.getName().toString(), facts.methodType(element), body);
            return new LibraryRegistration.Method(lowered, lowering.drainLibraryCalls());
        }
    }
}
