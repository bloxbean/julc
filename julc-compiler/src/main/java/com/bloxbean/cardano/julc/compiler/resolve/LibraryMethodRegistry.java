package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.pir.PirHelpers;
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

    @Override
    public Optional<PirTerm> lookup(String className, String methodName,
                                      List<PirTerm> args, List<PirType> argTypes) {
        var key = className + "." + methodName;
        var method = methods.get(key);
        if (method == null) {
            return Optional.empty();
        }

        // Extract expected parameter types from the FunType chain
        var expectedTypes = extractParamTypes(method.type());

        // Apply coercions where caller type differs from callee's expected type
        var coercedArgs = new ArrayList<PirTerm>(args.size());
        for (int i = 0; i < args.size(); i++) {
            var arg = args.get(i);
            var callerType = i < argTypes.size() ? argTypes.get(i) : new PirType.DataType();
            var calleeType = i < expectedTypes.size() ? expectedTypes.get(i) : new PirType.DataType();
            coercedArgs.add(coerceArg(arg, callerType, calleeType));
        }

        PirTerm result = new PirTerm.Var(key, method.type());
        for (var arg : coercedArgs) {
            result = new PirTerm.App(result, arg);
        }
        return Optional.of(result);
    }

    /**
     * Extract parameter types from a FunType chain.
     * E.g., FunType(ByteStringType, FunType(ListType, BoolType)) → [ByteStringType, ListType]
     */
    private static List<PirType> extractParamTypes(PirType type) {
        var params = new ArrayList<PirType>();
        while (type instanceof PirType.FunType ft) {
            params.add(ft.paramType());
            type = ft.returnType();
        }
        return params;
    }

    /**
     * Coerce an argument from caller type to callee's expected type.
     * <p>
     * Only decodes when callee expects a specific primitive type and caller has DataType.
     * When callee expects DataType, no coercion is applied because DataType means
     * "pass-through value" — the library body handles its own decode/encode.
     */
    private static PirTerm coerceArg(PirTerm arg, PirType callerType, PirType calleeType) {
        if (callerType.equals(calleeType)) return arg;

        // Caller has Data, callee expects specific decoded type → decode
        if (callerType instanceof PirType.DataType && isPrimitiveType(calleeType)) {
            return PirHelpers.wrapDecode(arg, calleeType);
        }

        return arg;
    }

    private static boolean isPrimitiveType(PirType type) {
        return type instanceof PirType.IntegerType
                || type instanceof PirType.ByteStringType
                || type instanceof PirType.BoolType
                || type instanceof PirType.StringType
                || type instanceof PirType.ListType
                || type instanceof PirType.MapType;
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
