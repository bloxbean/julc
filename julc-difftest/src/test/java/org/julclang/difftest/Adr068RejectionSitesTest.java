package org.julclang.difftest;

import org.julclang.compiler.JulcCompiler;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** ADR-068 I2a's coverage gate (F311): its catalogue and the contradictions it rejects. */
class Adr068RejectionSitesTest {

    private static final Path DIAGNOSTICS = Path.of(System.getProperty("adr068.repo", ".."))
            .resolve("julc-compiler/src/main/resources/diagnostics.json");

    /** The wildcard type arguments the compiler cannot resolve, written in a public {@code compileMethod} source. */
    private static final List<String> WILDCARDS = List.of("List<?>", "List<? extends String>", "Optional<?>",
            "Map<String, ?>");

    /**
     * F311's probes: every wildcard rejection is explained by a catalogue site reachable from source, and none
     * matches a site the catalogue calls unreachable.
     */
    @Test
    void wildcardRejectionsAreSourceSites() throws Exception {
        var rows = new ArrayList<Adr068Ledger.InventoryRow>();
        for (String type : WILDCARDS) {
            String source = "import java.math.BigInteger;\nimport java.util.*;\n"
                    + "class F { static BigInteger m(" + type + " x) { return BigInteger.ONE; } }\n";
            try {
                new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "m");
                fail(type + " compiled");
            } catch (RuntimeException e) {
                for (var d : Adr068Ledger.rejection(e).diagnostics()) {
                    assertTrue(d.message().startsWith("Cannot resolve type: "), type + ": " + d.message());
                    rows.add(row(d));
                }
            }
        }
        var coverage = Adr068RejectionSites.coverage(catalogue(), DIAGNOSTICS, rows);
        assertEquals(List.of(), coverage.uncatalogued());
        assertEquals(List.of(), coverage.contradicted());
        var site = coverage.rows().keySet().stream().filter(s -> s.id().equals("U-cannot-resolve-type")).findFirst().orElseThrow();
        assertTrue(site.source(), site.reachability());
        assertEquals(WILDCARDS.size(), coverage.rows().get(site));
    }

    /** A row at a site catalogued as not a source rejection fails the gate: the catalogue is then wrong. */
    @Test
    void aRowAtAnUnreachableSiteIsAContradiction(@TempDir Path temp) throws IOException, URISyntaxException {
        Path catalogue = temp.resolve("sites.tsv");
        Files.writeString(catalogue, Files.readString(catalogue())
                + "U-probe\tuncoded\t^Probe-only defensive rejection\tProbe:1\tdefensive: never reached\n");
        var reached = row(new Adr068Ledger.Diagnostic("ERROR", null, "Probe-only defensive rejection", "", 0, 0));
        var coverage = Adr068RejectionSites.coverage(catalogue, DIAGNOSTICS, List.of(reached));
        assertEquals(1, coverage.contradicted().size(), coverage.contradicted().toString());
        assertTrue(coverage.contradicted().getFirst().startsWith("U-probe (defensive: never reached): "),
                coverage.contradicted().toString());
        var unreached = Adr068RejectionSites.coverage(catalogue, DIAGNOSTICS, List.of());
        assertEquals(List.of(), unreached.contradicted());
    }

    private static Adr068Ledger.InventoryRow row(Adr068Ledger.Diagnostic d) {
        return new Adr068Ledger.InventoryRow("probe", "compile", d, "CompilerException", "all",
                new Adr068Ledger.Classification(false, "probe"), "probe");
    }

    private static Path catalogue() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(Adr068RejectionSitesTest.class.getResource("/adr068/rejection-sites.tsv")).toURI());
    }
}
