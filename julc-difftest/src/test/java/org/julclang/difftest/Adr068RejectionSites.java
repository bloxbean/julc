package org.julclang.difftest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * ADR-068 I2a coverage (F301): the hand-maintained catalogue of the reference frontend's rejection sites
 * ({@code adr068/rejection-sites.tsv}), checked against the generated inventory. The catalogue lists every code
 * {@code diagnostics.json} marks {@code emitted} and every uncoded rejection (a message pattern), each either
 * reachable from source (it must have inventory rows, from the recorded tests or the rejection fixtures) or
 * explained as not a source rejection (configuration, the producer-side backend contract, internal invariants).
 * Conversely, every inventory row must be explained by some entry.
 */
final class Adr068RejectionSites {

    /** One catalogue entry. */
    record Site(String id, String kind, String match, String site, String reachability, Pattern pattern) {
        boolean source() {
            return reachability.equals("source");
        }

        boolean matches(Adr068Ledger.InventoryRow row) {
            var d = row.diagnostic();
            if (kind.equals("code")) return match.equals(d.code());
            return d.code() == null && pattern.matcher(d.message()).find();
        }
    }

    /** The coverage of each site, and what nothing explains. */
    record Coverage(Map<Site, Integer> rows, List<String> uncovered, List<String> uncatalogued) {
        int sites() {
            return rows.size();
        }

        long covered() {
            return rows.values().stream().filter(n -> n > 0).count();
        }

        long notSource() {
            return rows.keySet().stream().filter(s -> !s.source()).count();
        }

        List<String> lines() {
            var lines = new ArrayList<String>();
            lines.add("site\tkind\tmatch\twhere\treachability\tinventory-rows");
            rows.forEach((s, n) -> lines.add(s.id() + "\t" + s.kind() + "\t" + s.match() + "\t" + s.site() + "\t"
                    + s.reachability() + "\t" + n));
            return lines;
        }
    }

    private static final Pattern EMITTED = Pattern.compile(
            "\"code\"\\s*:\\s*\"(JULC\\d{4})\"[^{}]*?\"status\"\\s*:\\s*\"emitted\"");

    static List<Site> load(Path catalogue) throws IOException {
        var sites = new ArrayList<Site>();
        for (String line : Files.readAllLines(catalogue)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            if (c.length != 5) throw new IllegalStateException("rejection-sites.tsv: five columns expected: " + line);
            sites.add(new Site(c[0], c[1], c[2], c[3], c[4], c[1].equals("uncoded") ? Pattern.compile(c[2]) : null));
        }
        return sites;
    }

    static Coverage coverage(Path catalogue, Path diagnosticsJson, List<Adr068Ledger.InventoryRow> inventory)
            throws IOException {
        var sites = load(catalogue);
        var catalogued = new TreeSet<String>();
        sites.stream().filter(s -> s.kind().equals("code")).forEach(s -> catalogued.add(s.match()));
        var missingCodes = new TreeSet<String>();
        var m = EMITTED.matcher(Files.readString(diagnosticsJson));
        while (m.find()) if (!catalogued.contains(m.group(1))) missingCodes.add(m.group(1));
        if (!missingCodes.isEmpty()) {
            throw new IllegalStateException("emitted codes missing from rejection-sites.tsv: " + missingCodes);
        }
        var rows = new LinkedHashMap<Site, Integer>();
        sites.forEach(s -> rows.put(s, 0));
        var uncatalogued = new TreeSet<String>();
        for (var row : inventory) {
            boolean explained = false;
            for (var s : sites) {
                if (s.matches(row)) {
                    rows.merge(s, 1, Integer::sum);
                    explained = true;
                }
            }
            if (!explained) {
                var d = row.diagnostic();
                uncatalogued.add((d.code() == null ? "uncoded:" + row.exception() : d.code()) + " "
                        + Adr068Ledger.oneLine(d.message(), 120));
            }
        }
        var uncovered = new ArrayList<String>();
        rows.forEach((s, n) -> {
            if (s.source() && n == 0) uncovered.add(s.id());
        });
        return new Coverage(rows, uncovered, List.copyOf(uncatalogued));
    }
}
