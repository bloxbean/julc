package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.pir.StdlibLookup;

import java.util.*;

/**
 * Registry of library methods compiled to PIR.
 * <p>
 * Implements {@link StdlibLookup} so library method calls in validator code
 * are resolved during compilation. Each method is stored as a named PIR lambda.
 * When looked up, returns a {@code Var} reference applied to the provided arguments.
 * The actual PIR bodies are emitted as outermost Let bindings around the validator.
 */
public class LibraryMethodRegistry implements StdlibLookup {

    /** A compiled library method. */
    public record LibraryMethod(String className, String methodName, PirType type, PirTerm body) {
        public String qualifiedName() {
            return className + "." + methodName;
        }
    }

    private final Map<String, LibraryMethod> methods = new LinkedHashMap<>();

    /**
     * Register a compiled library method.
     *
     * @param className  the simple class name
     * @param methodName the method name
     * @param type       the method's PIR function type
     * @param body       the compiled PIR lambda for the method body
     */
    public void register(String className, String methodName, PirType type, PirTerm body) {
        var key = className + "." + methodName;
        methods.put(key, new LibraryMethod(className, methodName, type, body));
    }

    @Override
    public Optional<PirTerm> lookup(String className, String methodName, List<PirTerm> args) {
        var key = className + "." + methodName;
        var method = methods.get(key);
        if (method == null) {
            return Optional.empty();
        }
        // Return a Var reference applied to args — the actual body is a Let binding elsewhere
        PirTerm result = new PirTerm.Var(key, method.type());
        for (var arg : args) {
            result = new PirTerm.App(result, arg);
        }
        return Optional.of(result);
    }

    /**
     * Return all registered library methods (for wrapping as Let bindings).
     */
    public Collection<LibraryMethod> allMethods() {
        return Collections.unmodifiableCollection(methods.values());
    }

    public boolean isEmpty() {
        return methods.isEmpty();
    }
}
