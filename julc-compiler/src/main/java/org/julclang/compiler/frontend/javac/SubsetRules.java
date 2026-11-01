package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.frontend.javac.subset.BooleanCasts;
import org.julclang.compiler.frontend.javac.subset.ConstructorBodies;
import org.julclang.compiler.frontend.javac.subset.SpelledMethods;
import org.julclang.compiler.frontend.javac.subset.SpelledTypes;
import org.julclang.compiler.frontend.javac.subset.UnloweredInitializers;

import javax.lang.model.element.ExecutableElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * The subset's rules that rest on what javac resolved. They run once, here, on javac's trees, after {@code analyze()}
 * succeeds: from {@link JavacGate} for a compilation on the JavaParser frontend, whose trees have no types, and from
 * {@link JavacCompilation} for one on the javac frontend. So both frontends have one rule, with one code, one message and
 * one position. The rules read the units javac read: the roots, and the libraries they reference. A library unit nothing
 * references is not read, so the rules say nothing of it. On the javac frontend such a unit is not compiled; on the
 * JavaParser frontend every library it is given is lowered, read or not.
 * <ul>
 *   <li>An entry point returns {@code boolean} or {@code java.lang.Boolean} ({@code JULC0094}).</li>
 *   <li>What is not lowered holds only a constant ({@link UnloweredInitializers}, {@code JULC0095}).</li>
 *   <li>A value reaches {@code boolean} through a cast only from a boolean ({@link BooleanCasts}, {@code JULC0098}).</li>
 *   <li>A constructor holds no statement ({@link ConstructorBodies}, {@code JULC0099}).</li>
 * </ul>
 * An API type comes in as a class file, so these rules never read one.
 * <p>
 * Two more rules are about how the JavaParser frontend reads a name, so only the gate has them
 * ({@link #checkSpellings}): the javac frontend binds a name to its element, and has no such reading.
 * <ul>
 *   <li>A name that frontend reads by its spelling binds, at every use, to what it reads ({@link SpelledTypes}, {@code JULC0100}).</li>
 *   <li>A method does not take a name it reads in place of the method's body ({@link SpelledMethods}, {@code JULC0101}).</li>
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
            ConstructorBodies.check(facts, unit);
        }
        for (var unit : libraries) library(facts, discovery, unit);
    }

    /** @throws org.julclang.compiler.CompilerException at the first rule a unit breaks */
    static void checkSpellings(JavacFacts facts, List<CompilationUnitTree> roots, List<CompilationUnitTree> libraries,
                               JavacGate.Compiled compiled, SpelledTypes.Reading reading) {
        var libraryUnits = new ArrayList<>(libraries);
        if (compiled == JavacGate.Compiled.LIBRARY) libraryUnits.addAll(roots);
        var compiledAsLibraries = new HashSet<>(libraryUnits);
        for (var units : List.of(roots, libraries)) {
            for (var unit : units) {
                SpelledTypes.check(facts, unit, reading, compiledAsLibraries);
                SpelledMethods.check(facts, unit);
            }
        }
        SpelledTypes.checkUnreadUnits(reading);
        // The JavaParser frontend binds a library's static fields at script start, whether or not Java initializes the class.
        for (var unit : libraryUnits) UnloweredInitializers.check(facts, unit, field -> false);
    }

    private static void library(JavacFacts facts, JavacDiscovery discovery, CompilationUnitTree unit) {
        UnloweredInitializers.check(facts, unit, discovery::isLoweredInALibrary);
        BooleanCasts.check(facts, unit);
        ConstructorBodies.check(facts, unit);
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
