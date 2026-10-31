package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerTypeDiagnostics;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.JulcCompiler.ScriptPurpose;
import org.julclang.compiler.OnchainOverloads;
import org.julclang.compiler.codegen.ValidatorAssembly;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.frontend.javac.lower.BindingCheck;
import org.julclang.compiler.frontend.javac.lower.Lowering;
import org.julclang.compiler.frontend.javac.lower.Scope;
import org.julclang.compiler.frontend.javac.subset.SubsetScan;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.resolve.TypeRegistry;
import org.julclang.core.source.SourceLocation;

import javax.lang.model.element.ExecutableElement;
import java.util.ArrayList;
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

    /** What the reference would have lowered of one method: its term and type, or the construct that stopped it. */
    record MethodAttempt(String binder, PirType type, PirTerm term, String construct) {
    }

    private final JavacProgram program;
    private final JavacFacts facts;
    private final CompilationContext context;
    private final JavacDiscovery discovery;
    private final Lowering lowering;

    private JavacCompilation(JavacProgram program, JavacFacts facts, CompilationContext context) {
        this.program = program;
        this.facts = facts;
        this.context = context;
        this.discovery = new JavacDiscovery(facts);
        this.lowering = new Lowering(facts, context);
    }

    /** Compiles the validator of {@code root}, with {@code pool} on the source path (D8). */
    public static CompileResult validator(JavacFrontend.Unit root, List<JavacFrontend.Unit> pool,
                                          CompilationContext context, boolean captureDetails) {
        return JavacGate.onJavacFrontend(() -> {
            var program = attributed(root, pool, context);
            return program.read(facts -> new JavacCompilation(program, facts, context).validator(captureDetails));
        });
    }

    /** Compiles the static method {@code methodName} of the first class of {@code root} ({@code compileMethod}). */
    public static CompileResult method(JavacFrontend.Unit root, List<JavacFrontend.Unit> pool, String methodName,
                                       CompilationContext context) {
        return JavacGate.onJavacFrontend(() -> {
            var program = attributed(root, pool, context);
            return program.read(facts -> new JavacCompilation(program, facts, context).method(methodName));
        });
    }

    private static JavacProgram attributed(JavacFrontend.Unit root, List<JavacFrontend.Unit> pool,
                                           CompilationContext context) {
        var program = JavacProgram.attribute(List.of(root), pool);
        context.setJavacProvenance(JavacProvenance.of(null, program.result()));
        return program;
    }

    /**
     * I1b, for the evidence tooling: every method the reference lowers of {@code root}, each lowered on its own, so a
     * method is compared even where another one stops the program. No compilation path goes through here.
     *
     * @param methodName the {@code compileMethod} target, or null for the validator of {@code root}
     */
    static List<MethodAttempt> attempt(JavacFrontend.Unit root, List<JavacFrontend.Unit> pool, String methodName,
                                       CompilationContext context) {
        var program = attributed(root, pool, context);
        return program.read(facts -> new JavacCompilation(program, facts, context).attempt(methodName));
    }

    private List<MethodAttempt> attempt(String methodName) {
        var unit = program.roots().getFirst();
        ClassTree type = methodName == null ? discovery.validatorClass(unit) : discovery.firstClass(unit);
        program.registerTypes();
        var parts = new ClassParts(type);
        var attempts = new ArrayList<MethodAttempt>();
        for (var method : discovery.methods(type)) {
            if (!JavacDiscovery.isStatic(method) && !discovery.isEntrypoint(method)) continue;
            try {
                attempts.add(new MethodAttempt(parts.binder(method), parts.methodType(method),
                        lowering.method(method, parts.scope, false), null));
            } catch (NotPorted e) {
                attempts.add(new MethodAttempt(parts.binder(method), null, null, e.construct()));
            }
        }
        return attempts;
    }

    // --- Parts ---

    /** Steps 6 to 10: what a class gives every method, and the scope they all see (I13: seeded before any body). */
    private final class ClassParts {
        final String owner;
        final List<JavacDiscovery.ParamField> params;
        final List<VariableTree> statics;
        final Scope scope;
        private final Map<MethodTree, PirType> methodTypes = new LinkedHashMap<>();

        ClassParts(ClassTree type) {
            owner = facts.identity((javax.lang.model.element.TypeElement) facts.element(type));
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

        /** Step 10: every static method's type, before any body is lowered, so a bad signature is reported first. */
        void declareMethods(ClassTree type) {
            for (var method : discovery.methods(type)) {
                if (JavacDiscovery.isStatic(method)) methodType(method);
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
                lowered.add(new ValidatorAssembly.StaticField(name, lowering.initializer(field.getInitializer(), scope)));
            }
            return lowered;
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
        }

        /** What the assembly needs of this compilation: where terms came from, and I13's check of the result. */
        ValidatorAssembly.Frontend frontend() {
            var terms = lowering.terms();
            var elements = new LinkedHashMap<String, javax.lang.model.element.Element>();
            for (var param : params) elements.put(scope.lookup(param.element()).name(), param.element());
            for (var field : statics) elements.put(scope.lookup(facts.element(field)).name(), facts.element(field));
            return new ValidatorAssembly.Frontend(terms.positions(), null,
                    (param, binding) -> terms.assembled(binding, elements.get(param.name())),
                    term -> {
                        if (Boolean.getBoolean(JulcCompiler.VERIFY_BINDER_NAMESPACE)) BindingCheck.check(term, terms.provenance());
                    },
                    (field, binding) -> terms.assembled(binding, elements.get(field.name())));
        }
    }

    private PirType computeMethodType(MethodTree method) {
        var element = (ExecutableElement) facts.element(method);
        var parameters = method.getParameters();
        var types = new ArrayList<PirType>();
        for (var parameter : parameters) types.add(facts.pirType(facts.element(parameter).asType(), parameter));
        PirType type = facts.pirType(element.getReturnType(), method.getReturnType());
        for (int i = types.size() - 1; i >= 0; i--) type = new PirType.FunType(types.get(i), type);
        return type;
    }

    /** Library lowering is not ported: a pool unit javac read is a method the program may call. */
    private void requireNoLibraries() {
        if (!program.libraries().isEmpty()) {
            var unit = program.libraries().getFirst();
            throw NotPorted.at(facts, unit, "a library unit");
        }
    }

    /** ADR-060 G2, enabled in tests: the binders of this compilation are reserved names or names the sources declare. */
    private void verifyBinderNamespace(CompilationUnitTree unit) {
        if (!Boolean.getBoolean(JulcCompiler.VERIFY_BINDER_NAMESPACE)) return;
        var declared = new HashSet<String>();
        var methods = new HashSet<String>();
        new TreeScanner<Void, Void>() {
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
        }.scan(unit, null);
        context.verifyBinderNames(name -> {
            if (PirHelpers.isGeneratedName(name)) return true;
            int mark = name.indexOf('\'');
            if (declared.contains(mark < 0 ? name : name.substring(0, mark))) return true;
            int dot = name.lastIndexOf('.');
            return dot > 0 && methods.contains(name.substring(dot + 1));
        });
    }

    // --- Validators ---

    private CompileResult validator(boolean captureDetails) {
        var unit = program.roots().getFirst();
        // 2. Validate subset
        SubsetScan.check(facts, unit);
        verifyBinderNamespace(unit);
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
        requireNoLibraries();
        // 10. Every static method's type
        parts.declareMethods(type);

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
            if (JavacDiscovery.isStatic(method) && !discovery.isEntrypoint(method)) {
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
            var transformed = ValidatorAssembly.strictEntrypoint(function, parameters, facts.registry());
            strictErrors.addAll(transformed.errors());
            boolean hasDatum = parameters.size() == 3 && (autoDispatch ? entry.tag() == 1 : purpose == ScriptPurpose.SPENDING);
            entries.add(new ValidatorAssembly.Entry(entry.tag(), parameters.size(), transformed.function(),
                    hasDatum ? parameters.get(0) : null, parameters.get(hasDatum ? 1 : 0),
                    hasDatum && parameters.get(0).type() instanceof PirType.OptionalType));
        }

        // 13-22. Everything from here on takes PIR and resolved types (F846).
        var validator = new ValidatorAssembly.Validator(purpose, autoDispatch, entries, List.copyOf(helpers.values()),
                statics, List.of(), parts.assemblyParams(), failureLocation(entrypoints.getFirst().method()), strictErrors);
        return ValidatorAssembly.validator(validator, registry(), context, parts.frontend(), captureDetails).result();
    }

    private List<JavacDiscovery.EntryPoint> entrypoints(ClassTree type, ScriptPurpose purpose) {
        if (purpose == ScriptPurpose.MULTI) return discovery.multiEntrypoints(type);
        var method = discovery.entrypoint(type);
        discovery.requireEntrypointParameters(method, purpose, type);
        return List.of(new JavacDiscovery.EntryPoint(method, "DEFAULT", -1));
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
        // 2. Validate subset
        SubsetScan.check(facts, unit);
        verifyBinderNamespace(unit);
        // 3. Find the target class (first non-interface class)
        var type = discovery.firstClass(unit);
        // 4. Find the target method
        var target = discovery.methods(type).stream()
                .filter(method -> JavacDiscovery.isStatic(method) && method.getName().contentEquals(methodName))
                .findFirst()
                .orElseThrow(() -> new CompilerException(
                        "No static method '" + methodName + "' found in " + type.getSimpleName()));
        // 5. Register types
        program.registerTypes();
        // 7, 7b. @Param and static fields
        var parts = new ClassParts(type);
        // 8. Compile libraries
        requireNoLibraries();
        // 9. Every static method's type
        parts.declareMethods(type);

        // The selected entry point is decoded from external Data: reject native Value anywhere in its boundary graph.
        var parameters = new ArrayList<ValidatorAssembly.Parameter>();
        for (var parameter : target.getParameters()) {
            String name = parameter.getName().toString();
            var at = facts.location(parameter);
            var parameterType = facts.pirType(facts.element(parameter).asType(), parameter);
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

        var statics = parts.lowerStatics();
        // Compile ALL static methods (including target) so cross-references work
        var overloads = new OnchainOverloads(type.getSimpleName().toString(), Set.of());
        var methods = new LinkedHashMap<String, ValidatorAssembly.Method>();
        for (var method : discovery.methods(type)) {
            if (JavacDiscovery.isStatic(method)) parts.lowerMethod(method, overloads, methods);
        }

        // 11-17. Everything from here on takes PIR and resolved types (F846).
        return ValidatorAssembly.method(new ValidatorAssembly.MethodProgram(parts.binder(target), parts.methodType(target),
                parameters, List.copyOf(methods.values()), statics, List.of(), parts.assemblyParams()),
                context, parts.frontend());
    }
}
