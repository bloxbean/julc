package org.julclang.compiler.codegen;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.JulcCompiler.ScriptPurpose;
import org.julclang.compiler.backend.PirBackend;
import org.julclang.compiler.debug.PirDebugProvenance;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.LibraryMethodRegistry.LibraryMethod;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.compiler.util.MethodDependencyResolver;
import org.julclang.core.source.SourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * What a program's lowered methods become, whichever frontend lowered them (ADR-068 F846): static field, helper and
 * library {@code let}s, the validator wrapper, the {@code @Param} lambdas, and the backend. A frontend discovers the
 * parts and lowers their bodies; everything here takes PIR and resolved types, and reads no source tree.
 * <p>
 * The JavaParser frontend's steps 13 to 22 moved here unchanged, so its bytes did not move. Those are checked by the
 * gate-off baseline against B1.
 */
public final class ValidatorAssembly {

    private ValidatorAssembly() {
    }

    /** A {@code @Param} field: bound to a decoded argument of the applied script. */
    public record ParamField(String name, PirType type, String javaType, SourceLocation location) {
    }

    /** A static field and its lowered initializer. */
    public record StaticField(String name, PirTerm value) {
    }

    /** A method's binder and lowered body. */
    public record Method(String name, PirTerm body) {
    }

    /**
     * A parameter of an entry point.
     *
     * @param name       its source name, for diagnostics
     * @param binderName the name the entry point's function binds it to
     */
    public record Parameter(String name, String binderName, PirType type, SourceLocation location) {
    }

    /**
     * An entry point. {@code datum} is null unless it is a spending entry point that takes one.
     *
     * @param tag            the script-info tag it handles, under auto-dispatch
     * @param parameterCount its parameters, {@code ScriptContext} included
     * @param function       its strict-record-transformed function ({@link #strictEntrypoint})
     * @param optionalDatum  whether {@code datum} is an {@code Optional}
     */
    public record Entry(int tag, int parameterCount, PirTerm function, Parameter datum, Parameter redeemer,
                        boolean optionalDatum) {
    }

    /** A validator's parts. Under auto-dispatch there is an entry per purpose, otherwise one. */
    public record Validator(ScriptPurpose purpose, boolean autoDispatch, List<Entry> entries, List<Method> helpers,
                            List<StaticField> statics, Collection<LibraryMethod> libraries, List<ParamField> params,
                            SourceLocation failureLocation, List<PirTerm.Error> strictErrors) {
    }

    /** A {@code compileMethod} target and the parts around it. */
    public record MethodProgram(String binderName, PirType type, List<Parameter> parameters, List<Method> methods,
                                List<StaticField> statics, Collection<LibraryMethod> libraries,
                                List<ParamField> params) {
    }

    /**
     * What the frontend that lowered a program adds to its assembly.
     *
     * @param positions   the PIR term to source location map, which the assembly adds the wrapper's failures to
     * @param provenance  debug provenance for the backend, or null
     * @param paramBound  told each {@code @Param}'s binding as it is built, or null
     * @param finalCheck  run on the assembled term, before the backend
     * @param staticBound told each static field's binding as it is built, one per copy, or null
     * @param methodBound told each method's binding by its binder name, one per copy, or null
     */
    public record Frontend(Map<PirTerm, SourceLocation> positions, PirDebugProvenance provenance,
                           BiConsumer<ParamField, PirTerm> paramBound, Consumer<PirTerm> finalCheck,
                           BiConsumer<StaticField, PirTerm> staticBound,
                           BiConsumer<String, PirTerm> methodBound) {
    }

    /** A compiled program: the result, and what the backend returned for it. */
    public record Assembled(CompileResult result, PirBackend.Result lowered) {
    }

    // --- Entry points ---

    /**
     * The strict record transform of an entry point's function: its parameters but the last ({@code ScriptContext}) are
     * typed transaction-time roots. {@code @Param} fields are not.
     */
    public static StrictRecordEntrypoint.Result strictEntrypoint(PirTerm function, List<Parameter> parameters,
                                                                 TypeRegistry types) {
        return strictEntrypoint(function, parameters, types, StrictRecordEntrypoint.Rebuilds.NONE);
    }

    /** {@link #strictEntrypoint(PirTerm, List, TypeRegistry)}, telling {@code rebuilds} what the transform builds. */
    public static StrictRecordEntrypoint.Result strictEntrypoint(PirTerm function, List<Parameter> parameters,
                                                                 TypeRegistry types,
                                                                 StrictRecordEntrypoint.Rebuilds rebuilds) {
        var roots = new ArrayList<StrictRecordEntrypoint.Root>();
        int rootCount = Math.max(0, parameters.size() - 1); // ScriptContext is always last
        for (int i = 0; i < rootCount; i++) {
            var parameter = parameters.get(i);
            requireStrictBoundary(parameter, types);
            roots.add(new StrictRecordEntrypoint.Root(parameter.binderName(), parameter.type()));
        }
        return StrictRecordEntrypoint.transform(function, roots, types.namedDefinitions(), rebuilds);
    }

    private static void requireStrictBoundary(Parameter parameter, TypeRegistry types) {
        if (types.containsNativeOpaque(parameter.type())) {
            throw CompilerTypeDiagnostics.nativeTypeAtDataBoundary(parameter.name(), parameter.type(), parameter.location());
        }
        try {
            types.findStandaloneVariantRecord(parameter.type()).ifPresent(description -> {
                throw new IllegalArgumentException(
                        "Unsupported strict boundary " + description
                                + "; declare the sealed interface type instead");
            });
            new StrictBoundaryGenerator(types.namedDefinitions()).ensureSupported(parameter.type());
        } catch (IllegalArgumentException ex) {
            var at = parameter.location();
            throw new CompilerException(List.of(new CompilerDiagnostic(CompilerDiagnostic.Level.ERROR, ex.getMessage(),
                    at.fileName(), at.line(), at.column())));
        }
    }

    private static PirType wrapperBoundaryType(PirType type, TypeRegistry types) {
        if (type == null) return null;
        return types.resolveNamed(type) instanceof PirType.RecordType ? new PirType.DataType() : type;
    }

    // --- Validators ---

    /** Assembles a validator and lowers it. */
    public static Assembled validator(Validator validator, TypeRegistry types, CompilationContext context,
                                      Frontend frontend, boolean captureDetails) {
        PirTerm wrapped = wrap(validator, types, context, frontend);
        wrapped = bindParams(validator.params(), wrapped, frontend);
        frontend.finalCheck().accept(wrapped);
        var lowered = PirBackend.lower(wrapped, context, frontend.positions(), true, frontend.provenance(),
                bindings(validator.helpers(), validator.statics(), validator.libraries()));
        var program = lowered.program();
        var result = new CompileResult(
                program, context.diagnostics(), paramInfos(validator.params()),
                captureDetails ? lowered.pir() : null, captureDetails ? program.term() : null, lowered.sourceMap(),
                context.target(), context.optimizationReport(), context.javacProvenance());
        return new Assembled(result, lowered);
    }

    /** Steps 13 to 15b: the bindings around each entry point, and the wrapper. */
    private static PirTerm wrap(Validator validator, TypeRegistry types, CompilationContext context, Frontend frontend) {
        // 13. Wrap helper methods as Let bindings
        // For auto-dispatch, wrap each handler; for single entrypoint, wrap the single body
        var handlers = new LinkedHashMap<Integer, PirTerm>();
        PirTerm body = null;
        if (validator.autoDispatch()) {
            // Wrap helper methods around each handler (dependency-ordered)
            for (var entry : validator.entries()) {
                handlers.put(entry.tag(), bindStatics(validator.statics(),
                        wrapMethodBindings(validator.helpers(), entry.function(), frontend), frontend));
            }
        } else {
            body = bindStatics(validator.statics(),
                    wrapMethodBindings(validator.helpers(), validator.entries().getFirst().function(), frontend), frontend);
        }

        // 14. Wrap library methods as Let bindings (topologically sorted: dependencies outermost)
        var sortedLibMethods = topoSortLibraryMethods(validator.libraries());
        if (validator.autoDispatch()) {
            handlers.replaceAll((tag, handler) -> bindLibraries(sortedLibMethods, handler));
        } else {
            body = bindLibraries(sortedLibMethods, body);
        }

        // 15. Resolve typed transaction-time roots and wrap the entrypoint for on-chain.
        // ScriptContext and deployer-applied @Param fields are intentionally not boundary roots.
        var wrapper = new ValidatorWrapper(types.namedDefinitions());
        PirTerm wrappedTerm;
        if (validator.autoDispatch()) {
            // Build datumOptional flags for auto-dispatch handlers
            Map<Integer, Integer> multiParamCounts = new LinkedHashMap<>();
            Map<Integer, Boolean> multiDatumOptional = new LinkedHashMap<>();
            Map<Integer, PirType> multiDatumTypes = new LinkedHashMap<>();
            Map<Integer, PirType> multiRedeemerTypes = new LinkedHashMap<>();
            for (var entry : validator.entries()) {
                multiParamCounts.put(entry.tag(), entry.parameterCount());
                if (entry.datum() != null) {
                    requireStrictBoundary(entry.datum(), types);
                    multiDatumTypes.put(entry.tag(), wrapperBoundaryType(entry.datum().type(), types));
                }
                requireStrictBoundary(entry.redeemer(), types);
                multiRedeemerTypes.put(entry.tag(), wrapperBoundaryType(entry.redeemer().type(), types));
                multiDatumOptional.put(entry.tag(), entry.optionalDatum());
            }
            wrappedTerm = wrapper.wrapMultiValidator(handlers, multiParamCounts,
                    multiDatumOptional, multiDatumTypes, multiRedeemerTypes);
        } else if (validator.purpose() == ScriptPurpose.SPENDING) {
            var entry = validator.entries().getFirst();
            PirType datumType = null;
            if (entry.datum() != null) {
                datumType = entry.datum().type();
                requireStrictBoundary(entry.datum(), types);
            }
            requireStrictBoundary(entry.redeemer(), types);
            wrappedTerm = wrapper.wrapSpendingValidator(body, entry.parameterCount(), entry.optionalDatum(),
                    wrapperBoundaryType(datumType, types),
                    wrapperBoundaryType(entry.redeemer().type(), types));
        } else {
            // Minting, Withdraw, Certifying, Voting, Proposing, Multi(manual) all use 2-param wrapper
            var entry = validator.entries().getFirst();
            requireStrictBoundary(entry.redeemer(), types);
            wrappedTerm = wrapper.wrapMintingPolicy(body, wrapperBoundaryType(entry.redeemer().type(), types));
        }

        // 15b. Register wrapper Error terms in source map (points to @Entrypoint method)
        if (context.isSourceMapEnabled() && !wrapper.getErrorTerms().isEmpty() && validator.failureLocation() != null) {
            for (var errorTerm : wrapper.getErrorTerms()) {
                frontend.positions().put(errorTerm, validator.failureLocation());
            }
            for (var errorTerm : validator.strictErrors()) {
                frontend.positions().put(errorTerm, validator.failureLocation());
            }
        }
        return wrappedTerm;
    }

    /** 13b. Wrap static field initializers as Let bindings (reverse order for correct scoping). */
    private static PirTerm bindStatics(List<StaticField> statics, PirTerm body, Frontend frontend) {
        for (int i = statics.size() - 1; i >= 0; i--) {
            var field = statics.get(i);
            body = new PirTerm.Let(field.name(), field.value(), body);
            if (frontend.staticBound() != null) frontend.staticBound().accept(field, body);
        }
        return body;
    }

    private static PirTerm bindLibraries(List<LibraryMethod> sorted, PirTerm body) {
        for (var libMethod : sorted) {
            if (containsVarRef(libMethod.body(), libMethod.qualifiedName())) {
                body = new PirTerm.LetRec(
                        List.of(new PirTerm.Binding(libMethod.qualifiedName(), libMethod.body())),
                        body);
            } else {
                body = new PirTerm.Let(libMethod.qualifiedName(), libMethod.body(), body);
            }
        }
        return body;
    }

    /** 16. Wrap with outer param lambdas. */
    private static PirTerm bindParams(List<ParamField> params, PirTerm body, Frontend frontend) {
        for (int i = params.size() - 1; i >= 0; i--) {
            var param = params.get(i);
            var rawName = "#" + param.name() + "__raw";
            var decoded = PirHelpers.wrapDecode(
                    new PirTerm.Var(rawName, new PirType.DataType()), param.type());
            var decodedBinding = new PirTerm.Let(param.name(), decoded, body);
            if (frontend.paramBound() != null) frontend.paramBound().accept(param, decodedBinding);
            body = new PirTerm.Lam(rawName, new PirType.DataType(), decodedBinding);
        }
        return body;
    }

    private static List<CompileResult.ParamInfo> paramInfos(List<ParamField> params) {
        return params.stream().map(param -> new CompileResult.ParamInfo(param.name(), param.javaType())).toList();
    }

    // --- compileMethod ---

    /** Assembles a {@code compileMethod} target: a lambda over Data that decodes its arguments and calls the method. */
    public static CompileResult method(MethodProgram program, CompilationContext context, Frontend frontend) {
        // 11. Build body: Data-accepting lambda that calls target method by Var reference
        // Build application: Var("method") applied to decoded args
        PirTerm application = new PirTerm.Var(program.binderName(), program.type());
        for (var parameter : program.parameters()) {
            application = new PirTerm.App(application,
                    new PirTerm.Var("#" + parameter.name() + "__dec", parameter.type()));
        }

        // Wrap with outer Lam + decode for each param (inside-out)
        PirTerm body = application;
        var parameters = program.parameters();
        for (int i = parameters.size() - 1; i >= 0; i--) {
            var parameter = parameters.get(i);
            var rawName = "#" + parameter.name() + "__raw";
            var decoded = PirHelpers.wrapDecode(
                    new PirTerm.Var(rawName, new PirType.DataType()), parameter.type());
            body = new PirTerm.Let("#" + parameter.name() + "__dec", decoded, body);
            body = new PirTerm.Lam(rawName, new PirType.DataType(), body);
        }

        // 12. Wrap ALL methods as Let/LetRec bindings (target + helpers, dependency-ordered)
        body = wrapMethodBindings(program.methods(), body, frontend);
        // 14. Wrap static field initializers
        body = bindStatics(program.statics(), body, frontend);
        // 15. Wrap library methods as Let bindings
        body = bindLibraries(topoSortLibraryMethods(program.libraries()), body);
        // 16. Wrap with outer @Param lambdas
        body = bindParams(program.params(), body, frontend);

        frontend.finalCheck().accept(body);
        // 17. Lower to UPLC (with source map support)
        var lowered = PirBackend.lowerQuietly(body, context, frontend.positions(),
                bindings(program.methods(), program.statics(), program.libraries()));
        return new CompileResult(
                lowered.program(), context.diagnostics(), paramInfos(program.params()), lowered.pir(),
                lowered.program().term(), lowered.sourceMap(),
                context.target(), context.optimizationReport(), context.javacProvenance());
    }

    // --- Bindings ---

    /** The binders {@link org.julclang.compiler.pir.DelayedMethodPass} reads: a method with no parameters that may fail runs at each call. */
    private static PirBackend.Bindings bindings(List<Method> methods, List<StaticField> statics, Collection<LibraryMethod> libraries) {
        var binders = new LinkedHashSet<String>();
        methods.forEach(method -> binders.add(method.name()));
        libraries.forEach(method -> binders.add(method.qualifiedName()));
        var fields = new LinkedHashSet<String>();
        statics.forEach(field -> fields.add(field.name()));
        return new PirBackend.Bindings(binders, fields);
    }

    private static boolean containsVarRef(PirTerm term, String name) {
        return PirHelpers.containsVarRef(term, name);
    }

    /**
     * Wrap class/validator methods as Let/LetRec bindings in dependency order.
     * Uses Tarjan's SCC to handle forward references and mutual recursion.
     */
    private static PirTerm wrapMethodBindings(List<Method> methods, PirTerm body, Frontend frontend) {
        if (methods.isEmpty()) return body;

        var bindings = new ArrayList<MethodDependencyResolver.NamedBinding>();
        for (var method : methods) {
            bindings.add(new MethodDependencyResolver.NamedBinding(method.name(), method.body()));
        }

        var groups = MethodDependencyResolver.resolveDependencyOrder(bindings, ValidatorAssembly::containsVarRef);

        // Wrap from first (innermost) to last (outermost)
        for (var group : groups) {
            if (group.isSingle()) {
                var b = group.bindings().get(0);
                if (containsVarRef(b.body(), b.name())) {
                    body = new PirTerm.LetRec(
                            List.of(new PirTerm.Binding(b.name(), b.body())), body);
                } else {
                    body = new PirTerm.Let(b.name(), b.body(), body);
                }
            } else if (group.bindings().size() == 2) {
                // Mutual recursion — multi-binding LetRec (Bekic's theorem in UplcGenerator)
                var pirBindings = new ArrayList<PirTerm.Binding>();
                for (var b : group.bindings()) {
                    pirBindings.add(new PirTerm.Binding(b.name(), b.body()));
                }
                body = new PirTerm.LetRec(pirBindings, body);
            } else {
                var names = group.bindings().stream()
                        .map(MethodDependencyResolver.NamedBinding::name)
                        .toList();
                throw new CompilerException(
                        "Mutual recursion with more than 2 methods is not supported: " + names);
            }
            if (frontend.methodBound() != null) {
                for (var b : group.bindings()) frontend.methodBound().accept(b.name(), body);
            }
        }

        return body;
    }

    /**
     * Topologically sort library methods so dependencies are outermost in the Let chain.
     * Methods with no library dependencies come last (outermost); dependent methods come first (innermost).
     * The iteration order of the result is suitable for direct use in the Let wrapping loop:
     * first item = innermost, last item = outermost.
     */
    private static List<LibraryMethod> topoSortLibraryMethods(Collection<LibraryMethod> methods) {
        var methodList = new ArrayList<>(methods);
        var methodMap = new LinkedHashMap<String, LibraryMethod>();
        for (var m : methodList) methodMap.put(m.qualifiedName(), m);

        // Build dependency graph: name -> set of library method names referenced in body
        var deps = new LinkedHashMap<String, Set<String>>();
        for (var m : methodList) {
            var myDeps = new LinkedHashSet<String>();
            for (var other : methodList) {
                if (!other.qualifiedName().equals(m.qualifiedName())
                        && containsVarRef(m.body(), other.qualifiedName())) {
                    myDeps.add(other.qualifiedName());
                }
            }
            deps.put(m.qualifiedName(), myDeps);
        }

        // Kahn's algorithm: emit methods whose dependencies are already emitted
        var emitted = new LinkedHashSet<String>();
        var result = new ArrayList<LibraryMethod>();
        var remaining = new LinkedHashSet<>(deps.keySet());
        while (!remaining.isEmpty()) {
            String next = null;
            for (String name : remaining) {
                var d = new LinkedHashSet<>(deps.get(name));
                d.removeAll(emitted);
                if (d.isEmpty()) {
                    next = name;
                    break;
                }
            }
            if (next == null) {
                // Cycle — add remaining in original order
                for (var m : methodList) {
                    if (remaining.contains(m.qualifiedName())) {
                        result.add(m);
                    }
                }
                break;
            }
            remaining.remove(next);
            emitted.add(next);
            result.add(methodMap.get(next));
        }

        // Kahn's produces dependencies-first order (leaves first).
        // For Let wrapping, we need dependents first (innermost), dependencies last (outermost).
        Collections.reverse(result);
        return result;
    }
}
