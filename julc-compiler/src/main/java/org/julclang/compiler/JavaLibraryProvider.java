package org.julclang.compiler;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import org.julclang.compiler.backend.*;
import org.julclang.compiler.pir.*;
import org.julclang.compiler.resolve.*;

import java.util.*;

/** Java implementation details stay behind LibraryProvider. No JVM library evaluation. */
public final class JavaLibraryProvider implements LibraryProvider {
    private final LibraryMethodRegistry registry;
    private final Map<String, LibraryExport> exports = new LinkedHashMap<>();
    private final Map<String, String> unsupported = new LinkedHashMap<>();
    private final Map<String, LibraryType> types;
    private final Map<String, PirType> definitions;

    public JavaLibraryProvider(List<String> sources, StdlibLookup lookup, CompilerOptions options) {
        var context = CompilationContext.resolve(options);
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
        var resolver = new TypeResolver();
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
        var descriptions = new LibraryTypeDescriptions(allSources, resolver);
        types = descriptions.types();
        definitions = Map.copyOf(resolver.namedDefinitions());
        var known = new LinkedHashSet<>(resolver.allRegisteredFqcns());
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
                    var compiled = registry.lookupMethod(symbol).orElseThrow();
                    if (!method.getTypeParameters().isEmpty()) {
                        unsupported.put(symbol, "Polymorphic Java methods require provider specialization, which is not yet supported");
                        continue;
                    }
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

    @Override public Map<String, LibraryType> types() { return types; }
    @Override public Map<String, PirType> namedDefinitions() { return definitions; }
    @Override public Map<String, String> unsupportedExports() { return Collections.unmodifiableMap(unsupported); }

    @Override
    public List<LibraryExport> describe(String module) {
        return exports.values().stream()
                .filter(e -> e.symbol().substring(0, e.symbol().lastIndexOf('.')).equals(module))
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
