package com.bloxbean.cardano.julc.compiler.resolve;

import com.bloxbean.cardano.julc.compiler.CompilerException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads bundled ledger type Java sources from the classpath.
 * <p>
 * Ledger sources are bundled in {@code META-INF/ledger-sources/} by the
 * {@code bundleLedgerSources} Gradle task in julc-ledger-api. Each .java file
 * is a ledger type definition (record or sealed interface) that can be
 * parsed by JavaParser and registered via {@link TypeRegistrar}.
 */
public final class LedgerSourceLoader {

    private static final String RESOURCE_DIR = "META-INF/ledger-sources/";
    private static final String INDEX_FILE = RESOURCE_DIR + "index.txt";

    private LedgerSourceLoader() {}

    /**
     * Load all bundled ledger sources from the classpath.
     *
     * @param classLoader the class loader to use for resource loading
     * @return parsed compilation units for all ledger type sources
     * @throws CompilerException if sources cannot be loaded or parsed
     */
    public static List<CompilationUnit> loadLedgerSources(ClassLoader classLoader) {
        var indexUrl = classLoader.getResource(INDEX_FILE);
        if (indexUrl == null) {
            throw new CompilerException(
                    "Ledger sources not found on classpath. Ensure julc-ledger-api is a dependency. "
                    + "Expected: " + INDEX_FILE);
        }

        List<String> fileNames;
        try (var reader = new BufferedReader(
                new InputStreamReader(indexUrl.openStream(), StandardCharsets.UTF_8))) {
            fileNames = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new CompilerException("Failed to read ledger source index: " + e.getMessage());
        }

        var cus = new ArrayList<CompilationUnit>(fileNames.size());
        for (var fileName : fileNames) {
            var resourcePath = RESOURCE_DIR + fileName;
            var sourceUrl = classLoader.getResource(resourcePath);
            if (sourceUrl == null) {
                throw new CompilerException("Ledger source listed in index but not found: " + resourcePath);
            }
            try (var stream = sourceUrl.openStream()) {
                var source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                cus.add(StaticJavaParser.parse(source));
            } catch (IOException e) {
                throw new CompilerException("Failed to read ledger source " + fileName + ": " + e.getMessage());
            }
        }
        return cus;
    }
}
