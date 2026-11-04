package org.julclang.compiler.frontend.javac;

import org.julclang.core.corpus.CorpusFiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The class files javac reads (ADR-068 D3, I10): a signature-only {@code java.base} served as the only system module,
 * and signature-only stubs of JuLC's on-chain API served on the class path. Both are build-time resources, embedded
 * uncompressed so that every host reads them, the Web Image included (it has no {@code Inflater}).
 * <p>
 * A blob is {@code int count}, then per entry a modified-UTF-8 path, an {@code int} length and the class file bytes,
 * sorted by path. Platform paths start with the module ({@code java.base/java/lang/Object.class}); stub paths are
 * class-path relative ({@code org/julclang/ledger/TxInfo.class}).
 * <p>
 * Parsed once per process ({@link #shared()}); instances are immutable and shared by every javac task.
 */
public final class JavacPlatform {

    /** The resource of the {@code java.base} blob. */
    public static final String PLATFORM_RESOURCE = "/org/julclang/compiler/frontend/javac/platform.bin";
    /** The resource of the API stubs. */
    public static final String STUBS_RESOURCE = "/org/julclang/compiler/frontend/javac/stubs.bin";

    /** The module of the platform blob. */
    static final String JAVA_BASE = "java.base";

    /** package -> simple file name (without {@code .class}) -> bytes. */
    private final Map<String, Map<String, byte[]>> system;
    private final Map<String, Map<String, byte[]>> classPath;
    private final String platformDigest;
    private final String stubsDigest;
    private final int platformCount;
    private final int stubsCount;
    private final Set<String> identities;

    private JavacPlatform(byte[] platformBlob, byte[] stubsBlob) {
        system = index(read(platformBlob), JAVA_BASE + "/");
        classPath = index(read(stubsBlob), "");
        platformDigest = sha256(platformBlob);
        stubsDigest = sha256(stubsBlob);
        platformCount = count(system);
        stubsCount = count(classPath);
        var names = new HashSet<String>();
        for (var index : List.of(system, classPath)) {
            index.forEach((pkg, files) -> files.keySet().forEach(
                    file -> names.add((pkg.isEmpty() ? "" : pkg + ".") + file.replace('$', '.'))));
        }
        identities = Set.copyOf(names);
    }

    private static volatile JavacPlatform shared;

    /**
     * The platform of this JuLC build, from its resources; parsed once per process. A missing resource is
     * {@link BrokenJulcBuildException}, every time it is asked for (nothing is cached on failure).
     */
    public static JavacPlatform shared() {
        JavacPlatform platform = shared;
        if (platform != null) return platform;
        synchronized (JavacPlatform.class) {
            if (shared == null) shared = new JavacPlatform(resource(PLATFORM_RESOURCE), resource(STUBS_RESOURCE));
            return shared;
        }
    }

    /** A platform from explicit blobs (tests and the build-time tool). */
    public static JavacPlatform of(byte[] platformBlob, byte[] stubsBlob) {
        return new JavacPlatform(platformBlob, stubsBlob);
    }

    /** The bytes of a shipped resource blob. */
    public static byte[] resource(String name) {
        try (InputStream in = JavacPlatform.class.getResourceAsStream(name)) {
            if (in == null) throw new BrokenJulcBuildException("missing resource " + name + " (the javac platform)");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The class files of {@code java.base}, by package and simple name. */
    Map<String, Map<String, byte[]>> system() {
        return system;
    }

    /** The API stubs, by package and simple name. */
    Map<String, Map<String, byte[]>> classPath() {
        return classPath;
    }

    /** SHA-256 of the platform blob, lower-case hex. */
    public String platformDigest() {
        return platformDigest;
    }

    /** SHA-256 of the stub blob, lower-case hex. */
    public String stubsDigest() {
        return stubsDigest;
    }

    public int platformClassCount() {
        return platformCount;
    }

    public int stubClassCount() {
        return stubsCount;
    }

    /** The packages the stubs define. */
    public Set<String> stubPackages() {
        return classPath.keySet();
    }

    /** Whether {@code identity} names a class of the platform or the stubs, at any depth. */
    public boolean declares(String identity) {
        return identities.contains(identity);
    }

    /** The qualified names of the stubs' top-level classes. */
    public Set<String> stubTopLevelNames() {
        var names = new TreeSet<String>();
        classPath.forEach((pkg, files) -> files.keySet().stream().filter(simple -> simple.indexOf('$') < 0)
                .forEach(simple -> names.add(pkg.isEmpty() ? simple : pkg + "." + simple)));
        return names;
    }

    /** Reads a blob: path -> bytes, in path order. */
    public static Map<String, byte[]> read(byte[] blob) {
        var entries = new TreeMap<String, byte[]>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String path = in.readUTF();
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                entries.put(path, data);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("malformed javac platform blob", e);
        }
        return entries;
    }

    /** Writes a blob from path -> bytes (written in path order). */
    public static byte[] write(Map<String, byte[]> entries) {
        var sorted = new TreeMap<>(entries);
        var out = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(out)) {
            o.writeInt(sorted.size());
            for (var e : sorted.entrySet()) {
                o.writeUTF(e.getKey());
                o.writeInt(e.getValue().length);
                o.write(e.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static Map<String, Map<String, byte[]>> index(Map<String, byte[]> entries, String prefix) {
        var result = new TreeMap<String, Map<String, byte[]>>();
        entries.forEach((path, bytes) -> {
            if (!path.startsWith(prefix) || !path.endsWith(".class")) {
                throw new IllegalArgumentException("unexpected javac platform entry " + path);
            }
            String rel = path.substring(prefix.length(), path.length() - ".class".length());
            int last = rel.lastIndexOf('/');
            String pkg = last < 0 ? "" : rel.substring(0, last).replace('/', '.');
            result.computeIfAbsent(pkg, p -> new TreeMap<>()).put(rel.substring(last + 1), bytes);
        });
        result.replaceAll((pkg, files) -> Collections.unmodifiableMap(files));
        return Collections.unmodifiableMap(result);
    }

    private static int count(Map<String, Map<String, byte[]>> index) {
        return index.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * The identity of a text: the SHA-256 digest of its injective stored form ({@link CorpusFiles#bytes}), the one
     * encoding JuLC uses for text identity, unpaired surrogates included (ADR-068 M1a F512, M0 F314).
     */
    public static String identity(String text) {
        return CorpusFiles.sha256(text);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
