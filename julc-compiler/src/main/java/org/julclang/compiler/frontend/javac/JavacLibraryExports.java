package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.backend.LibraryExport;
import org.julclang.compiler.backend.LibraryScheme;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.backend.TypeReferences;
import org.julclang.compiler.frontend.javac.lower.Lowering;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.compiler.resolve.LibraryMethodRegistry;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a {@code JavaLibraryProvider} exports on the javac frontend (ADR-068 D8, I5; ADR-059): its units are the roots, each
 * compiled as a library as a compilation compiles its pool, and each public static method of a class is an export, a scheme
 * when it is generic, or unsupported with the reference's reason. A scheme is specialized on request at the PIR types a
 * producer gives its type variables, which {@link JavacFacts#withTypeVariables} reads.
 * <p>
 * It holds its {@link JavacProgram}, so a specialization at {@code materialize()} is a query of it (F847); nothing it returns
 * holds a javac object.
 */
public final class JavacLibraryExports {

    /** A specialization: its body, in which a call of itself is a call of its binder, and its type. */
    public record Specialized(PirTerm body, PirType type) {
    }

    private static final String POLYMORPHIC = "Polymorphic Java method: describe it with schemes(module) and "
            + "instantiate it with LibraryRequest.Instantiate (ADR-059)";

    private final JavacProgram program;
    private final CompilationContext context;
    private final StdlibLookup lookup;
    private final JavacLibraryTypes.Described described;
    private final LibraryMethodRegistry registry;
    private final Map<String, LibraryExport> exports = new LinkedHashMap<>();
    /** Each generic export's scheme, or null where it cannot be described. */
    private final Map<String, LibraryScheme> templates = new LinkedHashMap<>();
    private final Map<String, String> unsupported = new LinkedHashMap<>();
    /** The templates the subset rejects, with the reason. */
    private final Map<String, String> rejectedTemplates = new LinkedHashMap<>();

    private JavacLibraryExports(JavacProgram program, CompilationContext context, StdlibLookup lookup) {
        this.program = program;
        this.context = context;
        this.lookup = lookup;
        this.registry = LibraryMethodRegistry.forCompilation(context);
        this.described = program.read(this::read);
    }

    /**
     * Reads, describes and lowers {@code sources}, given in the provider's order.
     *
     * @throws IllegalArgumentException for a class that is no {@code @OnchainLibrary}, or a type a producer cannot see
     * @throws org.julclang.compiler.CompilerException where javac or the lowering rejects the sources
     */
    public static JavacLibraryExports of(List<String> sources, StdlibLookup lookup, CompilationContext context) {
        // As the reference, before anything is read: a type two units declare has no one owner.
        var owners = new HashSet<String>();
        for (var names : JavacFrontend.shared().declaredNames(sources).orElse(List.of())) {
            for (String name : names) {
                if (!owners.add(name)) throw new IllegalArgumentException("Duplicate library type ownership: " + name);
            }
        }
        return new JavacLibraryExports(JavacProgram.analyzeLibraries(sources.stream().map(JavacFrontend.Unit::of).toList()),
                context, lookup);
    }

    private JavacLibraryTypes.Described read(JavacFacts facts) {
        program.roots().forEach(unit -> JavacLibraryTypes.requireLibraries(facts, unit));
        SubsetRules.checkOnJavacFrontend(facts, program.roots(), program.libraries(), JavacGate.Compiled.LIBRARY);
        var types = new JavacLibraryTypes(facts);
        var described = types.describe(program.declarations());
        var discovery = new JavacDiscovery(facts);
        var libraries = new JavacLibraries(program, facts, discovery, new Lowering(facts, context, lookup));
        libraries.scan();
        for (var template : libraries.templates()) {
            try {
                libraries.scan(template);
            } catch (CompilerException e) {
                // One template the subset rejects is one export unsupported, not a provider lost.
                String symbol = facts.identity((TypeElement) facts.element(facts.parent(template))) + "." + template.getName();
                rejectedTemplates.put(symbol, e.diagnostics().isEmpty() ? e.getMessage() : e.diagnostics().getFirst().message());
            }
        }
        for (var method : libraries.lower()) registry.register(method.className(), method.methodName(), method.type(), method.body());
        for (var unit : libraries.units()) {
            for (var type : libraries.classes(unit)) describeClass(facts, discovery, types, described, type);
        }
        return described;
    }

    private void describeClass(JavacFacts facts, JavacDiscovery discovery, JavacLibraryTypes types,
                               JavacLibraryTypes.Described described, ClassTree type) {
        String owner = facts.identity((TypeElement) facts.element(type));
        var statics = discovery.methods(type).stream().filter(discovery::isStatic).toList();
        String conflict = overloadConflict(types, owner, statics, facts);
        var exported = new HashSet<String>();
        for (var method : statics) {
            if (!facts.element(method).getModifiers().contains(Modifier.PUBLIC)) continue;
            String symbol = owner + "." + method.getName();
            if (conflict != null) {
                unsupported.put(symbol, conflict);
                continue;
            }
            // Identical overloads compile to one method: export it once.
            if (!exported.add(symbol)) continue;
            if (!method.getTypeParameters().isEmpty()) {
                describeTemplate(types, method, symbol, facts);
                continue;
            }
            var compiled = registry.lookupMethod(symbol).orElseThrow();
            try {
                var parameters = parameters(types, method, Set.of(), facts);
                var result = result(types, method, Set.of(), facts);
                PirType signature = representation(result, described);
                for (int i = parameters.size() - 1; i >= 0; i--) {
                    signature = new PirType.FunType(representation(parameters.get(i), described), signature);
                }
                if (!signature.equals(compiled.type())) {
                    throw new IllegalArgumentException("Source description disagrees with compiled representation for " + symbol);
                }
                exports.put(symbol, new LibraryExport(symbol, parameters.size(), compiled.type(), parameters, result));
            } catch (IllegalArgumentException e) {
                unsupported.put(symbol, e.getMessage());
            }
        }
    }

    private static PirType representation(LibraryType.Reference reference, JavacLibraryTypes.Described described) {
        return TypeReferences.representation(reference, described.types());
    }

    private static List<LibraryType.Reference> parameters(JavacLibraryTypes types, MethodTree method, Set<String> variables,
                                                          JavacFacts facts) {
        return method.getParameters().stream()
                .map(parameter -> types.reference(facts.element(parameter).asType(), parameter.getType(), variables)).toList();
    }

    private static LibraryType.Reference result(JavacLibraryTypes types, MethodTree method, Set<String> variables,
                                                JavacFacts facts) {
        return types.reference(((ExecutableElement) facts.element(method)).getReturnType(), method.getReturnType(), variables);
    }

    /**
     * Library methods are registered and called by {@code Class.method}, so every overload of a name must have one source
     * signature; otherwise every export of the class is unsupported, as the reference has it.
     */
    private static String overloadConflict(JavacLibraryTypes types, String owner, List<MethodTree> statics, JavacFacts facts) {
        var counts = new HashMap<String, Integer>();
        for (var method : statics) counts.merge(method.getName().toString(), 1, Integer::sum);
        var signatures = new LinkedHashMap<String, String>();
        for (var method : statics) {
            String name = method.getName().toString();
            if (counts.get(name) < 2) continue;
            String signature;
            try {
                signature = String.join(",", parameters(types, method, Set.of(), facts).stream()
                        .map(LibraryType.Reference::canonical).toList())
                        + "->" + result(types, method, Set.of(), facts).canonical()
                        + (method.getTypeParameters().isEmpty() ? "" : "<generic>");
            } catch (IllegalArgumentException e) {
                signature = "unsupported:" + name + method.getParameters();
            }
            var previous = signatures.putIfAbsent(name, signature);
            if (previous != null && !previous.equals(signature)) {
                return "Ambiguous overloaded library export " + owner + "." + name
                        + ": overloads with different signatures cannot be told apart on-chain; give them distinct names";
            }
        }
        return null;
    }

    /** A generic method as a scheme (ADR-059). A bounded type parameter has no supported substitution. */
    private void describeTemplate(JavacLibraryTypes types, MethodTree method, String symbol, JavacFacts facts) {
        if (rejectedTemplates.containsKey(symbol)) {
            unsupported.put(symbol, rejectedTemplates.get(symbol));
            templates.put(symbol, null);
            return;
        }
        var names = method.getTypeParameters().stream().map(parameter -> parameter.getName().toString()).toList();
        var bounded = method.getTypeParameters().stream().filter(parameter -> !parameter.getBounds().isEmpty()).findFirst();
        if (bounded.isPresent()) {
            unsupported.put(symbol, "Type parameter " + bounded.get() + " has a bound; generic library "
                    + "methods support only unbounded type parameters");
            templates.put(symbol, null);
            return;
        }
        try {
            var variables = Set.copyOf(names);
            var parameters = parameters(types, method, variables, facts);
            var result = result(types, method, variables, facts);
            var typeParameters = names.stream().map(name -> new LibraryScheme.TypeParameter(name,
                    Set.of(LibraryScheme.Constraint.DATA_ENCODABLE))).toList();
            String identity = symbol + "<" + String.join(",", names) + ">("
                    + String.join(",", parameters.stream().map(LibraryType.Reference::canonical).toList())
                    + ")" + result.canonical();
            templates.put(symbol, new LibraryScheme(identity, symbol, typeParameters, parameters, result, context.target()));
            unsupported.put(symbol, POLYMORPHIC);
        } catch (IllegalArgumentException e) {
            unsupported.put(symbol, e.getMessage());
            templates.put(symbol, null);
        }
    }

    public Map<String, LibraryType> types() {
        return described.types();
    }

    public Map<String, PirType> definitions() {
        return described.definitions();
    }

    /** The compiled library methods, by {@code Class.method}: what {@code materialize} gathers. */
    public LibraryMethodRegistry registry() {
        return registry;
    }

    public Map<String, LibraryExport> exports() {
        return exports;
    }

    public Map<String, String> unsupported() {
        return unsupported;
    }

    /** Each generic export's scheme, null where it has none; the map holds every generic export. */
    public Map<String, LibraryScheme> templates() {
        return templates;
    }

    /**
     * The generic method {@code symbol} lowered with each type variable bound to {@code environment}'s PIR type, by name,
     * as an ordinary method of its class.
     *
     * @throws org.julclang.compiler.CompilerException where it does not compile at those types
     */
    public Specialized specialize(String symbol, Map<String, PirType> environment) {
        return program.read(facts -> {
            var discovery = new JavacDiscovery(facts);
            var libraries = new JavacLibraries(program, facts, discovery, new Lowering(facts, context, lookup));
            for (var unit : libraries.units()) {
                for (var type : libraries.classes(unit)) {
                    String owner = facts.identity((TypeElement) facts.element(type));
                    for (var method : discovery.methods(type)) {
                        if (!(owner + "." + method.getName()).equals(symbol) || method.getTypeParameters().isEmpty()) continue;
                        return specialize(facts, method, environment);
                    }
                }
            }
            throw new IllegalArgumentException("no generic export " + symbol);
        });
    }

    private Specialized specialize(JavacFacts facts, MethodTree method, Map<String, PirType> environment) {
        var bound = new HashMap<Element, PirType>();
        for (var parameter : ((ExecutableElement) facts.element(method)).getTypeParameters()) {
            var type = environment.get(parameter.getSimpleName().toString());
            if (type != null) bound.put(parameter, type);
        }
        var view = facts.withTypeVariables(bound);
        var lowered = new JavacLibraries(program, view, new JavacDiscovery(view), new Lowering(view, context, lookup))
                .specialized(method);
        return new Specialized(lowered.body(), lowered.type());
    }
}
