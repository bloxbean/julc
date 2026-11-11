package org.julclang.vm.java.ndc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The vendored corpus is exactly the pinned upstream files: no edit, no lost file, no line-ending rewrite. */
class UplcConformanceCorpusTest {

    @Test
    void theCorpusIsTheUnpackedPinnedTarball() {
        assertEquals(UplcConformanceCorpus.TREE_SHA256,
                UplcConformanceCorpus.treeSha256(UplcConformanceCorpus.corpus()),
                "the files under ndc/uplc-conformance/corpus differ from the pinned upstream tarball "
                        + UplcConformanceCorpus.TARBALL_SHA256 + "; refresh them as the README there describes");
    }

    @Test
    void theExpectationsAreThePinnedFile() throws IOException {
        assertEquals(UplcConformanceCorpus.EXPECTATIONS_SHA256,
                NdcCorpus.sha256(Files.readAllBytes(UplcConformanceCorpus.expectations())),
                "ndc/uplc-conformance/expectations.json differs from the pinned upstream file");
    }

    @Test
    void theCorpusHoldsEveryVectorTheExpectationsName() throws IOException {
        int expectations = NdcReports.JSON.readTree(UplcConformanceCorpus.expectations().toFile())
                .get("scenarios").size();
        assertEquals(1064, expectations);
        assertEquals(expectations, UplcVector.list(UplcConformanceCorpus.corpus()).size());
    }
}
