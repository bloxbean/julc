package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.frontend.javac.lower.Lowering;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The order in which the reference's {@code LibraryCompiler} registers library methods, and the programs it cannot compile
 * for that order (ADR-068 D8). The order is what the library {@code let}s follow: {@code ValidatorAssembly} sorts them by
 * dependency, and breaks ties by it, so the bytes depend on it.
 * <p>
 * The reference compiles the units in the order it was given them, a unit's classes outer first, and a class's static
 * methods in source order, registering each as it compiles it. A call of a library method by its class name needs the
 * method registered: if it is not, the unit fails there, keeps what it registered at its place, and is tried again in the
 * next pass, as long as a pass makes progress. Then the first unit that still fails is rejected, at the first call it
 * cannot resolve, as {@code Undefined variable} of the class name. A unit with no such call registers in one pass. This
 * class reads only what lowering recorded of each method, so it is a function of its input.
 */
final class LibraryRegistration {

    /** A static method of a library class, in the order of its unit; {@code lowered} is null for a generic template. */
    record Method(LibraryMethod lowered, List<Lowering.LibraryCall> calls) {
    }

    private final Map<String, LibraryMethod> registered = new LinkedHashMap<>();

    private LibraryRegistration() {
    }

    /**
     * The methods of {@code units}, given in the order the reference was given them, in the order it registers them. An
     * overload takes its first one's place and the last one's body, as the reference's map does.
     *
     * @throws CompilerException where the reference cannot compile them in that order
     */
    static List<LibraryMethod> order(List<List<Method>> units) {
        var registration = new LibraryRegistration();
        var remaining = new ArrayList<>(units);
        boolean progress = true;
        while (progress && !remaining.isEmpty()) {
            progress = false;
            var next = new ArrayList<List<Method>>();
            for (var unit : remaining) {
                if (registration.attempt(unit) == null) progress = true;
                else next.add(unit);
            }
            remaining = next;
        }
        if (!remaining.isEmpty()) {
            var unresolved = registration.attempt(remaining.getFirst());
            throw new CompilerException("Undefined variable: " + unresolved.qualifier());
        }
        return List.copyOf(registration.registered.values());
    }

    /** Registers what {@code unit} can: null when it registers all of it, else the call that stopped it. */
    private Lowering.LibraryCall attempt(List<Method> unit) {
        for (var method : unit) {
            if (method.lowered() == null) continue;
            for (var call : method.calls()) {
                if (!registered.containsKey(call.binder())) return call;
            }
            registered.put(method.lowered().qualifiedName(), method.lowered());
        }
        return null;
    }
}
