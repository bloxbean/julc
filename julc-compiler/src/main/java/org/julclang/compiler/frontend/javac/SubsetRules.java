package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.subset.BooleanCasts;
import org.julclang.compiler.frontend.javac.subset.UnloweredInitializers;

import javax.lang.model.element.ExecutableElement;
import java.util.List;

/**
 * The subset's rules that rest on what javac resolved. They run once, here, on javac's trees, after {@code analyze()}
 * succeeds: from {@link JavacGate} for a compilation on the JavaParser frontend, whose trees have no types, and from
 * {@link JavacCompilation} for one on the javac frontend. So both frontends have one rule, with one code, one message and
 * one position. A unit javac did not read is not checked: nothing the program references is in it.
 * <ul>
 *   <li>An entry point returns {@code boolean} or {@code java.lang.Boolean} ({@code JULC0094}).</li>
 *   <li>What is not lowered holds only a constant ({@link UnloweredInitializers}, {@code JULC0095}).</li>
 *   <li>A cast to {@code boolean} takes a boolean or Data ({@link BooleanCasts}, {@code JULC0098}).</li>
 * </ul>
 */
final class SubsetRules {

    private SubsetRules() {
    }

    /** @throws org.julclang.compiler.CompilerException at the first rule a unit breaks */
    static void check(JavacFacts facts, List<CompilationUnitTree> roots, List<CompilationUnitTree> libraries,
                      JavacGate.Compiled compiled) {
        var discovery = new JavacDiscovery(facts);
        for (var unit : roots) {
            if (compiled == JavacGate.Compiled.LIBRARY) {
                library(facts, discovery, unit);
                continue;
            }
            var type = discovery.compiledClass(unit, compiled);
            if (type == null) continue;
            UnloweredInitializers.check(facts, unit, field -> discovery.isLowered(field, type));
            if (compiled == JavacGate.Compiled.VALIDATOR) requireBooleanResults(facts, discovery, type);
            BooleanCasts.check(facts, unit);
        }
        for (var unit : libraries) library(facts, discovery, unit);
    }

    private static void library(JavacFacts facts, JavacDiscovery discovery, CompilationUnitTree unit) {
        UnloweredInitializers.check(facts, unit, discovery::isLoweredInALibrary);
        BooleanCasts.check(facts, unit);
    }

    private static void requireBooleanResults(JavacFacts facts, JavacDiscovery discovery, ClassTree type) {
        for (var method : discovery.methods(type)) {
            if (!discovery.carries(method, JavacDiscovery.simpleName(JavacNames.ENTRYPOINT))) continue;
            var result = ((ExecutableElement) facts.element(method)).getReturnType();
            if (!facts.isBoolean(result)) {
                throw CompilerTypeDiagnostics.entrypointReturnType(method.getName().toString(),
                        type.getSimpleName().toString(), result.toString(), facts.location(method));
            }
        }
    }
}
