package org.julclang.difftest;

import com.github.javaparser.ast.CompilationUnit;
import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JavaSourceParser;
import org.julclang.compiler.backend.LibraryType;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.compiler.frontend.javac.JavacProgram;
import org.julclang.compiler.frontend.javac.TolerantJavacTypes;
import org.julclang.compiler.pir.PirType;
import org.julclang.compiler.pir.StdlibLookup;
import org.julclang.compiler.resolve.LedgerSourceLoader;
import org.julclang.compiler.resolve.TypeRegistrar;
import org.julclang.compiler.resolve.TypeResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * ADR-068 M2 (I11): one case's registries from each frontend, compared. The reference is the JavaParser path as
 * {@code JulcCompiler} runs it: the ledger, its flat aliases, then the libraries and the root. The javac path sees the
 * same units twice: all of them as roots, which compares every type the reference registers; and as D8 has it, the
 * root with the libraries on the source path, which registers what javac read, and is compared with the reference over
 * exactly those units.
 */
final class Adr068Types {

    /** A frontend's reading of a case: its registry, or the rejection in its place. */
    record Reading(TypeResolver.Registry registry, String rejection) {

        boolean accepted() {
            return registry != null;
        }

        int declarations() {
            return registry.records().size() + registry.sums().size() + registry.newTypes().size();
        }
    }

    private Adr068Types() {
    }

    /** The JavaParser path's reading of {@code sources}, in {@code JulcCompiler}'s order. */
    static Reading reference(List<String> sources) {
        try {
            var units = new ArrayList<CompilationUnit>();
            for (String source : sources) units.add(JavaSourceParser.parseCompilationUnit(source));
            var resolver = new TypeResolver();
            new TypeRegistrar().registerAll(LedgerSourceLoader.loadLedgerSources(Adr068Types.class.getClassLoader()), resolver);
            resolver.addFlatVariantAliases("org.julclang.ledger");
            new TypeRegistrar().registerAll(units, resolver);
            return new Reading(resolver.registry(), null);
        } catch (RuntimeException e) {
            return new Reading(null, message(e));
        }
    }

    /** The javac path's reading: {@code null} program text means rejected by javac itself. */
    static Reading javac(List<String> roots, List<String> pool) {
        try {
            return new Reading(program(roots, pool).types().registry(), null);
        } catch (RuntimeException e) {
            return new Reading(null, message(e));
        }
    }

    static JavacProgram program(List<String> roots, List<String> pool) {
        return JavacProgram.analyze(roots.stream().map(JavacFrontend.Unit::of).toList(),
                pool.stream().map(JavacFrontend.Unit::of).toList());
    }

    /** The first difference between two registries, or null when they are equal. */
    static String difference(TypeResolver.Registry reference, TypeResolver.Registry javac) {
        String difference = difference("record", reference.records(), javac.records());
        if (difference == null) difference = difference("sum", reference.sums(), javac.sums());
        if (difference == null) difference = difference("variant", reference.variants(), javac.variants());
        if (difference == null) difference = difference("newtype", reference.newTypes(), javac.newTypes());
        if (difference == null && !reference.parameterized().equals(javac.parameterized())) {
            difference = "parameterized " + reference.parameterized() + " / " + javac.parameterized();
        }
        return difference;
    }

    private static <V> String difference(String kind, Map<String, V> reference, Map<String, V> javac) {
        var names = new TreeSet<>(reference.keySet());
        names.addAll(javac.keySet());
        for (String name : names) {
            V left = reference.get(name), right = javac.get(name);
            if (!Objects.equals(left, right)) {
                return kind + " " + name + ": " + brief(left) + " / " + brief(right);
            }
        }
        return null;
    }

    private static String brief(Object value) {
        String text = String.valueOf(value);
        return text.length() > 400 ? text.substring(0, 400) + "..." : text;
    }

    /** A rejection: its code, if any, and its message. */
    static String message(Throwable t) {
        // A hot JVM drops a NullPointerException's helpful message, so its text is not stable.
        if (t instanceof NullPointerException) return "NullPointerException";
        String message = t instanceof CompilerException e && !e.diagnostics().isEmpty()
                ? e.diagnostics().stream().filter(d -> d.isError()).findFirst()
                        .map(d -> (d.code() == null ? "" : d.code() + ": ") + d.message()).orElse(String.valueOf(t.getMessage()))
                : t instanceof CompilerException ? t.getMessage() : t.getClass().getSimpleName() + ": " + t.getMessage();
        return Adr068Ledger.stableMessage(message).replace('\n', ' ').replace('\t', ' ');
    }

    /** The provider's public type surface from each frontend (I5): equal, or the first difference. */
    static String providerDifference(List<String> sources, StdlibLookup lookup) {
        Map<String, LibraryType> referenceTypes, javacTypes;
        Map<String, PirType> referenceDefinitions, javacDefinitions;
        String referenceRejection = null, javacRejection = null;
        JavaLibraryProvider reference = null, javac = null;
        try {
            reference = new JavaLibraryProvider(sources, lookup, new CompilerOptions().setJavacGate(JavacGateMode.OFF));
        } catch (RuntimeException e) {
            referenceRejection = message(e);
        }
        try {
            javac = new JavaLibraryProvider(sources, lookup, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC));
        } catch (RuntimeException e) {
            javacRejection = message(e);
        }
        if (reference == null && javac == null) {
            return referenceRejection.equals(javacRejection) ? null : referenceRejection + " / " + javacRejection;
        }
        if (reference == null || javac == null) return "one rejects: " + referenceRejection + " / " + javacRejection;
        referenceTypes = new TreeMap<>(reference.types());
        javacTypes = new TreeMap<>(javac.types());
        referenceDefinitions = new TreeMap<>(reference.namedDefinitions());
        javacDefinitions = new TreeMap<>(javac.namedDefinitions());
        String difference = difference("library type", referenceTypes, javacTypes);
        return difference != null ? difference : difference("definition", referenceDefinitions, javacDefinitions);
    }

    /** The differences the evidence classifies, of one kind: key -> reason ({@code m2-accepted-differences.tsv}). */
    static Map<String, String> acceptedDifferences(Path repository, String kind) throws IOException {
        var accepted = new TreeMap<String, String>();
        for (String line : Files.readAllLines(repository.resolve("adr/evidence/068/m2-accepted-differences.tsv"))) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] cells = line.split("\t");
            if (cells[0].equals(kind)) accepted.put(cells[1], cells[2]);
        }
        return accepted;
    }

    /** The texts of a case's units: libraries, then the root, as the reference registers them. */
    static List<String> units(Adr068Corpus.Case c) {
        var units = new ArrayList<String>();
        c.libraries.forEach(library -> units.add(library.text()));
        if (c.main != null) units.add(c.main.text());
        return units;
    }

    /**
     * The registry javac's model gives a source it rejects, with the dialect's implicit imports added so that a
     * declaration resolves as the reference resolves it. Only for comparing declarations: see {@code TolerantJavacTypes}.
     */
    static Reading tolerant(List<String> sources) {
        try {
            return new Reading(TolerantJavacTypes.read(sources.stream().map(Adr068Types::withImplicitImports).toList()), null);
        } catch (RuntimeException e) {
            return new Reading(null, message(e));
        }
    }

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;");
    private static final String IMPLICIT = "import java.math.*; import java.util.*; import org.julclang.core.*;"
            + " import org.julclang.core.types.*; import org.julclang.ledger.*; import org.julclang.stdlib.*;"
            + " import org.julclang.stdlib.lib.*; import org.julclang.stdlib.annotation.*; ";

    /** The bundled libraries are left alone: their text is their identity (D3), and they import what they use. */
    private static String withImplicitImports(String source) {
        var matcher = PACKAGE.matcher(source);
        if (source.contains("package org.julclang.stdlib")) return source;
        return matcher.find() ? source.substring(0, matcher.end()) + IMPLICIT + source.substring(matcher.end())
                : IMPLICIT + source;
    }
}
