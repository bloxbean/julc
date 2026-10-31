package org.julclang.compiler.frontend.javac;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;
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
    private final List<String> topLevelNames;
    private final String displayName;

    private JavacSourceFile(URI uri, String text, JavacUnits.UnitInfo info, String displayName) {
        super(uri, Kind.SOURCE);
        this.text = text;
        this.packageName = info.packageName();
        this.simpleName = info.primary();
        this.topLevelNames = info.topLevelNames();
        this.displayName = displayName;
    }

    /** A unit with no file: named {@code <Primary>.java}, as JuLC names string sources in its diagnostics. */
    static JavacSourceFile ofText(String text, JavacUnits.UnitInfo info) {
        String path = (info.packageName().isEmpty() ? "" : info.packageName().replace('.', '/') + "/")
                + info.primary() + ".java";
        return new JavacSourceFile(URI.create("string:///" + path), text, info, info.primary() + ".java");
    }

    /** A unit read from {@code file}. */
    static JavacSourceFile ofPath(Path file, String text, JavacUnits.UnitInfo info) {
        return new JavacSourceFile(file.toAbsolutePath().toUri(), text, info, file.toString());
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

    /** The primary type's simple name: the public top-level type, or the first one. */
    public String simpleName() {
        return simpleName;
    }

    public String binaryName() {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
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
