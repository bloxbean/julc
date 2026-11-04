package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.JulcCompiler.ScriptPurpose;
import org.julclang.compiler.OnchainOverloads;
import org.julclang.compiler.backend.PirBackend;
import org.julclang.compiler.codegen.ValidatorAssembly;
import org.julclang.compiler.debug.DebugMetadataCollector;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.lower.BindingCheck;
import org.julclang.compiler.frontend.javac.lower.Lowering;
import org.julclang.compiler.frontend.javac.lower.Scope;
import org.julclang.compiler.frontend.javac.subset.SubsetScan;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirSubstitution;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.Constant;
import org.julclang.core.source.SourceLocation;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A compilation on the javac frontend (ADR-068 D2): the coordinator, which finds a program's parts, has {@link Lowering}
 * lower them, and hands them to {@link ValidatorAssembly}. It walks no expression. Its steps and their order are the
 * reference's, numbered as in {@code JulcCompiler}, so a source with two rejections reports the same one first (I2a).
 * A compilation runs in one {@link JavacProgram#read} query.
 */
public final class JavacCompilation {

    /** What a method's attempt names when it is rejected, not stopped at a construct. */
    static final String REJECTED = "(rejected)";

    /** What the reference would have lowered of one method: its term and type, or the construct that stopped it. */
    record MethodAttempt(String binder, PirType type, PirTerm term, String construct) {
    }

    private final JavacProgram program;
    private final JavacFacts facts;
    private final CompilationContext context;
    private final JavacDiscovery discovery;
    private final Lowering lowering;
    private final JavacLibraries libraries;

    private JavacCompilation(JavacProgram program, JavacFacts facts, CompilationContext context, StdlibLookup stdlib) {
        this.program = program;
        this.facts = facts;
        this.context = context;
        this.discovery = new JavacDiscovery(facts);
        this.lowering = new Lowering(facts, context, stdlib);
        lowering.terms().shareShapeChecks();
        this.libraries = new JavacLibraries(program, facts, discovery, lowering);
    }

    /**
     * What a validator's compilation asks for beyond its program.
     *
     * @param captureDetails whether the result keeps its PIR and UPLC
     * @param contractSchema whether to describe the validator's interfaces ({@code compileContract})
     * @param debugLibraries the library sources debug metadata names, in the order given, or null to collect none
     *                       ({@code compileForDebug})
     */
    public record Request(boolean captureDetails, boolean contractSchema, List<String> debugLibraries) {
    }

    /**
     * A compiled validator: its result, its schema if asked, and its debug metadata's collector with what the backend
     * returned, which the caller completes with the compiler's options.
     */
    public record Compiled(CompileResult result, ContractSchema schema, DebugMetadataCollector debug,
                           PirBackend.Result lowered) {
    }

    /**
     * Compiles the validator of {@code root}, with {@code pool} on the source path (D8).
     *
     * @param stdlib the compilation's lookup, or null
     */
    public static CompileResult validator(JavacFrontend.Unit root, List<JavacFrontend.Unit> pool, StdlibLookup stdlib,
                                          CompilationContext context, boolean captureDetails) {
        return validator(root, pool, stdlib, context, new Request(captureDetails, false, null)).result();
    }

    /** {@link #validator(JavacFrontend.Unit, List, StdlibLookup, CompilationContext, boolean)}, with what {@code request} asks. */
    public static Compiled validator(JavacFrontend.Unit root, List<JavacFrontend.Unit> pool, StdlibLookup stdlib,
                                     CompilationContext context, Request request) {
        var program = attributed(root, pool, context);
        return program.read(facts -> new JavacCompilation(program, facts, context, stdlib).validator(request));
    }

    /** Compiles the static method {@code methodName} of the first class of {@code root} ({@code compileMethod}). */
    public static CompileResult method(JavacFrontend.Unit root, List<JavacFrontend.Unit> pool, String methodName,
                                       StdlibLookup stdlib, CompilationContext context) {
        var program = attributed(root, pool, context);
        return program.read(facts -> new JavacCompilation(program, facts, context, stdlib).method(methodName));
    }

    private static JavacProgram attributed(JavacFrontend.Unit root, List<JavacFrontend.Unit> pool,
                                           CompilationContext context) {
        var program = JavacProgram.attribute(List.of(root), pool);
        context.setJavacProvenance(JavacProvenance.frontend(program.result()));
        return program;
    }

    /**
     * I1b, for the evidence tooling: every method the reference lowers of {@code root}, each lowered on its own, so a
     * method is compared even where another one stops or rejects the program. I13's check is not a rejection: it fails
     * the caller. No compilation path goes through here.
     *
     * @param methodName the {@code compileMethod} target, or null for the validator of {@code root}
     */
    static List<MethodAttempt> attempt(JavacFrontend.Unit root, List<JavacFrontend.Unit> pool, String methodName,
                                       StdlibLookup stdlib, CompilationContext context) {
        var program = attributed(root, pool, context);
        return program.read(facts -> new JavacCompilation(program, facts, context, stdlib).attempt(methodName));
    }

    private List<MethodAttempt> attempt(String methodName) {
        var unit = program.roots().getFirst();
        ClassTree type = methodName == null ? discovery.validatorClass(unit) : discovery.firstClass(unit);
        program.registerTypes();
        var attempts = new ArrayList<MethodAttempt>();
        for (var library : libraries.attempts()) {
            var lowered = library.lowered();
            attempts.add(lowered == null ? new MethodAttempt(library.binder(), null, null, library.construct())
                    : new MethodAttempt(library.binder(), lowered.type(), lowered.body(), null));
        }
        lowering.classBegins();
        var parts = new ClassParts(type);
        lowering.entryMethods(methodName == null ? discovery.methods(type).stream().filter(discovery::isEntrypoint).toList()
                : discovery.methods(type).stream().filter(method -> method.getName().contentEquals(methodName)).toList());
        parts.declareMethods(type);
        for (var method : discovery.methods(type)) {
            boolean entry = methodName == null && discovery.isEntrypoint(method);
            if (!discovery.isStatic(method) && !entry) continue;
            try {
                // As the program lowers it: an entry point's boolean returns are guarded under a source map.
                var term = lowering.method(method, parts.scope, entry && context.isSourceMapEnabled());
                if (Boolean.getBoolean(JulcCompiler.VERIFY_BINDER_NAMESPACE)) {
                    BindingCheck.check(parts.bindAround(term), lowering.terms().provenance());
                }
                attempts.add(new MethodAttempt(parts.binder(method), parts.methodType(method), term, null));
            } catch (NotPorted e) {
                attempts.add(new MethodAttempt(parts.binder(method), null, null, e.construct()));
            } catch (CompilerException e) {
                attempts.add(new MethodAttempt(parts.binder(method), null, null, REJECTED));
            }
        }
        return attempts;
    }

    // --- Parts ---

    /** Steps 6 to 10: what a class gives every method, and the scope they all see (I13: seeded before any body). */
    private final class ClassParts {
        final ClassTree type;
        final String owner;
        final List<JavacDiscovery.ParamField> params;
        final List<VariableTree> statics;
        final Scope scope;
        private final Map<MethodTree, PirType> methodTypes = new LinkedHashMap<>();
        private final Map<String, Element> methodElements = new LinkedHashMap<>();

        ClassParts(ClassTree type) {
            this.type = type;
            owner = facts.identity((TypeElement) facts.element(type));
            var rejected = new ArrayList<CompilerDiagnostic>();
            params = discovery.paramFields(type, rejected);
            if (!rejected.isEmpty()) throw new CompilerException(rejected);
            statics = discovery.staticFields(type);
            var bound = Scope.empty();
            for (var param : params) bound = bound.bind(param.element(), param.type());
            for (var field : statics) {
                var element = facts.element(field);
                bound = bound.bind(element, facts.pirType(element.asType(), field.getType()));
            }
            scope = bound;
        }

        /** {@code term} under stand-ins for this class's field binders, for I13's check of a method on its own. */
        PirTerm bindAround(PirTerm term) {
            for (var param : params) term = standIn(param.element(), term);
            for (var field : statics) term = standIn(facts.element(field), term);
            for (var method : discovery.methods(type)) {
                if (!discovery.isStatic(method)) continue;
                term = standIn(binder(method), facts.binderMethod((ExecutableElement) facts.element(method)), term);
            }
            return term;
        }

        private PirTerm standIn(Element element, PirTerm body) {
            return standIn(scope.lookup(element).name(), element, body);
        }

        private PirTerm standIn(String name, Element element, PirTerm body) {
            var binding = new PirTerm.Let(name, new PirTerm.Const(Constant.unit()), body);
            lowering.terms().assembled(binding, name, element);
            return binding;
        }

        /** Step 10: every static method's type, before any body is lowered, so a bad signature is reported first. */
        void declareMethods(ClassTree type) {
            for (var method : discovery.methods(type)) {
                if (discovery.isStatic(method)) {
                    methodType(method);
                    lowering.methodDeclared(binder(method));
                }
            }
        }

        String binder(MethodTree method) {
            return owner + "." + method.getName();
        }

        PirType methodType(MethodTree method) {
            return methodTypes.computeIfAbsent(method, JavacCompilation.this::computeMethodType);
        }

        /** Step 11b: the static fields' initializers. */
        List<ValidatorAssembly.StaticField> lowerStatics() {
            var lowered = new ArrayList<ValidatorAssembly.StaticField>();
            for (var field : statics) {
                var name = scope.lookup(facts.element(field)).name();
                var value = lowering.initializer(field.getInitializer(), scope);
                requireNoMethodCall(field, value);
                lowered.add(new ValidatorAssembly.StaticField(name, value));
            }
            return lowered;
        }

        /** JULC0057: initializers are bound outside the class's methods, so a call of one could not be bound. */
        private void requireNoMethodCall(VariableTree field, PirTerm value) {
            var free = PirSubstitution.collectFreeVarNames(value);
            for (var method : discovery.methods(type)) {
                boolean isStatic = facts.element(method).getModifiers().contains(Modifier.STATIC);
                if (isStatic && free.contains(binder(method))) {
                    throw CompilerTypeDiagnostics.staticInitializerCallsMethod(field.getName().toString(),
                            method.getName().toString(), facts.location(field.getInitializer()));
                }
            }
        }

        List<ValidatorAssembly.ParamField> assemblyParams() {
            return params.stream().map(param -> new ValidatorAssembly.ParamField(
                    scope.lookup(param.element()).name(), param.type(), param.tree().getType().toString(),
                    facts.location(param.tree()))).toList();
        }

        /** A static method's lowering, checked as an overload of the ones before it (JULC0054). */
        void lowerMethod(MethodTree method, OnchainOverloads overloads, Map<String, ValidatorAssembly.Method> methods) {
            var body = lowering.method(method, scope, false);
            overloads.check(method.getName().toString(), methodType(method), body, () -> facts.location(method));
            methods.put(binder(method), new ValidatorAssembly.Method(binder(method), body));
            methodElements.put(binder(method), facts.binderMethod((ExecutableElement) facts.element(method)));
        }

        /** What the assembly needs of this compilation: where terms came from, and I13's check of the result. */
        ValidatorAssembly.Frontend frontend() {
            return frontend(null);
        }

        /** {@link #frontend()}, telling {@code debug}, if not null, the binders of {@code @Param}s as the reference does. */
        ValidatorAssembly.Frontend frontend(DebugMetadataCollector debug) {
            var terms = lowering.terms();
            var elements = new LinkedHashMap<String, Element>();
            for (var param : params) elements.put(scope.lookup(param.element()).name(), param.element());
            for (var field : statics) elements.put(scope.lookup(facts.element(field)).name(), facts.element(field));
            return new ValidatorAssembly.Frontend(terms.positions(), debug == null ? null : debug.provenance(),
                    (param, binding) -> {
                        terms.assembled(binding, param.name(), elements.get(param.name()));
                        terms.declared(elements.get(param.name()), binding, param.type());
                    },
                    term -> {
                        if (Boolean.getBoolean(JulcCompiler.VERIFY_BINDER_NAMESPACE)) BindingCheck.check(term, terms.provenance());
                    },
                    (field, binding) -> terms.assembled(binding, field.name(), elements.get(field.name())),
                    (name, binding) -> terms.assembled(binding, name, methodElements.getOrDefault(name, libraries.binders().get(name))),
                    terms::bindShapeChecks);
        }
    }

    private PirType computeMethodType(MethodTree method) {
        return facts.methodType((ExecutableElement) facts.element(method));
    }

    /** ADR-060 G2, enabled in tests: the binders of this compilation are reserved names or names the sources declare. */
    private void verifyBinderNamespace(CompilationUnitTree unit) {
        if (!Boolean.getBoolean(JulcCompiler.VERIFY_BINDER_NAMESPACE)) return;
        var declared = new HashSet<String>();
        var methods = new HashSet<String>();
        var scanner = new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree tree, Void unused) {
                declared.add(tree.getName().toString());
                return super.visitVariable(tree, null);
            }

            @Override
            public Void visitMethod(MethodTree tree, Void unused) {
                methods.add(tree.getName().toString());
                return super.visitMethod(tree, null);
            }
        };
        scanner.scan(unit, null);
        for (var library : libraries.units()) scanner.scan(library, null);
        context.verifyBinderNames(name -> {
            if (PirHelpers.isGeneratedName(name)) return true;
            int mark = name.indexOf(Scope.MARK);
            if (declared.contains(mark < 0 ? name : name.substring(0, mark))) return true;
            int dot = name.lastIndexOf('.');
            return dot > 0 && methods.contains(name.substring(dot + 1));
        });
    }

    /** What the compilation lowers of the root {@code unit}: {@code type}, and the unit's classes if it is a library too. */
    private Set<Tree> lowered(CompilationUnitTree unit, ClassTree type) {
        var lowered = discovery.lowered(type);
        lowered.addAll(libraries.lowered(unit));
        return lowered;
    }

    // --- Validators ---

    /**
     * What a compilation of {@code root} reports of the subset before it lowers anything, in its order: javac's rejection of
     * the source, or the subset's rules, then its scan, with their warnings. A source with a validator is checked as its
     * validator's compilation checks it, any other as {@code compileMethod} checks it. JuLC's tools check a source with it
     * without compiling (the playground's check, ADR-068 M6), so a check and a compilation give the same codes at the
     * same positions. Empty when the source keeps to the subset.
     */
    public static List<CompilerDiagnostic> subset(JavacFrontend.Unit root, List<JavacFrontend.Unit> pool,
                                                  CompilationContext context) {
        try {
            var program = attributed(root, pool, context);
            program.read(facts -> {
                var compilation = new JavacCompilation(program, facts, context, null);
                var unit = program.roots().getFirst();
                var validator = compilation.discovery.validatorClassOrNull(unit);
                var compiled = validator != null ? JavacGate.Compiled.VALIDATOR : JavacGate.Compiled.METHOD;
                compilation.subsetRules(compiled);
                compilation.subsetScan(unit, validator != null ? validator : compilation.discovery.firstClassOrNull(unit));
                return null;
            });
            return context.diagnostics();
        } catch (CompilerException rejection) {
            var diagnostics = new ArrayList<>(context.diagnostics());
            diagnostics.addAll(rejection.diagnostics());
            return List.copyOf(diagnostics);
        }
    }

    /** 1b. The subset's rules, first as on the JavaParser frontend, whose compilations get them from the gate. */
    private void subsetRules(JavacGate.Compiled compiled) {
        SubsetRules.checkOnJavacFrontend(facts, program.roots(), program.libraries(), compiled);
        SubsetRules.warnings(facts, program.roots()).forEach(context::report);
    }

    /** 2. The subset's scan of the root, {@code type} being what the compilation lowers of it, and of the libraries. */
    private void subsetScan(CompilationUnitTree unit, ClassTree type) {
        SubsetScan.check(facts, unit, lowered(unit, type)).forEach(context::report);
        libraries.scan().forEach(context::report);
    }

    private Compiled validator(Request request) {
        var unit = program.roots().getFirst();
        subsetRules(JavacGate.Compiled.VALIDATOR);
        // 1c. Debug metadata's declarations, scanned before the subset is validated, as in the reference
        DebugMetadataCollector debug = null;
        if (request.debugLibraries() != null) {
            debug = JavacDebugScan.collector(facts, debugSources(unit, request.debugLibraries()),
                    debugUnits(unit, request.debugLibraries()));
            lowering.terms().collectDebugMetadata(debug);
        }
        // 2. Validate subset
        subsetScan(unit, discovery.validatorClassOrNull(unit));
        verifyBinderNamespace(unit);
        // 3. Validate: library units hold no validator annotation
        libraries.requireNoValidators();
        // 4. Find annotated validator class and determine purpose
        var type = discovery.validatorClass(unit);
        var purpose = discovery.purpose(type);
        // 5. Register types
        program.registerTypes();
        // 6, 6b. @Param and static fields
        var parts = new ClassParts(type);
        // 7. Find @Entrypoint method(s) and validate
        var entrypoints = entrypoints(type, purpose);
        // 8. Compile library static methods
        var libraryMethods = libraries.lower();
        // 10. Every static method's type
        lowering.classBegins();
        parts.declareMethods(type);
        // The entrypoints run once per evaluation (JULC0056, ADR-060)
        lowering.entryMethods(entrypoints.stream().map(JavacDiscovery.EntryPoint::method).toList());

        // 11b. Compile static field initializers
        var statics = parts.lowerStatics();
        // 11. Generate PIR for helper methods
        var entrypointNames = new HashSet<String>();
        for (var method : discovery.methods(type)) {
            if (discovery.isEntrypoint(method)) entrypointNames.add(method.getName().toString());
        }
        var overloads = new OnchainOverloads(type.getSimpleName().toString(), entrypointNames);
        var helpers = new LinkedHashMap<String, ValidatorAssembly.Method>();
        for (var method : discovery.methods(type)) {
            if (discovery.isStatic(method) && !discovery.isEntrypoint(method)) {
                parts.lowerMethod(method, overloads, helpers);
            }
        }

        // 12. Generate PIR for entrypoint(s)
        boolean autoDispatch = purpose == ScriptPurpose.MULTI
                && entrypoints.stream().noneMatch(entry -> entry.tag() < 0);
        var entries = new ArrayList<ValidatorAssembly.Entry>();
        var strictErrors = new ArrayList<PirTerm.Error>();
        for (var entry : entrypoints) {
            var function = lowering.method(entry.method(), parts.scope, context.isSourceMapEnabled());
            var parameters = entryParameters(entry.method(), function);
            var transformed = ValidatorAssembly.strictEntrypoint(function, parameters, facts.registry(),
                    lowering.terms().provenance().rebuildsOf(function));
            strictErrors.addAll(transformed.errors());
            boolean hasDatum = parameters.size() == 3 && (autoDispatch ? entry.tag() == 1 : purpose == ScriptPurpose.SPENDING);
            entries.add(new ValidatorAssembly.Entry(entry.tag(), parameters.size(), transformed.function(),
                    hasDatum ? parameters.get(0) : null, parameters.get(hasDatum ? 1 : 0),
                    hasDatum && parameters.get(0).type() instanceof PirType.OptionalType));
        }

        // 12b. What lowering warned of
        lowering.drainWarnings().forEach(context::report);

        // 13-22. Everything from here on takes PIR and resolved types (F846).
        var validator = new ValidatorAssembly.Validator(purpose, autoDispatch, entries, List.copyOf(helpers.values()),
                statics, libraryMethods, parts.assemblyParams(), failureLocation(entrypoints.getFirst().method()), strictErrors);
        var assembled = ValidatorAssembly.validator(validator, registry(), context, parts.frontend(debug),
                request.captureDetails());
        // The schema is read after the bytes are built, so its rejection comes after the program's, as in the reference.
        var schema = request.contractSchema()
                ? new JavacContractSchema(facts).of(type, purpose, entrypoints, parts.params) : null;
        return new Compiled(assembled.result(), schema, debug, assembled.lowered());
    }

    /** The sources debug metadata names: the root, then each library given, with the reference's ids and names. */
    private List<DebugMetadataCollector.SourceText> debugSources(CompilationUnitTree root, List<String> libraries) {
        var sources = new ArrayList<DebugMetadataCollector.SourceText>();
        sources.add(new DebugMetadataCollector.SourceText("validator", JavacDebugScan.uri(root, "validator.java"),
                facts.source(root)));
        for (int i = 0; i < libraries.size(); i++) {
            String first = JavacFrontend.shared().firstClassOrInterface(libraries.get(i));
            sources.add(new DebugMetadataCollector.SourceText("library-" + i,
                    first == null ? "library-" + i + ".java" : first + ".java", libraries.get(i)));
        }
        return sources;
    }

    /** The tree of each source {@link #debugSources} names, or null for a library javac did not read. */
    private List<CompilationUnitTree> debugUnits(CompilationUnitTree root, List<String> libraries) {
        var read = new HashMap<String, CompilationUnitTree>();
        for (var library : program.libraries()) read.putIfAbsent(facts.source(library), library);
        var units = new ArrayList<CompilationUnitTree>();
        units.add(root);
        for (var library : libraries) units.add(read.get(library));
        return units;
    }

    private List<JavacDiscovery.EntryPoint> entrypoints(ClassTree type, ScriptPurpose purpose) {
        List<JavacDiscovery.EntryPoint> entries;
        if (purpose == ScriptPurpose.MULTI) {
            entries = discovery.multiEntrypoints(type);
        } else {
            var method = discovery.entrypoint(type);
            discovery.requireEntrypointParameters(method, purpose, type);
            entries = List.of(new JavacDiscovery.EntryPoint(method, "DEFAULT", -1));
        }
        for (var entry : entries) {
            discovery.requireStaticEntrypoint(entry.method(), type);
        }
        return entries;
    }

    /** The parameters of an entry point, each under the binder {@code function} binds it to. */
    private List<ValidatorAssembly.Parameter> entryParameters(MethodTree method, PirTerm function) {
        var parameters = new ArrayList<ValidatorAssembly.Parameter>();
        PirTerm cursor = function;
        for (var parameter : method.getParameters()) {
            var lambda = (PirTerm.Lam) cursor;
            parameters.add(new ValidatorAssembly.Parameter(parameter.getName().toString(), lambda.param(),
                    lambda.paramType(), facts.location(parameter)));
            cursor = lambda.body();
        }
        return parameters;
    }

    /** Where a validator that returned false is reported: its {@code @Entrypoint} method. */
    private SourceLocation failureLocation(MethodTree method) {
        var at = facts.location(method);
        return new SourceLocation(at.fileName(), at.line(), at.column(), "validator returned false");
    }

    private TypeRegistry registry() {
        return facts.registry();
    }

    // --- compileMethod ---

    private CompileResult method(String methodName) {
        var unit = program.roots().getFirst();
        subsetRules(JavacGate.Compiled.METHOD);
        // 2. Validate subset
        subsetScan(unit, discovery.firstClassOrNull(unit));
        verifyBinderNamespace(unit);
        // 3. Find the target class (first non-interface class)
        var type = discovery.firstClass(unit);
        // 4. Find the target method
        var target = discovery.methods(type).stream()
                .filter(method -> discovery.isStatic(method) && method.getName().contentEquals(methodName))
                .findFirst()
                .orElseThrow(() -> new CompilerException(
                        "No static method '" + methodName + "' found in " + type.getSimpleName()));
        // 5. Register types
        program.registerTypes();
        // 7, 7b. @Param and static fields
        var parts = new ClassParts(type);
        // 8. Compile libraries
        var libraryMethods = libraries.lower();
        // 9. Every static method's type
        lowering.classBegins();
        parts.declareMethods(type);

        // The selected entry point is decoded from external Data: reject native Value anywhere in its boundary graph.
        var parameters = new ArrayList<ValidatorAssembly.Parameter>();
        for (var parameter : target.getParameters()) {
            String name = parameter.getName().toString();
            var at = facts.location(parameter);
            var parameterType = facts.parameterType(parameter);
            if (registry().containsNativeOpaque(parameterType)) {
                throw CompilerTypeDiagnostics.nativeTypeAtDataBoundary(name, parameterType, at);
            }
            var array = PirType.nativeArrayIn(parameterType);
            if (array != null) {
                throw CompilerTypeDiagnostics.nativeArrayNotDataEncodable(
                        "Parameter '" + name + "' of the compiled method", array, at);
            }
            parameters.add(new ValidatorAssembly.Parameter(name, name, parameterType, at));
        }

        // The target runs once per evaluation (JULC0056, ADR-060)
        lowering.entryMethods(List.of(target));
        var statics = parts.lowerStatics();
        // Compile ALL static methods (including target) so cross-references work
        var overloads = new OnchainOverloads(type.getSimpleName().toString(), Set.of());
        var methods = new LinkedHashMap<String, ValidatorAssembly.Method>();
        for (var method : discovery.methods(type)) {
            if (discovery.isStatic(method)) parts.lowerMethod(method, overloads, methods);
        }

        lowering.drainWarnings().forEach(context::report);

        // 11-17. Everything from here on takes PIR and resolved types (F846).
        return ValidatorAssembly.method(new ValidatorAssembly.MethodProgram(parts.binder(target), parts.methodType(target),
                parameters, List.copyOf(methods.values()), statics, libraryMethods, parts.assemblyParams()),
                context, parts.frontend());
    }
}
