package org.julclang.compiler;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import org.julclang.compiler.backend.*;
import org.julclang.compiler.pir.*;
import org.julclang.compiler.resolve.*;

import java.util.*;

/** Java implementation details stay behind LibraryProvider. No JVM library evaluation. */
public final class JavaLibraryProvider implements LibraryProvider {
    private final LibraryMethodRegistry registry;
    private final Map<String, LibraryExport> exports = new LinkedHashMap<>();

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
        var resolver = new TypeResolver();
        new TypeRegistrar()
                .registerAll(
                        LedgerSourceLoader.loadLedgerSources(
                                getClass().getClassLoader(),
                                source -> parser.parse(source).getResult().orElseThrow()),
                        resolver);
        new TypeRegistrar().registerAll(units, resolver);
        var known = new LinkedHashSet<>(resolver.allRegisteredFqcns());
        known.addAll(TypeResolver.ledgerHashFqcns());
        if (lookup != null) known.addAll(lookup.registeredClassNames());
        for (var cu : units)
            for (var cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = cls.getFullyQualifiedName().orElseThrow();
                known.add(fqcn);
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
        for (var cu : units)
            for (var cls : cu.findAll(ClassOrInterfaceDeclaration.class))
                for (var method : cls.getMethods()) {
                    if (!method.isPublic()
                            || !method.isStatic()
                            || !method.getTypeParameters().isEmpty()) continue;
                    var symbol =
                            cls.getFullyQualifiedName().orElseThrow()
                                    + "."
                                    + method.getNameAsString();
                    var compiled = registry.lookupMethod(symbol).orElseThrow();
                    exports.put(
                            symbol,
                            new LibraryExport(
                                    symbol, method.getParameters().size(), compiled.type()));
                }
    }

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
