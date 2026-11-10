package org.julclang.vm.java.ndc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * One uplc-conformance vector, {@code protocol-version-<n>/v<n>/<group>/<name>/<name>.uplc}, labelled as the scenario's
 * expectations label it (Amaru's {@code crates/amaru-uplc/build.rs} defines the labels).
 *
 * @param id            the folders below the Plutus-version folder, non-alphanumerics as {@code _}, lower case, then
 *                      {@code _v<n>}
 * @param title         the vector's folder name, {@code -} and {@code _} as spaces, lower case
 * @param group         the folders between the Plutus-version folder and the vector's folder
 * @param language      1, 2 or 3
 * @param protocolMajor the major protocol version, from the top-level folder
 * @param program       the {@code .uplc} file
 */
record UplcVector(String id, String title, String group, int language, int protocolMajor, Path program) {

    String plutusVersion() {
        return "V" + language;
    }

    String protocolVersion() {
        return protocolMajor + ".0";
    }

    /** @return the {@code .uplc.expected} file */
    Path expected() {
        return program.resolveSibling(program.getFileName() + ".expected");
    }

    /** @return the {@code .flat} file (absent for a program with no Flat form) */
    Path flat() {
        String name = program.getFileName().toString();
        return program.resolveSibling(name.substring(0, name.length() - ".uplc".length()) + ".flat");
    }

    /** @return every vector below {@code root}, sorted by path */
    static List<UplcVector> list(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".uplc"))
                    .filter(p -> !root.relativize(p).startsWith("cost-models"))
                    .sorted()
                    .map(p -> of(root.relativize(p), p))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static UplcVector of(Path relative, Path program) {
        if (relative.getNameCount() < 4) {
            throw new IllegalArgumentException("not a vector path: " + relative);
        }
        String protocolFolder = relative.getName(0).toString();
        String languageFolder = relative.getName(1).toString();
        if (!protocolFolder.matches("protocol-version-\\d+") || !languageFolder.matches("v[123]")) {
            throw new IllegalArgumentException("not a vector path: " + relative);
        }
        int protocolMajor = Integer.parseInt(protocolFolder.substring("protocol-version-".length()));
        int language = languageFolder.charAt(1) - '0';
        Path vector = relative.subpath(2, relative.getNameCount()).getParent();
        String name = vector.toString().replace('\\', '/').replaceAll("[^A-Za-z0-9]", "_").toLowerCase(Locale.ROOT);
        Path group = vector.getParent() != null ? vector.getParent() : vector;
        String title = vector.getFileName().toString().replaceAll("[-_]", " ").toLowerCase(Locale.ROOT);
        return new UplcVector(name + "_v" + language, title, group.toString().replace('\\', '/'), language,
                protocolMajor, program);
    }
}
