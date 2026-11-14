package org.julclang.vm.java.ndc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Where the node-diversity scenarios find their corpora and write their reports, and the parts every report shares.
 * The Gradle tasks {@code ndcUplcConformance} and {@code ndcUplcTurbo} set the system properties.
 */
final class NdcReports {

    /** The implementation name the reports carry. */
    static final String IMPLEMENTATION = "julc";

    static final ObjectMapper JSON = new ObjectMapper();

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private NdcReports() {
    }

    /** @return the download cache directory */
    static Path cacheDir() {
        return Path.of(property("ndc.cache.dir"));
    }

    /** @return the report directory */
    static Path reportDir() {
        return Path.of(property("ndc.report.dir"));
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("system property " + name + " is not set: run the scenario through its "
                    + "Gradle task (./gradlew :julc-vm-java:ndcUplcConformance or :julc-vm-java:ndcUplcTurbo)");
        }
        return value;
    }

    /** @return a report holding the fields every scenario requires */
    static ObjectNode report(String scenario) {
        ObjectNode report = JSON.createObjectNode();
        report.put("version", "1");
        report.put("scenario", scenario);
        report.put("generated_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        report.putObject("implementation").put("name", IMPLEMENTATION);
        return report;
    }

    /**
     * Writes {@code julc-<version>-<yyyymmdd>-<scenario>.json}, the name the site looks for among a node's release
     * assets.
     *
     * @return the file written
     */
    static Path writeJson(String scenario, ObjectNode report) {
        String version = System.getProperty("ndc.version", "dev");
        Path file = reportDir().resolve(IMPLEMENTATION + "-" + version + "-" + DAY.format(Instant.now()) + "-"
                + scenario + ".json");
        try {
            Files.createDirectories(file.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("Report: " + file);
        return file;
    }

    /** Writes {@code <scenario>-summary.txt} and prints it. */
    static Path writeSummary(String scenario, String summary) {
        Path file = reportDir().resolve(scenario + "-summary.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, summary, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(summary);
        System.out.println("Summary: " + file);
        return file;
    }

    /** @return the machine block of a performance report */
    static ObjectNode machine() {
        var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        ObjectNode machine = JSON.createObjectNode();
        machine.put("os", System.getProperty("os.name"));
        machine.put("os_version", System.getProperty("os.version"));
        machine.put("architecture", System.getProperty("os.arch"));
        machine.put("cpu", cpu().orElse(null));
        machine.put("logical_cores", Runtime.getRuntime().availableProcessors());
        machine.put("memory_bytes", os.getTotalMemorySize());
        return machine;
    }

    /** @return the CPU model: {@code /proc/cpuinfo} on Linux, {@code sysctl} on macOS */
    private static Optional<String> cpu() {
        try {
            Path cpuinfo = Path.of("/proc/cpuinfo");
            if (Files.isReadable(cpuinfo)) {
                return Files.readAllLines(cpuinfo).stream().filter(l -> l.startsWith("model name"))
                        .map(l -> l.substring(l.indexOf(':') + 1).trim()).findFirst();
            }
            Process process = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start();
            String brand = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor(5, TimeUnit.SECONDS) && !brand.isEmpty() ? Optional.of(brand) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
