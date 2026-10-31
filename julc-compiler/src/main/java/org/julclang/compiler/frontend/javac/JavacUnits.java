package org.julclang.compiler.frontend.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The package and top-level type names of a compilation unit, read by javac's parser (no attribution), so that a
 * pool source is found on the source path by the binary name javac looks up. Cached by the text's digest: the same
 * library sources are offered to every compilation of a process.
 */
final class JavacUnits {

    /** The package, the top-level type names in source order, and the primary one (public, else first). */
    record UnitInfo(String packageName, List<String> topLevelNames, String primary) {
    }

    private static final int CACHE_LIMIT = 8192;
    private static final Map<String, UnitInfo> CACHE = new ConcurrentHashMap<>();

    private JavacUnits() {
    }

    /** The names of each text, in order. */
    static List<UnitInfo> infos(JavaCompiler compiler, JavacPlatform platform, List<String> texts) {
        var digests = texts.stream().map(JavacPlatform::identity).toList();
        var known = new LinkedHashMap<String, UnitInfo>();
        var missing = new LinkedHashMap<String, String>();
        for (int i = 0; i < texts.size(); i++) {
            UnitInfo info = CACHE.get(digests.get(i));
            if (info != null) known.put(digests.get(i), info);
            else missing.put(digests.get(i), texts.get(i));
        }
        if (!missing.isEmpty()) {
            var parsed = parse(compiler, platform, List.copyOf(missing.keySet()), List.copyOf(missing.values()));
            if (CACHE.size() > CACHE_LIMIT) CACHE.clear();
            int i = 0;
            for (String digest : missing.keySet()) {
                known.put(digest, parsed.get(i));
                CACHE.put(digest, parsed.get(i++));
            }
        }
        return digests.stream().map(known::get).toList();
    }

    private static List<UnitInfo> parse(JavaCompiler compiler, JavacPlatform platform, List<String> digests,
                                        List<String> texts) {
        var files = new ArrayList<JavaFileObject>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            files.add(new SimpleJavaFileObject(URI.create("string:///Unit" + i + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return text;
                }
            });
        }
        var fileManager = new EmbeddedFileManager(platform, List.of(), null);
        var task = (JavacTask) compiler.getTask(null, fileManager, diagnostic -> { }, JavacFrontend.OPTIONS, null,
                files);
        var result = new ArrayList<UnitInfo>();
        try {
            int i = 0;
            for (CompilationUnitTree unit : task.parse()) result.add(info(unit, digests.get(i++)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    /**
     * The unit's names, keeping only well-formed ones: javac's parser recovers from a malformed declaration with
     * names such as {@code <error>}, which are not Java names and cannot be part of a URI. A unit without a usable
     * primary name gets a synthetic one from its digest; javac then reports its syntax errors (D5).
     */
    private static UnitInfo info(CompilationUnitTree unit, String digest) {
        String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        if (!SourceVersion.isName(pkg)) pkg = "";
        var names = new ArrayList<String>();
        String primary = null;
        for (Tree declaration : unit.getTypeDecls()) {
            if (!(declaration instanceof ClassTree type)) continue;
            String name = type.getSimpleName().toString();
            if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name) || names.contains(name)) continue;
            names.add(name);
            if (primary == null && type.getModifiers().getFlags().contains(Modifier.PUBLIC)) primary = name;
        }
        if (primary == null) primary = names.isEmpty() ? "Unit_" + digest.substring(0, 12) : names.getFirst();
        return new UnitInfo(pkg, List.copyOf(names), primary);
    }
}
