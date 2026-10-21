package org.julclang.stdlib;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.LedgerTypeProvider;
import org.julclang.compiler.backend.*;
import org.julclang.compiler.backend.LibraryType.Reference;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.pir.PirHelpers;
import org.julclang.compiler.pir.PirHofBuilders;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;

import java.util.*;
import java.util.function.Function;

/**
 * Programmatic PIR library exports for language-neutral producers (ADR-059, #182).
 *
 * <p>The list higher-order functions of {@code ListsLib} have no Java source; the Java frontend
 * builds them with {@link PirHofBuilders}. This provider describes them as generic schemes and
 * materializes each instantiation as a closed lambda over reserved {@code $julc$arg$i}
 * parameters: arguments are evaluated once, strictly and left to right, and cannot be captured
 * by builder binders. Julc-generated adapters decode each Data list element for the typed
 * function argument and encode results, so a producer passes ordinary typed functions.
 *
 * <p>This deliberately differs from Java inlining, where the function argument is re-evaluated
 * for each element and never evaluated for an empty list. Nominal type arguments are resolved
 * against the bundled ledger types and the descriptions supplied at construction.
 */
public final class StdlibLibraryProvider implements LibraryProvider {
    /** The module that owns the list higher-order functions. */
    public static final String LISTS = "org.julclang.stdlib.lib.ListsLib";

    private static final String CONTENT = "julc-stdlib programmatic list HOFs v1";
    private static final Reference A = Reference.variable("a");
    private static final Reference B = Reference.variable("b");
    private static final Reference BOOL = new Reference("Bool", List.of());

    /** One programmatic export: its scheme and the builder that specializes it. */
    private record Hof(LibraryScheme scheme, Function<List<PirType>, Built> builder) {}

    /** A specialized closed term and its type. */
    private record Built(PirTerm term, PirType type) {}

    private final CompilationContext context;
    private final Map<String, LibraryType> types = new LinkedHashMap<>();
    private final Map<String, Hof> catalog = new LinkedHashMap<>();
    private final Map<SpecializationKey, LibraryImports.Binding> bindings = new HashMap<>();
    private final Map<String, PirTerm> terms = new HashMap<>();

    /** A provider whose nominal type arguments may be bundled ledger types. */
    public StdlibLibraryProvider(CompilerOptions options) {
        this(options, Map.of());
    }

    /**
     * @param options compiler options selecting the target
     * @param types   additional nominal type descriptions that may appear as type arguments,
     *                typically the {@code types()} of the providers composed with this one
     */
    public StdlibLibraryProvider(CompilerOptions options, Map<String, LibraryType> types) {
        context = CompilationContext.resolve(options);
        this.types.putAll(new LedgerTypeProvider().types());
        this.types.putAll(types);
        define("map", List.of("a", "b"), List.of(list(A), function(A, B)), list(B), t -> {
            var element = t.get(0);
            var result = t.get(1);
            return hof(List.of(new PirType.ListType(element), new PirType.FunType(element, result)),
                    new PirType.ListType(result), args -> PirHofBuilders.map(args.get(0),
                            adapter(args.get(1), element, value -> PirHelpers.wrapEncode(value, result))));
        });
        define("filter", List.of("a"), List.of(list(A), function(A, BOOL)), list(A), t -> {
            var element = t.getFirst();
            return hof(List.of(new PirType.ListType(element), new PirType.FunType(element, new PirType.BoolType())),
                    new PirType.ListType(element), args -> PirHofBuilders.filter(args.get(0),
                            adapter(args.get(1), element, Function.identity())));
        });
        for (var name : List.of("any", "all"))
            define(name, List.of("a"), List.of(list(A), function(A, BOOL)), BOOL, t -> {
                var element = t.getFirst();
                return hof(List.of(new PirType.ListType(element), new PirType.FunType(element, new PirType.BoolType())),
                        new PirType.BoolType(), args -> {
                            var predicate = adapter(args.get(1), element, Function.identity());
                            return name.equals("any") ? PirHofBuilders.any(args.get(0), predicate)
                                    : PirHofBuilders.all(args.get(0), predicate);
                        });
            });
        define("find", List.of("a"), List.of(list(A), function(A, BOOL)), new Reference("JulcOptional", List.of(A)),
                t -> {
                    var element = t.getFirst();
                    return hof(List.of(new PirType.ListType(element),
                                    new PirType.FunType(element, new PirType.BoolType())),
                            new PirType.OptionalType(element), args -> PirHofBuilders.find(args.get(0),
                                    adapter(args.get(1), element, Function.identity())));
                });
        define("foldl", List.of("a", "b"), List.of(function(B, function(A, B)), B, list(A)), B, t -> {
            var element = t.get(0);
            var accumulator = t.get(1);
            var step = new PirType.FunType(accumulator, new PirType.FunType(element, accumulator));
            return hof(List.of(step, accumulator, new PirType.ListType(element)), accumulator, args -> {
                // \acc -> \item -> f acc (decode item)
                var acc = new PirTerm.Var("$julc$acc", accumulator);
                var item = new PirTerm.Var("$julc$item", new PirType.DataType());
                var fold = new PirTerm.Lam("$julc$acc", accumulator, new PirTerm.Lam("$julc$item",
                        new PirType.DataType(), new PirTerm.App(new PirTerm.App(args.get(0), acc),
                                PirHelpers.wrapDecode(item, element))));
                return PirHofBuilders.foldl(fold, args.get(1), args.get(2));
            });
        });
    }

    private void define(String name, List<String> parameters, List<Reference> sourceParameters,
                        Reference result, Function<List<PirType>, Built> builder) {
        String symbol = LISTS + "." + name;
        String identity = symbol + "<" + String.join(",", parameters) + ">("
                + String.join(",", sourceParameters.stream().map(Reference::canonical).toList()) + ")"
                + result.canonical();
        var typeParameters = parameters.stream().map(p -> new LibraryScheme.TypeParameter(p,
                Set.of(LibraryScheme.Constraint.DATA_ENCODABLE))).toList();
        catalog.put(symbol, new Hof(new LibraryScheme(identity, symbol, typeParameters, sourceParameters, result,
                context.target()), builder));
    }

    /**
     * A closed lambda over {@code $julc$arg$i}; the builder only sees parameter variables, so no
     * producer term is ever placed under a builder binder.
     */
    private static Built hof(List<PirType> parameters, PirType result,
                             Function<List<PirTerm>, PirTerm> body) {
        var arguments = new ArrayList<PirTerm>();
        for (int i = 0; i < parameters.size(); i++) arguments.add(new PirTerm.Var("$julc$arg$" + i, parameters.get(i)));
        PirTerm term = body.apply(arguments);
        PirType type = result;
        for (int i = parameters.size() - 1; i >= 0; i--) {
            term = new PirTerm.Lam("$julc$arg$" + i, parameters.get(i), term);
            type = new PirType.FunType(parameters.get(i), type);
        }
        return new Built(term, type);
    }

    /** {@code \e:Data -> finish (f (decode e))}: the builders pass raw Data list elements. */
    private static PirTerm adapter(PirTerm function, PirType element, Function<PirTerm, PirTerm> finish) {
        var raw = new PirTerm.Var("$julc$element", new PirType.DataType());
        return new PirTerm.Lam("$julc$element", new PirType.DataType(),
                finish.apply(new PirTerm.App(function, PirHelpers.wrapDecode(raw, element))));
    }

    private static Reference list(Reference element) {
        return new Reference("List", List.of(element));
    }

    private static Reference function(Reference parameter, Reference result) {
        return new Reference("Function", List.of(parameter, result));
    }

    @Override
    public List<LibraryExport> describe(String module) {
        return List.of();
    }

    @Override
    public List<LibraryScheme> schemes(String module) {
        return catalog.values().stream().map(Hof::scheme)
                .filter(s -> s.symbol().startsWith(module + ".") && s.symbol().lastIndexOf('.') == module.length())
                .toList();
    }

    @Override
    public PirTerm materialize(String symbol) {
        throw new IllegalArgumentException(symbol + " is generic; instantiate it with LibraryRequest.Instantiate");
    }

    @Override
    public int revision() {
        return BackendContract.REVISION_2;
    }

    @Override
    public LibraryImports materialize(List<LibraryRequest> requests) {
        var definitions = new LinkedHashMap<String, PirTerm>();
        var group = new LinkedHashMap<LibraryRequest, LibraryImports.Binding>();
        for (var request : requests) {
            if (!(request instanceof LibraryRequest.Instantiate instantiate))
                throw unsupported(request, "programmatic list exports are generic; use LibraryRequest.Instantiate");
            var binding = instantiate(instantiate);
            definitions.putIfAbsent(binding.name(), terms.get(binding.name()));
            group.put(request, binding);
        }
        return new LibraryImports(StdlibLibraryProvider.class.getName(), revision(), context.target(), definitions,
                group, Map.of(), Map.of());
    }

    private LibraryImports.Binding instantiate(LibraryRequest.Instantiate request) {
        var hof = catalog.get(request.symbol());
        if (hof == null) throw unsupported(request, "no programmatic export with this symbol");
        var scheme = hof.scheme();
        if (request.typeArguments().size() != scheme.typeParameters().size())
            throw unsupported(request, scheme.identity() + " takes " + scheme.typeParameters().size()
                    + " type arguments, not " + request.typeArguments().size());
        Map<String, LibraryType> visible;
        try {
            visible = TypeReferences.withProducerTypes(types, request);
        } catch (IllegalArgumentException e) {
            throw unsupported(request, e.getMessage());
        }
        var representations = new ArrayList<PirType>();
        for (var argument : request.typeArguments()) {
            if (argument.isGeneric())
                throw unsupported(request, "type arguments must be concrete, not " + argument.canonical());
            PirType representation;
            try {
                representation = TypeReferences.representation(argument, visible);
            } catch (IllegalArgumentException e) {
                throw unsupported(request, "type argument " + argument.canonical() + ": " + e.getMessage());
            }
            if (!TypeReferences.dataEncodable(representation))
                throw unsupported(request, "type argument " + argument.canonical() + " does not satisfy "
                        + LibraryScheme.Constraint.DATA_ENCODABLE);
            representations.add(representation);
        }
        var key = new SpecializationKey(CONTENT, scheme.identity(), request.typeArguments(),
                LibraryType.REPRESENTATION_REVISION, BackendContract.REVISION, context.target().profileId(),
                TypeReferences.producerFingerprints(request));
        var cached = bindings.get(key);
        if (cached != null) return cached;
        var built = hof.builder().apply(representations);
        var binding = new LibraryImports.Binding(request.symbol() + "#" + key.digest(), built.type());
        terms.put(binding.name(), built.term());
        bindings.put(key, binding);
        return binding;
    }

    private static BackendException unsupported(LibraryRequest request, String detail) {
        return new BackendException(DiagnosticCodes.BACKEND_UNSUPPORTED_REQUEST, request.describe(),
                request.describe(), detail);
    }
}
