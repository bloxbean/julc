package org.julclang.vm.java.ndc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UplcVectorTest {

    @Test
    void labelsAVectorAsTheExpectationsDo() {
        Path relative = Path.of("protocol-version-11/v3/builtin/semantics/dropList/dropList-09/dropList-09.uplc");
        UplcVector vector = UplcVector.of(relative, Path.of("/corpus").resolve(relative));

        assertEquals("builtin_semantics_droplist_droplist_09_v3", vector.id());
        assertEquals("droplist 09", vector.title());
        assertEquals("builtin/semantics/dropList", vector.group());
        assertEquals("V3", vector.plutusVersion());
        assertEquals("11.0", vector.protocolVersion());
        assertEquals("dropList-09.uplc.expected", vector.expected().getFileName().toString());
        assertEquals("dropList-09.flat", vector.flat().getFileName().toString());
    }

    @Test
    void aVectorDirectlyBelowThePlutusVersionIsItsOwnGroup() {
        Path relative = Path.of("protocol-version-10/v1/program/program.uplc");
        UplcVector vector = UplcVector.of(relative, relative);

        assertEquals("program_v1", vector.id());
        assertEquals("program", vector.group());
        assertEquals("10.0", vector.protocolVersion());
    }

    @Test
    void refusesOtherPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> UplcVector.of(Path.of("protocol-version-11/v4/term/a/a.uplc"), Path.of("a.uplc")));
        assertThrows(IllegalArgumentException.class,
                () -> UplcVector.of(Path.of("pv-11/v3/term/a/a.uplc"), Path.of("a.uplc")));
        assertThrows(IllegalArgumentException.class,
                () -> UplcVector.of(Path.of("protocol-version-11/v3/a.uplc"), Path.of("a.uplc")));
    }

    @Test
    void listsTheVectorsButNotTheCostModels(@TempDir Path root) throws IOException {
        for (String file : List.of("protocol-version-11/v3/term/b/b.uplc", "protocol-version-10/v2/term/a/a.uplc",
                "cost-models/protocol-version-11/x/y.uplc", "protocol-version-11/v3/term/b/b.uplc.expected")) {
            Files.createDirectories(root.resolve(file).getParent());
            Files.writeString(root.resolve(file), "");
        }

        List<UplcVector> vectors = UplcVector.list(root);

        assertEquals(List.of("term_a_v2", "term_b_v3"), vectors.stream().map(UplcVector::id).toList());
    }
}
