package org.julclang.compiler;


import org.julclang.compiler.backend.*;
import org.julclang.compiler.corpus.CorpusRecorder;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacRuntime;
import org.julclang.compiler.frontend.javac.JavacLibraryExports;
import org.julclang.compiler.frontend.javac.JavacLibraryTypes;
import org.julclang.compiler.pir.*;
import org.julclang.compiler.resolve.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;

/** Java implementation details stay behind LibraryProvider. No JVM library evaluation. */
public final class JavaLibraryProvider implements LibraryProvider {
    /**
     * A generic method, specialized on request by {@code compile}, which takes its type variables' PIR types by name;
     * {@code scheme} is null when it cannot be described.
     */
    /** What a template gives for its type arguments: its term and type, or the rejections of it. */
    private record Specialization(PirTerm body, PirType type, List<CompilerDiagnostic> errors) {}

    /** A generic method, specialized on request by {@code compile}, which takes its type variables' bindings. */
    private record Template(LibraryScheme scheme, Function<Map<String, PirType>, Specialization> compile) {}

    /** One compiled specialization. */
    private record Specialized(String name, PirTerm body, PirType type) {}

    private final CompilationContext context;
    private final LibraryMethodRegistry registry;
    private final StdlibLookup lookup;
    private final String contentHash;
    private final Map<String, LibraryExport> exports = new LinkedHashMap<>();
    private final Map<String, Template> templates = new LinkedHashMap<>();
    private final Map<SpecializationKey, Specialized> specializations = new HashMap<>();
    private final Map<String, String> unsupported = new LinkedHashMap<>();
    private final Map<String, LibraryType> types;
    private final Map<String, PirType> definitions;
    /**
     * This provider's case in the ADR-068 corpus, recorded at construction from the options it resolved (never from
     * the caller's {@code CompilerOptions}, which may change afterwards, F313); null unless the recorder is active.
     */
    private final String corpusCase;

    public JavaLibraryProvider(List<String> sources, StdlibLookup lookup, CompilerOptions options) {
        context = CompilationContext.resolve(options);
        corpusCase = CorpusRecorder.ACTIVE == null ? null : CorpusRecorder.ACTIVE.record("JavaLibraryProvider", null,
                null, sources.stream().map(text -> new CorpusRecorder.Source(text, null)).toList(), lookup,
                CorpusRecorder.Options.of(context));
        this.lookup = lookup;
        contentHash = contentHash(sources);
        // ADR-068 D7, D8: javac reads the units and the stubs.
        if (sources.isEmpty()) {
            var described = JavacRuntime.call(() -> JavacLibraryTypes.describe(sources));
            types = described.types();
            definitions = described.definitions();
            registry = LibraryMethodRegistry.forCompilation(context);
            return;
        }
        var javac = JavacRuntime.call(() -> JavacLibraryExports.of(sources, lookup, context));
        types = javac.types();
        definitions = Map.copyOf(javac.definitions());
        registry = javac.registry();
        exports.putAll(javac.exports());
        unsupported.putAll(javac.unsupported());
        javac.templates().forEach((symbol, scheme) -> templates.put(symbol, new Template(scheme, environment -> {
            try {
                var specialized = javac.specialize(symbol, environment);
                return new Specialization(specialized.body(), specialized.type(), List.of());
            } catch (CompilerException e) {
                // The reference's generator collects what it rejects, and the request names those messages.
                if (e.diagnostics().isEmpty()) throw e;
                return new Specialization(null, null, e.diagnostics());
            }
        })));
    }

    @Override public Map<String, LibraryType> types() { return types; }
    @Override public int revision() { return BackendContract.REVISION_2; }
    @Override public Map<String, PirType> namedDefinitions() { return definitions; }
    @Override public Map<String, String> unsupportedExports() {
        return Collections.unmodifiableMap(unsupported);
    }

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
                    if (corpusCase != null) CorpusRecorder.ACTIVE.recordInstantiate(corpusCase, lookup, instantiate);
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
        String name = "#" + request.symbol() + "#" + key.digest();
        Specialization compiled;
        try {
            compiled = template.compile().apply(environment);
        } catch (CompilerException e) {
            throw unsupported(request, "specialization does not compile: " + e.getMessage());
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
