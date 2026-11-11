package org.julclang.vm.java.ndc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The uplc-conformance corpus, vendored byte for byte under the test resources {@code ndc/uplc-conformance} (its
 * README names the upstream revision and how to refresh it): the scenario's vectors and cost models ({@code corpus/})
 * and the expectations the site scores a report against ({@code expectations.json}).
 */
final class UplcConformanceCorpus {

    /** The test-classpath folder. */
    static final String RESOURCE = "/ndc/uplc-conformance";

    /** The SHA-256 of the upstream tarball {@code corpus/} was unpacked from. */
    static final String TARBALL_SHA256 = "8bd4ad4f9f4c8c6b8ffa1f06ed1db0b43043afbba77ef6a14bcfb5a52345ed3f";

    /**
     * The SHA-256 of the {@code shasum -a 256} listing ({@code <hex>  <path>} lines) of every file under
     * {@code corpus/}, sorted by path: the README gives the shell command that computes the same.
     */
    static final String TREE_SHA256 = "6e46fc745502b36b3928eb8d38d9ac1d82800eba57bafa8fcb089faaec588a7f";

    /** The SHA-256 of {@code expectations.json}. */
    static final String EXPECTATIONS_SHA256 = "1c93d210b4bf155b1d53fecbb98c380a35b647ea3738efffa674e3b348001357";

    private UplcConformanceCorpus() {
    }

    /** @return the vendored folder on the test classpath */
    static Path root() {
        URL url = UplcConformanceCorpus.class.getResource(RESOURCE);
        if (url == null) {
            throw new IllegalStateException("the vendored uplc-conformance corpus " + RESOURCE
                    + " is not on the test classpath");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the vectors and cost models */
    static Path corpus() {
        return root().resolve("corpus");
    }

    /** @return the expectations file */
    static Path expectations() {
        return root().resolve("expectations.json");
    }

    /** @return the tree digest of {@code dir}, computed as {@link #TREE_SHA256} is */
    static String treeSha256(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            List<String> paths = files.filter(Files::isRegularFile)
                    .map(file -> dir.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
            StringBuilder listing = new StringBuilder();
            for (String path : paths) {
                listing.append(NdcCorpus.sha256(Files.readAllBytes(dir.resolve(path)))).append("  ").append(path)
                        .append('\n');
            }
            return NdcCorpus.sha256(listing.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
