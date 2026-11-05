package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.subset.BooleanCasts;
import org.julclang.compiler.frontend.javac.subset.ConstantStringJoins;
import org.julclang.compiler.frontend.javac.subset.ConstructorBodies;
import org.julclang.compiler.frontend.javac.subset.RecordDeclarations;
import org.julclang.compiler.frontend.javac.subset.SpelledMethods;
import org.julclang.compiler.frontend.javac.subset.UnloweredInitializers;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * The subset's rules that rest on what javac resolved. They run once, here, on javac's trees, after {@code analyze()}
 * succeeds, from {@link JavacCompilation}. The rules read the units javac read: the roots, and the libraries they reference.
 * A library unit nothing references is not read, so the rules say nothing of it, and it is not compiled.
 * <ul>
 *   <li>An entry point returns {@code boolean} or {@code java.lang.Boolean} ({@code JULC0094}).</li>
 *   <li>An entry point's last parameter is the script context: {@code ScriptContext} or {@code PlutusData}
 *       ({@code JULC0103}).</li>
 *   <li>What is not lowered holds only a constant ({@link UnloweredInitializers}, {@code JULC0095}).</li>
 *   <li>A value reaches {@code boolean} through a cast only from a boolean ({@link BooleanCasts}, {@code JULC0098}).</li>
 *   <li>A constructor holds no statement ({@link ConstructorBodies}, {@code JULC0099}).</li>
 *   <li>A method does not declare what a record compiles as its components: {@code toPlutusData} or an accessor
 *       ({@link SpelledMethods#checkDeclarations}, {@code JULC0101}).</li>
 * </ul>
 * An API type comes in as a class file, so these rules never read one.
 * <p>
 * {@link #warnings} rejects nothing: a {@code +} of string constants ({@link ConstantStringJoins}, {@code JULC0102}).
 */
final class SubsetRules {

    /** What a compilation compiles of its roots, which decides what the subset's rules read as lowered. */
    public enum Compiled {
        VALIDATOR, METHOD, LIBRARY
    }

    private SubsetRules() {
    }

    /** @throws org.julclang.compiler.CompilerException at the first rule a unit breaks */
    static void check(JavacFacts facts, List<CompilationUnitTree> roots, List<CompilationUnitTree> libraries,
                      Compiled compiled) {
        var discovery = new JavacDiscovery(facts);
        for (var unit : roots) {
            if (compiled == Compiled.LIBRARY) {
                library(facts, unit);
                continue;
            }
            SpelledMethods.checkDeclarations(facts, unit);
            var type = discovery.compiledClass(unit, compiled);
            if (type == null) continue;
            UnloweredInitializers.check(facts, unit, field -> discovery.isLowered(field, type));
            if (compiled == Compiled.VALIDATOR) requireEntrypointSignatures(facts, discovery, type);
            BooleanCasts.check(facts, unit);
            ConstructorBodies.check(facts, unit);
        }
        for (var unit : libraries) library(facts, unit);
    }

    /**
     * {@link #check} for a compilation on the javac frontend, with the rule the gate's component rule stands in for on the
     * other: a {@code toPlutusData} that a component generates, which no tree says ({@link SpelledMethods#checkRecordMembers}, {@code JULC0101}).
     * Then ADR-067's rules on a record's declaration, which ADR-068 D11 gives this frontend alone: one sealed parent
     * ({@code JULC0079}) and a body of nested types ({@code JULC0080}) ({@link RecordDeclarations}).
     *
     * @throws org.julclang.compiler.CompilerException at the first rule a unit breaks
     */
    static void checkOnJavacFrontend(JavacFacts facts, List<CompilationUnitTree> roots, List<CompilationUnitTree> libraries,
                                     Compiled compiled) {
        check(facts, roots, libraries, compiled);
        for (var units : List.of(roots, libraries)) {
            for (var unit : units) SpelledMethods.checkRecordMembers(facts, unit);
        }
        for (var units : List.of(roots, libraries)) {
            for (var unit : units) RecordDeclarations.check(facts, unit);
        }
    }

    /** What the rules warn of in {@code roots}, in order: the same on both frontends, since they read the same trees. */
    static List<CompilerDiagnostic> warnings(JavacFacts facts, List<CompilationUnitTree> roots) {
        var warnings = new ArrayList<CompilerDiagnostic>();
        for (var unit : roots) warnings.addAll(ConstantStringJoins.in(facts, unit));
        return warnings;
    }

    /**
     * A library's static fields are bound when the script starts, around every method of their class, whether or not Java
     * would initialize the class (D8). So each holds what nothing lowers may hold ({@code JULC0095}), on both frontends.
     */
    private static void library(JavacFacts facts, CompilationUnitTree unit) {
        SpelledMethods.checkDeclarations(facts, unit);
        UnloweredInitializers.check(facts, unit, field -> false);
        BooleanCasts.check(facts, unit);
        ConstructorBodies.check(facts, unit);
    }

    /**
     * {@code JULC0094}, then {@code JULC0103}, for each entry point in source order. The wrapper passes the script context's
     * Data as the last argument, undecoded, so a last parameter of any other type reads the context as that type, and the
     * method's JVM test is not a test of the script. An entry point with a parameter count its purpose does not take is the
     * count's rejection, later, as on the reference, so this rule does not overtake it.
     */
    private static void requireEntrypointSignatures(JavacFacts facts, JavacDiscovery discovery, ClassTree type) {
        for (var method : discovery.methods(type)) {
            if (!discovery.carries(method, JavacDiscovery.simpleName(JavacNames.ENTRYPOINT))) continue;
            var element = (ExecutableElement) facts.element(method);
            var result = element.getReturnType();
            if (!facts.isBoolean(result)) {
                throw CompilerTypeDiagnostics.entrypointReturnType(method.getName().toString(),
                        type.getSimpleName().toString(), result.toString(), facts.location(method));
            }
            if (!discovery.takesParameterCount(type, method)) continue;
            var last = method.getParameters().getLast();
            var context = facts.element(last).asType();
            if (!isContext(facts, context)) {
                throw CompilerTypeDiagnostics.entrypointContextParameter(method.getName().toString(),
                        type.getSimpleName().toString(), ArrayIdentityEquality.text(context), facts.location(last));
            }
        }
    }

    /** {@code ScriptContext} or {@code PlutusData}, by identity: a subtype of {@code PlutusData} is a decode of the context. */
    private static boolean isContext(JavacFacts facts, TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        String identity = facts.identity(facts.typeElement(type));
        return identity.equals(JavacNames.SCRIPT_CONTEXT) || identity.equals(JavacNames.PLUTUS_DATA);
    }
}
