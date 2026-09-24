package org.julclang.compiler;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import org.julclang.compiler.backend.*;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.*;
import org.julclang.compiler.resolve.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Java implementation details stay behind LibraryProvider. No JVM library evaluation. */
public final class JavaLibraryProvider implements LibraryProvider {
    /** A generic method, specialized on request; {@code scheme} is null when it cannot be described. */
    private record Template(CompilationUnit unit, ClassOrInterfaceDeclaration owner, MethodDeclaration method,
                            LibraryScheme scheme) {}

    /** One compiled specialization. */
    private record Specialized(String name, PirTerm body, PirType type) {}

    private final CompilationContext context;
    private final TypeResolver resolver;
    private final LibraryMethodRegistry registry;
    private final StdlibLookup lookup;
    private final Set<String> known;
    private final LibraryTypeDescriptions descriptions;
    private final String contentHash;
    private final Map<String, LibraryExport> exports = new LinkedHashMap<>();
    private final Map<String, Template> templates = new LinkedHashMap<>();
    private final Map<SpecializationKey, Specialized> specializations = new HashMap<>();
    private final Map<String, String> unsupported = new LinkedHashMap<>();
    private final Map<String, LibraryType> types;
    private final Map<String, PirType> definitions;

    public JavaLibraryProvider(List<String> sources, StdlibLookup lookup, CompilerOptions options) {
        context = CompilationContext.resolve(options);
        this.lookup = lookup;
        contentHash = contentHash(sources);
        var parser =
                new JavaParser(
                        new ParserConfiguration()
                                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        var units = new ArrayList<CompilationUnit>();
        for (var source : sources) {
            var parsed = parser.parse(source);
            if (!parsed.isSuccessful())
                throw new IllegalArgumentException(
                        "Invalid Java library source: " + parsed.getProblems());
            units.add(parsed.getResult().orElseThrow());
        }
        var owners = new HashSet<String>();
        for (var unit : units) for (TypeDeclaration<?> declaration : unit.findAll(TypeDeclaration.class)) {
            String identity = declaration.getFullyQualifiedName().orElseThrow();
            if (!owners.add(identity)) throw new IllegalArgumentException("Duplicate library type ownership: " + identity);
        }
        resolver = new TypeResolver();
        var ledgerSources = LedgerSourceLoader.loadLedgerSources(
                                getClass().getClassLoader(),
                                source -> parser.parse(source).getResult().orElseThrow());
        new TypeRegistrar().registerAll(ledgerSources, resolver);
        for (String identity : owners) if (resolver.allRegisteredFqcns().contains(identity)
                || TypeResolver.ledgerHashFqcns().contains(identity))
            throw new IllegalArgumentException("Library type conflicts with bundled ledger type: " + identity);
        new TypeRegistrar().registerAll(units, resolver);
        var allSources = new ArrayList<>(ledgerSources);
        allSources.addAll(units);
        descriptions = new LibraryTypeDescriptions(allSources, resolver);
        types = descriptions.types();
        definitions = Map.copyOf(resolver.namedDefinitions());
        known = new LinkedHashSet<>(resolver.allRegisteredFqcns());
        known.addAll(TypeResolver.ledgerHashFqcns());
        if (lookup != null) known.addAll(lookup.registeredClassNames());
        for (var cu : units)
            for (var cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = cls.getFullyQualifiedName().orElseThrow();
                known.add(fqcn);
                if (cls.isInterface()) continue;
                if (cls.getAnnotationByName("OnchainLibrary").isEmpty())
                    throw new IllegalArgumentException(
                            "Only @OnchainLibrary sources can be exported: " + fqcn);
                var names = new HashSet<String>();
                for (var method : cls.getMethods())
                    if (method.isStatic()) {
                        if (!names.add(method.getNameAsString()))
                            throw new IllegalArgumentException(
                                    "Ambiguous overloaded library export: "
                                            + fqcn
                                            + "."
                                            + method.getNameAsString());
                    }
            }
        registry = LibraryMethodRegistry.forCompilation(context);
        new LibraryCompiler(context).compile(units, resolver, registry, lookup, known);
        for (var cu : units) {
            descriptions.scope(cu);
            for (var cls : cu.findAll(ClassOrInterfaceDeclaration.class))
                for (var method : cls.getMethods()) {
                    if (!method.isPublic() || !method.isStatic() || cls.isInterface()) continue;
                    var symbol =
                            cls.getFullyQualifiedName().orElseThrow()
                                    + "."
                                    + method.getNameAsString();
                    if (!method.getTypeParameters().isEmpty()) {
                        describeTemplate(cu, cls, method, symbol);
                        continue;
                    }
                    var compiled = registry.lookupMethod(symbol).orElseThrow();
                    try {
                        var parameters = method.getParameters().stream().map(p -> descriptions.reference(p.getType())).toList();
                        var result = descriptions.reference(method.getType());
                        PirType signature = descriptions.representation(result);
                        for (int i = parameters.size() - 1; i >= 0; i--)
                            signature = new PirType.FunType(descriptions.representation(parameters.get(i)), signature);
                        if (!signature.equals(compiled.type()))
                            throw new IllegalArgumentException("Source description disagrees with compiled representation for " + symbol);
                        exports.put(
                                symbol,
                                new LibraryExport(
                                        symbol, method.getParameters().size(), compiled.type(),
                                        parameters, result));
                    } catch (IllegalArgumentException e) {
                        unsupported.put(symbol, e.getMessage());
                    }
                }
        }
    }

    /**
     * Describe a generic method as a scheme (ADR-059, #181). Bounded type parameters,
     * wildcards and raw containers have no supported substitution and stay unsupported.
     */
    private void describeTemplate(CompilationUnit cu, ClassOrInterfaceDeclaration cls, MethodDeclaration method,
                                  String symbol) {
        var names = method.getTypeParameters().stream().map(p -> p.getNameAsString()).toList();
        var bounded = method.getTypeParameters().stream().filter(p -> !p.getTypeBound().isEmpty()).findFirst();
        if (bounded.isPresent()) {
            unsupported.put(symbol, "Type parameter " + bounded.get() + " has a bound; generic library "
                    + "methods support only unbounded type parameters");
            templates.put(symbol, new Template(cu, cls, method, null));
            return;
        }
        try {
            descriptions.variables(Set.copyOf(names));
            var parameters = method.getParameters().stream().map(p -> descriptions.reference(p.getType())).toList();
            var result = descriptions.reference(method.getType());
            var typeParameters = names.stream().map(name -> new LibraryScheme.TypeParameter(name,
                    Set.of(LibraryScheme.Constraint.DATA_ENCODABLE))).toList();
            String identity = symbol + "<" + String.join(",", names) + ">("
                    + String.join(",", parameters.stream().map(LibraryType.Reference::canonical).toList())
                    + ")" + result.canonical();
            var scheme = new LibraryScheme(identity, symbol, typeParameters, parameters, result, context.target());
            templates.put(symbol, new Template(cu, cls, method, scheme));
            unsupported.put(symbol, "Polymorphic Java method: describe it with schemes(module) and "
                    + "instantiate it with LibraryRequest.Instantiate (ADR-059)");
        } catch (IllegalArgumentException e) {
            unsupported.put(symbol, e.getMessage());
            templates.put(symbol, new Template(cu, cls, method, null));
        } finally {
            descriptions.variables(Set.of());
        }
    }

    @Override public Map<String, LibraryType> types() { return types; }
    @Override public int revision() { return BackendContract.REVISION_2; }
    @Override public Map<String, PirType> namedDefinitions() { return definitions; }
    @Override public Map<String, String> unsupportedExports() { return Collections.unmodifiableMap(unsupported); }

    @Override
    public List<LibraryExport> describe(String module) {
        return exports.values().stream()
                .filter(e -> e.symbol().substring(0, e.symbol().lastIndexOf('.')).equals(module))
                .toList();
    }

    @Override
    public List<LibraryScheme> schemes(String module) {
        return templates.values().stream()
                .map(Template::scheme)
                .filter(Objects::nonNull)
                .filter(s -> s.symbol().substring(0, s.symbol().lastIndexOf('.')).equals(module))
                .toList();
    }

    @Override
    public PirTerm materialize(String symbol) {
        if (!exports.containsKey(symbol))
            throw new IllegalArgumentException("Unknown public on-chain export: " + symbol);
        var root = registry.lookupMethod(symbol).orElseThrow();
        var reachable = new LinkedHashMap<String, PirTerm>();
        gather(symbol, reachable);
        PirTerm term = PirLinker.link(reachable, new PirTerm.Var(symbol, root.type()));
        PirClosure.check(term);
        return term;
    }

    /**
     * Materialize the requested exports and their private dependencies as one group, so a
     * dependency shared by several exports is linked once (ADR-059).
     */
    @Override
    public LibraryImports materialize(List<LibraryRequest> requests) {
        var reachable = new LinkedHashMap<String, PirTerm>();
        var bindings = new LinkedHashMap<LibraryRequest, LibraryImports.Binding>();
        for (var request : requests) {
            switch (request) {
                case LibraryRequest.Export export -> {
                    var described = exports.get(export.symbol());
                    if (described == null)
                        throw new BackendException(DiagnosticCodes.BACKEND_UNSUPPORTED_REQUEST,
                                export.symbol(), export.symbol(),
                                unsupported.getOrDefault(export.symbol(), "no public on-chain export with this symbol"));
                    gather(export.symbol(), reachable);
                    bindings.put(request, new LibraryImports.Binding(export.symbol(), described.type()));
                }
                case LibraryRequest.Instantiate instantiate -> {
                    var specialized = specialize(instantiate);
                    for (String dependency : PirClosure.freeVariables(specialized.body()))
                        if (!dependency.equals(specialized.name())) gather(dependency, reachable);
                    reachable.putIfAbsent(specialized.name(), specialized.body());
                    bindings.put(request, new LibraryImports.Binding(specialized.name(), specialized.type()));
                }
                case LibraryRequest.Operation operation -> bind(reachable, bindings, request,
                        new TypeOperations(types, definitions).operation(operation));
                case LibraryRequest.Codec codec -> bind(reachable, bindings, request,
                        new TypeOperations(types, definitions).codec(codec));
            }
        }
        return new LibraryImports(JavaLibraryProvider.class.getName(), revision(), context.target(),
                reachable, bindings, definitions, types);
    }

    /**
     * Compile the actual generic method with its type parameters bound to the requested
     * concrete types (ADR-059, #181). Nothing is erased to Data: the specialization is
     * ordinary Java-source compilation at those types. Results are memoized by
     * {@link SpecializationKey} within this provider instance.
     */
    private Specialized specialize(LibraryRequest.Instantiate request) {
        var template = templates.get(request.symbol());
        if (template == null)
            throw unsupported(request, unsupported.getOrDefault(request.symbol(), "no generic export with this symbol"));
        var scheme = template.scheme();
        if (scheme == null) throw unsupported(request, unsupported.get(request.symbol()));
        if (request.typeArguments().size() != scheme.typeParameters().size())
            throw unsupported(request, scheme.identity() + " takes " + scheme.typeParameters().size()
                    + " type arguments, not " + request.typeArguments().size());
        Map<String, LibraryType> visible;
        try {
            visible = TypeReferences.withProducerTypes(types, request);
        } catch (IllegalArgumentException e) {
            throw unsupported(request, e.getMessage());
        }
        var substitution = new LinkedHashMap<String, LibraryType.Reference>();
        var environment = new LinkedHashMap<String, PirType>();
        for (int i = 0; i < request.typeArguments().size(); i++) {
            var argument = request.typeArguments().get(i);
            var parameter = scheme.typeParameters().get(i);
            if (argument.isGeneric())
                throw unsupported(request, "type arguments must be concrete, not " + argument.canonical());
            PirType representation;
            try {
                representation = TypeReferences.representation(argument, visible);
            } catch (IllegalArgumentException e) {
                throw unsupported(request, "type argument " + argument.canonical() + " is not a supported type: "
                        + e.getMessage());
            }
            if (!TypeReferences.dataEncodable(representation))
                throw unsupported(request, "type argument " + argument.canonical() + " for " + parameter.name()
                        + " does not satisfy " + LibraryScheme.Constraint.DATA_ENCODABLE);
            substitution.put(parameter.name(), argument);
            environment.put(parameter.name(), representation);
        }
        var key = new SpecializationKey(contentHash, scheme.identity(), request.typeArguments(),
                LibraryType.REPRESENTATION_REVISION, BackendContract.REVISION, context.target().profileId(),
                TypeReferences.producerFingerprints(request));
        var cached = specializations.get(key);
        if (cached != null) return cached;
        String name = request.symbol() + "#" + key.digest();
        var previous = resolver.bindTypeVariables(environment);
        LibraryCompiler.Specialization compiled;
        try {
            compiled = new LibraryCompiler(context).compileSpecialization(template.unit(), template.owner(),
                    template.method(), resolver, registry, lookup, known);
        } catch (CompilerException e) {
            throw unsupported(request, "specialization does not compile: " + e.getMessage());
        } finally {
            resolver.restoreTypeVariables(previous);
        }
        if (!compiled.errors().isEmpty())
            throw unsupported(request, "specialization does not compile: " + compiled.errors().stream()
                    .map(d -> d.message()).toList());
        PirType signature = TypeReferences.representation(scheme.result().substitute(substitution), visible);
        for (int i = scheme.parameters().size() - 1; i >= 0; i--)
            signature = new PirType.FunType(
                    TypeReferences.representation(scheme.parameters().get(i).substitute(substitution), visible),
                    signature);
        if (!signature.equals(compiled.type()))
            throw unsupported(request, "the specialized source signature disagrees with its compiled representation");
        // Recursive calls were compiled against Class.method at the specialized type.
        var body = PirSubstitution.substitute(compiled.body(), request.symbol(),
                new PirTerm.Var(name, compiled.type()));
        var specialized = new Specialized(name, body, compiled.type());
        specializations.put(key, specialized);
        return specialized;
    }

    /** SHA-256 of the compiler version and every source, length-prefixed in the supplied order. */
    private static String contentHash(List<String> sources) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : prepend(CompilerVersion.VERSION, sources)) {
                var bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> prepend(String first, List<String> rest) {
        var all = new ArrayList<String>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private static BackendException unsupported(LibraryRequest request, String detail) {
        return new BackendException(DiagnosticCodes.BACKEND_UNSUPPORTED_REQUEST, request.describe(),
                request.describe(), detail);
    }

    private static void bind(Map<String, PirTerm> definitions,
                             Map<LibraryRequest, LibraryImports.Binding> bindings,
                             LibraryRequest request, TypeOperations.Materialized materialized) {
        definitions.putIfAbsent(materialized.name(), materialized.term());
        bindings.put(request, new LibraryImports.Binding(materialized.name(), materialized.type()));
    }

    private void gather(String symbol, Map<String, PirTerm> found) {
        if (found.containsKey(symbol)) return;
        var method =
                registry.lookupMethod(symbol)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Missing library dependency: " + symbol));
        found.put(symbol, method.body());
        for (String dependency : PirClosure.freeVariables(method.body())) gather(dependency, found);
    }
}
