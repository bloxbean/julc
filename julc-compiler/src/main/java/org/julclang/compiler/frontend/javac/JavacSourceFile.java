package org.julclang.compiler.frontend.javac;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

/**
 * One Java compilation unit given to javac, a root or a pool source (ADR-068 D8), held in memory. Its URI ends with
 * {@code <Primary>.java}, so javac's file-name rules (a public class in a file of its name, and source-path lookup
 * by binary name) see the name the unit's own public (or first) top-level type gives it. A unit read from a file
 * keeps that file's path.
 */
public final class JavacSourceFile extends SimpleJavaFileObject {

    private final String text;
    private final String packageName;
    private final String simpleName;
    private final String primary;
    private final List<String> topLevelNames;
    private final String displayName;

    private JavacSourceFile(URI uri, String text, String packageName, String simpleName, String primary,
                            List<String> topLevelNames, String displayName) {
        super(uri, Kind.SOURCE);
        this.text = text;
        this.packageName = packageName;
        this.simpleName = simpleName;
        this.primary = primary;
        this.topLevelNames = topLevelNames;
        this.displayName = displayName;
    }

    private JavacSourceFile(URI uri, String text, JavacUnits.UnitInfo info, String displayName) {
        this(uri, text, info.packageName(), info.primary(), info.primary(), info.topLevelNames(), displayName);
    }

    /** A unit with no file: named {@code <Primary>.java}, as JuLC names string sources in its diagnostics. */
    static JavacSourceFile ofText(String text, JavacUnits.UnitInfo info) {
        String path = "/" + (info.packageName().isEmpty() ? "" : info.packageName().replace('.', '/') + "/")
                + info.primary() + ".java";
        try {
            // The multi-argument constructor quotes what a URI cannot hold (an identifier-ignorable character).
            return new JavacSourceFile(new URI("string", null, path, null), text, info, info.primary() + ".java");
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A unit read from {@code file}. */
    static JavacSourceFile ofPath(Path file, String text, JavacUnits.UnitInfo info) {
        return new JavacSourceFile(file.toAbsolutePath().toUri(), text, info, file.toString());
    }

    /**
     * The same unit, listed on the source path under one of its secondary top-level types (D8: every top-level
     * declaration of a pool unit is found). It has the unit's URI, so whichever entry javac completes first, it reads
     * the unit once and enters all its types.
     */
    JavacSourceFile listedAs(String secondary) {
        return new JavacSourceFile(toUri(), text, packageName, secondary, primary, topLevelNames, displayName);
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return text;
    }

    public String text() {
        return text;
    }

    public String packageName() {
        return packageName;
    }

    /** The name this file object is listed under: the primary type's, or a secondary one's ({@link #listedAs}). */
    public String simpleName() {
        return simpleName;
    }

    public String binaryName() {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    /** The primary type's qualified name: the public top-level type, or the first one. */
    public String primaryName() {
        return packageName.isEmpty() ? primary : packageName + "." + primary;
    }

    /** The qualified names of every top-level type the unit declares, in source order. */
    public List<String> topLevelNames() {
        return topLevelNames.stream().map(n -> packageName.isEmpty() ? n : packageName + "." + n).toList();
    }

    /** The file name diagnostics report. */
    public String displayName() {
        return displayName;
    }
}
